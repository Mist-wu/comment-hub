package io.commenthub.consumer;

import io.commenthub.ai.v1.AiGatewayGrpc;
import io.commenthub.ai.v1.Label;
import io.commenthub.ai.v1.ModerateReply;
import io.commenthub.ai.v1.ModerateRequest;
import io.commenthub.common.event.CommentCreated;
import io.commenthub.common.event.Topics;
import io.commenthub.common.trace.Trace;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * CommentCreated 的三个消费者，各自一个消费者组、各自去重、各自重试和进死信。
 * 拆开的原因：审核依赖模型，可能慢、可能失败；不能让它拖住计数和删缓存，
 * 也不能因为计数已经做过，导致重放时审核被一起跳过。
 */
@Component
public class CommentCreatedListeners {
    private static final Logger log = LoggerFactory.getLogger(CommentCreatedListeners.class);
    private static final DefaultRedisScript<Long> INCRBY_IF_EXISTS = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return redis.call('incrby', KEYS[1], ARGV[1]) end return nil", Long.class);

    private final Events events;
    private final Dedup dedup;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final AiGatewayGrpc.AiGatewayBlockingStub ai;
    private final ConsumerToggles toggles;
    private final java.util.concurrent.ExecutorService moderationPool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final java.util.concurrent.Semaphore permits;

    public CommentCreatedListeners(Events events, Dedup dedup, JdbcTemplate jdbc, TransactionTemplate tx,
                                   StringRedisTemplate redis, AiGatewayGrpc.AiGatewayBlockingStub ai, ConsumerToggles toggles,
                                   @org.springframework.beans.factory.annotation.Value("${hub.moderation.parallelism:32}") int parallelism) {
        this.permits = new java.util.concurrent.Semaphore(parallelism);
        this.events = events;
        this.dedup = dedup;
        this.jdbc = jdbc;
        this.tx = tx;
        this.redis = redis;
        this.ai = ai;
        this.toggles = toggles;
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
            Map<Long, Integer> deltas = tx.execute(st -> {
                Set<String> fresh = dedup.firstTimeBatch("counter",
                        evs.stream().map(CommentCreated::eventId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
                Map<Long, Integer> d = new TreeMap<>();
                for (CommentCreated e : evs) if (fresh.remove(e.eventId())) d.merge(e.videoId(), 1, Integer::sum);
                List<Object[]> args = d.entrySet().stream().map(x -> new Object[]{x.getValue(), x.getKey()}).toList();
                if (!args.isEmpty()) jdbc.batchUpdate("UPDATE video SET comment_count = comment_count + ? WHERE id = ?", args);
                return d;
            });
            int applied = 0;
            for (var x : deltas.entrySet()) {
                redis.execute(INCRBY_IF_EXISTS, List.of("video:cc:" + x.getKey()), String.valueOf(x.getValue()));
                applied += x.getValue();
            }
            if (applied < evs.size()) log.info("duplicate events skipped consumer=counter n={}", evs.size() - applied);
            evs.stream().mapToLong(CommentCreated::createdAtMillis).max().ifPresent(t -> events.recordE2e("counter", t));
        }
    }

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
     * 单线程逐条调用时吞吐只有 1 / 模型延迟（3 个分区 ≈ 7 条/秒），一次突发写就会积压几万条。
     * 超出模型 QPS / token 预算的部分由网关降级到关键词规则，所以积压能很快消化完。
     *
     * 网关自己会降级，这里的异常只可能是网关不可达或超过 deadline。某条失败时抛
     * BatchListenerFailedException(下标)：它之前的 offset 照常提交，从它开始重试，3 次后进死信；
     * 同批里已经成功的记录重试时会被去重表跳过。
     */
    @KafkaListener(topics = Topics.COMMENT_CREATED, groupId = "moderation", containerFactory = "batchFactory")
    public void moderateBatch(List<ConsumerRecord<String, String>> batch) {
        List<CommentCreated> evs = batch.stream().map(r -> events.parse(r, CommentCreated.class)).toList();
        Set<String> done = alreadyDone(evs);
        List<java.util.concurrent.Future<Verdict>> fs = new ArrayList<>(evs.size());
        for (int i = 0; i < evs.size(); i++) {
            CommentCreated e = evs.get(i);
            String trace = Events.traceId(batch.get(i));
            if (done.contains(e.eventId())) {
                fs.add(java.util.concurrent.CompletableFuture.completedFuture(null));
                continue;
            }
            fs.add(moderationPool.submit(() -> {
                permits.acquireUninterruptibly();
                try (var s = Trace.open(trace)) {
                    return call(e);
                } finally {
                    permits.release();
                }
            }));
        }
        // 按顺序收集结果；第一条失败的记录之前的结果先落库，再从它开始重试
        List<Verdict> ok = new ArrayList<>();
        RuntimeException failure = null;
        int failedAt = -1;
        for (int i = 0; i < fs.size(); i++) {
            try {
                Verdict v = fs.get(i).get();
                if (v != null && failure == null) ok.add(v);
            } catch (java.util.concurrent.ExecutionException ex) {
                if (failure == null) {
                    failure = ex.getCause() instanceof RuntimeException re ? re : new IllegalStateException(ex.getCause());
                    failedAt = i;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new org.springframework.kafka.listener.BatchListenerFailedException("interrupted", ex, i);
            }
        }
        persist(ok);
        if (failure != null) {
            throw new org.springframework.kafka.listener.BatchListenerFailedException("moderation failed", failure, failedAt);
        }
    }

    /** 模型调用前先查去重表，已经审核过的事件不再花钱调模型。 */
    private Set<String> alreadyDone(List<CommentCreated> evs) {
        if (evs.isEmpty()) return Set.of();
        String in = String.join(",", java.util.Collections.nCopies(evs.size(), "?"));
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

    /** 一批审核结果一个事务：去重记录 + status 更新一起提交。 */
    private void persist(List<Verdict> vs) {
        if (vs.isEmpty()) return;
        List<Verdict> fresh = tx.execute(st -> {
            Set<String> first = dedup.firstTimeBatch("moderation",
                    vs.stream().map(v -> v.event().eventId()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
            List<Verdict> out = vs.stream().filter(v -> first.contains(v.event().eventId()))
                    .sorted(java.util.Comparator.comparingLong(v -> v.event().commentId())).toList();
            if (!out.isEmpty()) jdbc.batchUpdate("UPDATE comment SET status = ? WHERE id = ?",
                    out.stream().map(v -> new Object[]{v.status(), v.event().commentId()}).toList());
            return out;
        });
        List<String> evict = new ArrayList<>();
        for (Verdict v : fresh) {
            // 被拒的评论可能已经进了首页缓存，删掉让它下次重建时被过滤
            if (v.label() == Label.REJECT) {
                evict.add("cm:latest:" + v.event().videoId());
                evict.add("cm:hot:" + v.event().videoId());
            }
            events.recordE2e("moderation", v.event().createdAtMillis());
        }
        if (!evict.isEmpty()) redis.delete(evict);
    }
}
