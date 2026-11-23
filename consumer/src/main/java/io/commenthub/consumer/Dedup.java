package io.commenthub.consumer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 按 (消费者, 事件 ID) 去重。必须和业务更新在同一个数据库事务里调用：
 * 业务失败回滚时去重记录一起回滚，重试时还能再处理；业务提交后再重放，INSERT IGNORE 返回 0，直接跳过。
 */
@Component
public class Dedup {
    private final JdbcTemplate jdbc;

    public Dedup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 返回这批里第一次见到的事件 ID。
     * CommentCreated 的 key 是 videoId，同一个 eventId 会进同一个分区，分区内只有一个消费者线程，先查后插没有竞争。
     * 生产配置开了 rewriteBatchedStatements，批量 INSERT IGNORE 的返回值不能用来区分哪一行真正插入。
     * 如果同一个 eventId 被用不同的 key 投到两个分区，两笔事务可能都认为自己是第一次。
     */
    public Set<String> firstTimeBatch(String consumer, Collection<String> eventIds) {
        if (eventIds.isEmpty()) return Set.of();
        String in = String.join(",", Collections.nCopies(eventIds.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(consumer);
        args.addAll(eventIds);
        Set<String> seen = new HashSet<>(jdbc.queryForList(
                "SELECT event_id FROM consumed_event WHERE consumer = ? AND event_id IN (" + in + ")", String.class, args.toArray()));
        Set<String> fresh = new LinkedHashSet<>();
        for (String id : eventIds) if (!seen.contains(id)) fresh.add(id);
        if (!fresh.isEmpty()) {
            jdbc.batchUpdate("INSERT IGNORE INTO consumed_event (consumer, event_id) VALUES (?, ?)",
                    fresh.stream().map(id -> new Object[]{consumer, id}).toList());
        }
        return fresh;
    }

    /** 去重表只需覆盖“可能被重放”的时间范围，保留 7 天。 */
    @Scheduled(cron = "0 17 3 * * *")
    public void purge() {
        jdbc.update("DELETE FROM consumed_event WHERE created_at < NOW() - INTERVAL 7 DAY LIMIT 100000");
    }
}
