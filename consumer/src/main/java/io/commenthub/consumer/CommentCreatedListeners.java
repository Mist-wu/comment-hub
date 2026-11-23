package io.commenthub.consumer;

import io.commenthub.ai.v1.AiGatewayGrpc;
import io.commenthub.ai.v1.Label;
import io.commenthub.ai.v1.ModerateReply;
import io.commenthub.ai.v1.ModerateRequest;
import io.commenthub.common.event.CommentCreated;
import io.commenthub.common.event.Topics;
import io.commenthub.common.trace.Trace;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * CommentCreated 的三个消费者，各自一个消费者组、各自去重、各自重试和进死信。
 * 拆开的原因：审核依赖模型，可能慢、可能失败；不能让它拖住计数和删缓存，
 * 也不能因为计数已经做过，导致重放时审核被一起跳过。
 */
@Component
public class CommentCreatedListeners {
    private static final Logger log = LoggerFactory.getLogger(CommentCreatedListeners.class);
    /**
     * 计数在数据库提交之后才改 Redis。脚本按 eventId 记一个 7 天标记，和去重表同一窗口。
     * 标记不存在才 INCRBY（key 还不存在就只记账，等读路径从库回填）。
     * 整段是一条 Lua，失败时标记和计数都没写上，重投可以再执行。
     */
    static final DefaultRedisScript<Long> INCR_ONCE = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[2]) == 1 then return 0 end "
                    + "if redis.call('exists', KEYS[1]) == 1 then redis.call('incrby', KEYS[1], ARGV[1]) end "
                    + "redis.call('set', KEYS[2], '1', 'EX', '604800') return 1", Long.class);

    private final Events events;
    private final Dedup dedup;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final AiGatewayGrpc.AiGatewayBlockingStub ai;
    private final ConsumerToggles toggles;
    private final ExecutorService moderationPool = Executors.newVirtualThreadPerTaskExecutor();
    /** 停机最多等这么久。排队中的任务能被中断；已经卡在模型调用里的，到点就留下。 */
    Duration shutdownWait = Duration.ofSeconds(5);
    private final Semaphore permits;

    public CommentCreatedListeners(Events events, Dedup dedup, JdbcTemplate jdbc, TransactionTemplate tx,
                                   StringRedisTemplate redis, AiGatewayGrpc.AiGatewayBlockingStub ai, ConsumerToggles toggles,
                                   @Value("${hub.moderation.parallelism:32}") int parallelism) {
        this.permits = new Semaphore(parallelism);
        this.events = events;
        this.dedup = dedup;
        this.jdbc = jdbc;
        this.tx = tx;
        this.redis = redis;
        this.ai = ai;
        this.toggles = toggles;
    }

    @PreDestroy
    void shutdown() {
        moderationPool.shutdownNow();
        try {
            if (!moderationPool.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("moderation pool did not stop within {}ms", shutdownWait.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 评论数：批量拉取，去重后按视频合并，一批只开一个事务、每个视频一条 UPDATE（按 id 排序，避免死锁）。
     * 逐条处理时每条事件一次提交（一次 redo + binlog fsync），突发写 2000 条/秒时和写接口抢磁盘；
     * 合并后热门视频那一行一批只更新一次，也不再是热点行。Redis 计数在提交后更新（只在 key 已存在时 INCRBY）。
     */
    @KafkaListener(topics = Topics.COMMENT_CREATED, groupId = "counter", containerFactory = "batchFactory")
    public void count(List<ConsumerRecord<String, String>> batch) {
        try (var s = Trace.open(batch.isEmpty() ? null : Events.traceId(batch.get(0)))) {
            List<CommentCreated> evs = batch.stream().map(r -> events.parse(r, CommentCreated.class)).toList();
            Counted counted = tx.execute(st -> {
                Set<String> fresh = dedup.firstTimeBatch("counter",
                        evs.stream().map(CommentCreated::eventId).collect(Collectors.toCollection(LinkedHashSet::new)));
                Map<Long, Integer> d = new TreeMap<>();
                Set<String> appliedIds = new LinkedHashSet<>();
                for (CommentCreated e : evs) {
                    if (fresh.remove(e.eventId())) {
                        appliedIds.add(e.eventId());
                        d.merge(e.videoId(), 1, Integer::sum);
                    }
                }
                List<Object[]> args = d.entrySet().stream().map(x -> new Object[]{x.getValue(), x.getKey()}).toList();
                if (!args.isEmpty()) jdbc.batchUpdate("UPDATE video SET comment_count = comment_count + ? WHERE id = ?", args);
                return new Counted(d, appliedIds);
            });
            if (counted == null) counted = new Counted(Map.of(), Set.of());
            // 重投时数据库已经记过账，fresh 为空，但 Redis 标记可能还没写上。每条事件都再跑一次脚本。
            Set<String> seen = new LinkedHashSet<>();
            for (CommentCreated e : evs) {
                if (!seen.add(e.eventId())) continue;
                redis.execute(INCR_ONCE, List.of("video:cc:" + e.videoId(), "video:cc:evt:" + e.eventId()), "1");
                if (counted.fresh().contains(e.eventId())) events.recordE2e("counter", e.createdAtMillis());
            }
            int applied = counted.deltas().values().stream().mapToInt(Integer::intValue).sum();
            if (applied < evs.size()) log.info("duplicate events skipped consumer=counter n={}", evs.size() - applied);
        }
    }

    private record Counted(Map<Long, Integer> deltas, Set<String> fresh) {}

    /** 删首页缓存。删除天然幂等，重放多少次结果都一样，不需要去重表。 */
    @KafkaListener(topics = Topics.COMMENT_CREATED, groupId = "cache")
    public void evict(ConsumerRecord<String, String> rec) {
        try (var s = Trace.open(Events.traceId(rec))) {
            CommentCreated e = events.parse(rec, CommentCreated.class);
            redis.delete(List.of("cm:latest:" + e.videoId()));
            events.recordE2e("cache", e.createdAtMillis());
        }
    }

    /**
     * 异步审核：一次拉一批，用虚拟线程并发调 ai-gateway（每条带 deadline），并发上限 = 网关舱壁大小。
     * 逐条调用时每个消费线程的吞吐只有 1 / 模型延迟（3 个消费线程 ≈ 7 条/秒），一次突发写就会积压几万条。
     * 超出模型 QPS / token 预算的部分由网关降级到关键词规则，所以积压能很快消化完。
     *
     * 网关自己会降级，这里的异常只可能是网关不可达或超过 deadline。某条失败时先把前面已经成功的结果落库，
     * 再抛 BatchListenerFailedException(下标)：只有落库成功的前缀才提交 offset，从失败的那条重试，3 次后进死信。
     * 等待被中断时同样先落库。数据库提交后的缓存删除如果失败，不带下标地抛出去，整批重试，避免 offset 提交了但缓存还在。
     */
    @KafkaListener(topics = Topics.COMMENT_CREATED, groupId = "moderation", containerFactory = "batchFactory")
    public void moderateBatch(List<ConsumerRecord<String, String>> batch) {
        List<CommentCreated> evs = batch.stream().map(r -> events.parse(r, CommentCreated.class)).toList();
        Set<String> seen = new HashSet<>(alreadyDone(evs));
        List<Future<Verdict>> fs = new ArrayList<>(evs.size());
        for (int i = 0; i < evs.size(); i++) {
            CommentCreated e = evs.get(i);
            String trace = Events.traceId(batch.get(i));
            if (!seen.add(e.eventId())) {
                fs.add(CompletableFuture.completedFuture(null));
                continue;
            }
            fs.add(moderationPool.submit(() -> {
                try {
                    permits.acquire();
                } catch (InterruptedException e1) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", e1);
                }
                try (var s = Trace.open(trace)) {
                    return call(e);
                } finally {
                    permits.release();
                }
            }));
        }
        // 按顺序收集。中断或模型失败都先落库，再按失败下标提交；中断时不能在落库前抛带下标的异常。
        List<Verdict> ok = new ArrayList<>();
        RuntimeException failure = null;
        int failedAt = -1;
        InterruptedException interrupted = null;
        for (int i = 0; i < fs.size(); i++) {
            try {
                Verdict v = fs.get(i).get();
                if (v != null && failure == null) ok.add(v);
            } catch (ExecutionException ex) {
                if (failure == null) {
                    failure = ex.getCause() instanceof RuntimeException re ? re : new IllegalStateException(ex.getCause());
                    failedAt = i;
                }
            } catch (InterruptedException ex) {
                for (int j = i; j < fs.size(); j++) fs.get(j).cancel(true);
                Thread.interrupted();
                failure = new IllegalStateException("interrupted", ex);
                failedAt = i;
                interrupted = ex;
                break;
            }
        }
        try {
            List<Verdict> fresh = persist(ok);
            evictCommittedRejects(evs.subList(0, failure == null ? evs.size() : failedAt), fresh);
        } finally {
            if (interrupted != null) Thread.currentThread().interrupt();
        }
        if (interrupted != null) {
            throw new BatchListenerFailedException("interrupted", interrupted, failedAt);
        }
        if (failure != null) {
            throw new BatchListenerFailedException("moderation failed", failure, failedAt);
        }
    }

    /** 模型调用前先查去重表，已经审核过的事件不再花钱调模型。 */
    private Set<String> alreadyDone(List<CommentCreated> evs) {
        if (evs.isEmpty()) return Set.of();
        String in = String.join(",", Collections.nCopies(evs.size(), "?"));
        List<Object> args = new ArrayList<>();
        evs.forEach(e -> args.add(e.eventId()));
        Set<String> done = new HashSet<>(jdbc.queryForList(
                "SELECT event_id FROM consumed_event WHERE consumer = 'moderation' AND event_id IN (" + in + ")", String.class, args.toArray()));
        if (!done.isEmpty()) log.info("duplicate events skipped consumer=moderation n={}", done.size());
        return done;
    }

    record Verdict(CommentCreated event, Label label, int status) {
    }

    /**
     * 调网关（带 deadline）。网关自己会降级，所以这里抛异常只可能是网关不可达或超过 deadline——
     * 交给重试，3 次后进死信，网关恢复后重放。
     */
    private Verdict call(CommentCreated e) {
        if (ThreadLocalRandom.current().nextDouble() < toggles.moderationFailRate) {
            throw new IllegalStateException("injected moderation failure");
        }
        ModerateReply r = ai.withDeadlineAfter(toggles.moderationDeadlineMs, TimeUnit.MILLISECONDS)
                .moderate(ModerateRequest.newBuilder().setCommentId(e.commentId()).setVideoId(e.videoId()).setContent(e.content()).build());
        int status = switch (r.getLabel()) {
            case REJECT -> 3;
            case REVIEW -> 2;
            default -> 1;
        };
        log.debug("moderated comment={} label={} source={} degrade={}", e.commentId(), r.getLabel(), r.getSource(), r.getDegradeReason());
        return new Verdict(e, r.getLabel(), status);
    }

    /** 一批审核结果一个事务：去重记录 + status 更新一起提交。缓存删除放在事务外面。 */
    private List<Verdict> persist(List<Verdict> vs) {
        if (vs.isEmpty()) return List.of();
        List<Verdict> fresh = tx.execute(st -> {
            Set<String> first = dedup.firstTimeBatch("moderation",
                    vs.stream().map(v -> v.event().eventId()).collect(Collectors.toCollection(LinkedHashSet::new)));
            List<Verdict> out = vs.stream().filter(v -> first.remove(v.event().eventId()))
                    .sorted(Comparator.comparingLong(v -> v.event().commentId())).toList();
            if (!out.isEmpty()) jdbc.batchUpdate("UPDATE comment SET status = ? WHERE id = ?",
                    out.stream().map(v -> new Object[]{v.status(), v.event().commentId()}).toList());
            return out;
        });
        if (fresh == null) return List.of();
        for (Verdict v : fresh) events.recordE2e("moderation", v.event().createdAtMillis());
        return fresh;
    }

    /**
     * 本次会提交 offset 的记录里，凡是拒审的都删首页和热榜缓存。
     * 重投时模型不会再调，只能按库里的 status 判断。删除失败就抛出去，让这批 offset 先别提交。
     */
    private void evictCommittedRejects(List<CommentCreated> committed, List<Verdict> fresh) {
        boolean rejectInBatch = fresh.stream().anyMatch(v -> v.label() == Label.REJECT);
        boolean maybeRetry = committed.size() != fresh.size();
        if (!rejectInBatch && !maybeRetry) return;
        List<Long> ids = committed.stream().map(CommentCreated::commentId).distinct().toList();
        if (ids.isEmpty()) return;
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Long> videos = jdbc.queryForList(
                "SELECT DISTINCT video_id FROM comment WHERE status = 3 AND id IN (" + in + ")", Long.class, ids.toArray());
        if (videos.isEmpty()) return;
        List<String> keys = new ArrayList<>(videos.size() * 2);
        for (Long videoId : videos) {
            keys.add("cm:latest:" + videoId);
            keys.add("cm:hot:" + videoId);
        }
        redis.delete(keys);
    }
}
