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

import java.util.List;
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
    private static final DefaultRedisScript<Long> INCR_IF_EXISTS = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return redis.call('incr', KEYS[1]) end return nil", Long.class);

    private final Events events;
    private final Dedup dedup;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StringRedisTemplate redis;
    private final AiGatewayGrpc.AiGatewayBlockingStub ai;
    private final ConsumerToggles toggles;

    public CommentCreatedListeners(Events events, Dedup dedup, JdbcTemplate jdbc, TransactionTemplate tx,
                                   StringRedisTemplate redis, AiGatewayGrpc.AiGatewayBlockingStub ai, ConsumerToggles toggles) {
        this.events = events;
        this.dedup = dedup;
        this.jdbc = jdbc;
        this.tx = tx;
        this.redis = redis;
        this.ai = ai;
        this.toggles = toggles;
    }

    /** 评论数 +1：去重记录和 UPDATE 在同一事务；Redis 计数在提交后更新（只在 key 已存在时 INCR）。 */
    @KafkaListener(topics = Topics.COMMENT_CREATED, groupId = "counter")
    public void count(ConsumerRecord<String, String> rec) {
        try (var s = Trace.open(Events.traceId(rec))) {
            CommentCreated e = events.parse(rec, CommentCreated.class);
            Boolean applied = tx.execute(st -> {
                if (!dedup.firstTime("counter", e.eventId())) return false;
                jdbc.update("UPDATE video SET comment_count = comment_count + 1 WHERE id = ?", e.videoId());
                return true;
            });
            if (Boolean.TRUE.equals(applied)) {
                redis.execute(INCR_IF_EXISTS, List.of("video:cc:" + e.videoId()));
            } else {
                log.info("duplicate event skipped consumer=counter event={}", e.eventId());
            }
            events.recordE2e("counter", e.createdAtMillis());
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
     * 异步审核：调 ai-gateway（带 deadline），写回 status。网关自己会降级，所以这里抛异常只可能是
     * 网关不可达或超过 deadline —— 交给重试，3 次后进死信，网关恢复后重放。
     */
    @KafkaListener(topics = Topics.COMMENT_CREATED, groupId = "moderation")
    public void moderate(ConsumerRecord<String, String> rec) {
        try (var s = Trace.open(Events.traceId(rec))) {
            CommentCreated e = events.parse(rec, CommentCreated.class);
            if (ThreadLocalRandom.current().nextDouble() < toggles.moderationFailRate) {
                throw new IllegalStateException("injected moderation failure");
            }
            Integer done = jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE consumer='moderation' AND event_id=?",
                    Integer.class, e.eventId());
            if (done != null && done > 0) {
                log.info("duplicate event skipped consumer=moderation event={}", e.eventId());
                return;
            }
            ModerateReply r = ai.withDeadlineAfter(toggles.moderationDeadlineMs, TimeUnit.MILLISECONDS)
                    .moderate(ModerateRequest.newBuilder().setCommentId(e.commentId()).setVideoId(e.videoId()).setContent(e.content()).build());
            int status = switch (r.getLabel()) {
                case REJECT -> 3;
                case REVIEW -> 2;
                default -> 1;
            };
            tx.executeWithoutResult(st -> {
                if (dedup.firstTime("moderation", e.eventId())) {
                    jdbc.update("UPDATE comment SET status = ? WHERE id = ?", status, e.commentId());
                }
            });
            if (r.getLabel() == Label.REJECT) {
                // 被拒的评论可能已经进了首页缓存，删掉让它下次重建时被过滤
                redis.delete(List.of("cm:latest:" + e.videoId(), "cm:hot:" + e.videoId()));
            }
            log.info("moderated comment={} label={} source={} degrade={}", e.commentId(), r.getLabel(), r.getSource(), r.getDegradeReason());
            events.recordE2e("moderation", e.createdAtMillis());
        }
    }
}
