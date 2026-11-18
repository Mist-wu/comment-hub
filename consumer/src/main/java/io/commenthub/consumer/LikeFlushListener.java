package io.commenthub.consumer;

import io.commenthub.common.event.LikeChanged;
import io.commenthub.common.event.Topics;
import io.commenthub.common.trace.Trace;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 点赞数落库：一批 LikeChanged 先去重，再按 commentId 求和，每条评论只发一条
 * UPDATE comment SET like_count = like_count + ?。同一条评论 1 秒内被点 1 万次，落库只是几条 UPDATE。
 * 去重和更新在同一个事务里，整批失败整批回滚重试。
 */
@Component
public class LikeFlushListener {
    private static final Logger log = LoggerFactory.getLogger(LikeFlushListener.class);
    private final Events events;
    private final Dedup dedup;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    public LikeFlushListener(Events events, Dedup dedup, JdbcTemplate jdbc, TransactionTemplate tx, MeterRegistry meters) {
        this.events = events;
        this.dedup = dedup;
        this.jdbc = jdbc;
        this.tx = tx;
        this.meters = meters;
    }

    @KafkaListener(topics = Topics.LIKE_CHANGED, groupId = "like-flush", containerFactory = "batchFactory")
    public void flush(List<ConsumerRecord<String, String>> batch) {
        try (var s = Trace.open(batch.isEmpty() ? null : Events.traceId(batch.get(0)))) {
            List<LikeChanged> evs = batch.stream().map(r -> events.parse(r, LikeChanged.class)).toList();
            apply(evs);
            long newest = evs.stream().mapToLong(LikeChanged::atMillis).max().orElse(System.currentTimeMillis());
            events.recordE2e("like-flush", newest);
            meters.summary("hub.like.flush.batch_size").record(batch.size());
        }
    }

    private void apply(List<LikeChanged> evs) {
        tx.executeWithoutResult(st -> {
            Map<Long, Integer> deltas = merge(evs, ids -> dedup.firstTimeBatch("like-flush", ids));
            List<Object[]> args = new ArrayList<>();
            // 按 id 排序更新，多个消费者线程同时更新多行时加锁顺序一致，避免死锁
            new TreeMap<>(deltas).forEach((id, d) -> {
                if (d != 0) args.add(new Object[]{d, id});
            });
            if (!args.isEmpty()) jdbc.batchUpdate("UPDATE comment SET like_count = like_count + ? WHERE id = ?", args);
            meters.summary("hub.like.flush.rows").record(args.size());
            log.debug("like flush events={} rows={}", evs.size(), args.size());
        });
    }

    /** 纯函数部分：去重后按评论求和。单独拿出来便于单元测试。 */
    static Map<Long, Integer> merge(List<LikeChanged> evs, java.util.function.Function<Collection<String>, Set<String>> firstTime) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (LikeChanged e : evs) ids.add(e.eventId());
        Set<String> fresh = new HashSet<>(firstTime.apply(ids));
        Map<Long, Integer> deltas = new HashMap<>();
        for (LikeChanged e : evs) {
            if (fresh.remove(e.eventId())) deltas.merge(e.commentId(), e.delta(), Integer::sum);
        }
        return deltas;
    }
}
