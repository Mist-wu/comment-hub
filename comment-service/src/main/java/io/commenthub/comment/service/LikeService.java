package io.commenthub.comment.service;

import io.commenthub.comment.config.Toggles;
import io.commenthub.comment.mapper.CommentLikeMapper;
import io.commenthub.comment.mapper.CommentMapper;
import io.commenthub.common.event.LikeChanged;
import io.commenthub.common.event.Topics;
import io.commenthub.common.trace.Trace;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 点赞 / 取消点赞。两种实现由 {@link Toggles#likeMode} 切换：
 *
 * <p>DB（朴素版）：一个事务里 INSERT IGNORE 点赞行 + UPDATE comment SET like_count = like_count + 1。
 * 在 REPEATABLE READ 下，UPDATE 是“当前读”，要拿这一行的 X 锁直到事务提交；同一条评论被大量点赞时，
 * 所有事务在这一行上排队，吞吐被锁等待和提交（redo fsync）卡住 —— 热点行。
 *
 * <p>REDIS（优化版）：点赞行仍写 MySQL（唯一键保证幂等，不同用户写的是不同行，没有热点），
 * 计数在 Redis 里 INCRBY，再发 LikeChanged 事件，由 consumer 按评论合并后批量 UPDATE 落库。
 * MySQL 的 like_count 比 Redis 落后“消费延迟 + 批间隔”，对账见 {@link LikeReconciler}。
 */
@Service
public class LikeService {
    public static final String DIRTY_SET = "like:dirty";

    public static String key(long commentId) {
        return "like:cnt:" + commentId;
    }

    /** key 存在才加，不存在返回 nil，由调用方从库里初始化，避免从 0 开始计数。 */
    private static final DefaultRedisScript<Long> INCR_IF_EXISTS = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return redis.call('incrby', KEYS[1], ARGV[1]) end return nil",
            Long.class);

    public record LikeResult(boolean changed, long likeCount) {}

    private final Toggles toggles;
    private final CommentLikeMapper likes;
    private final CommentMapper comments;
    private final StringRedisTemplate redis;
    private final EventPublisher publisher;
    private final TransactionTemplate tx;

    public LikeService(Toggles toggles, CommentLikeMapper likes, CommentMapper comments, StringRedisTemplate redis,
                       EventPublisher publisher, TransactionTemplate tx) {
        this.toggles = toggles;
        this.likes = likes;
        this.comments = comments;
        this.redis = redis;
        this.publisher = publisher;
        this.tx = tx;
    }

    public LikeResult like(long userId, long commentId) {
        return toggles.likeMode == Toggles.LikeMode.DB ? dbChange(userId, commentId, +1) : redisChange(userId, commentId, +1);
    }

    public LikeResult unlike(long userId, long commentId) {
        return toggles.likeMode == Toggles.LikeMode.DB ? dbChange(userId, commentId, -1) : redisChange(userId, commentId, -1);
    }

    private LikeResult dbChange(long userId, long commentId, int delta) {
        return tx.execute(status -> {
            int changed = delta > 0 ? likes.insertIgnore(userId, commentId) : likes.delete(userId, commentId);
            if (changed == 1 && comments.addLike(commentId, delta) == 0) {
                status.setRollbackOnly();
                throw new NotFoundException("comment " + commentId);
            }
            Integer n = comments.likeCount(commentId);
            if (n == null) throw new NotFoundException("comment " + commentId);
            return new LikeResult(changed == 1, n);
        });
    }

    private LikeResult redisChange(long userId, long commentId, int delta) {
        int changed = delta > 0 ? likes.insertIgnore(userId, commentId) : likes.delete(userId, commentId);
        if (changed == 0) {
            // 重复点赞 / 重复取消：幂等，直接返回当前计数
            return new LikeResult(false, currentCount(commentId));
        }
        Long n = redis.execute(INCR_IF_EXISTS, List.of(key(commentId)), String.valueOf(delta));
        if (n == null) {
            Integer db = comments.likeCount(commentId);
            if (db == null) {
                if (delta > 0) likes.delete(userId, commentId); // 评论不存在，撤销刚插入的点赞行
                throw new NotFoundException("comment " + commentId);
            }
            // 用库里的值初始化。此刻库里可能还缺“已发出未落库”的事件 —— 只在 key 被淘汰后才会发生，对账会修正
            redis.opsForValue().setIfAbsent(key(commentId), String.valueOf(db));
            n = redis.opsForValue().increment(key(commentId), delta);
        }
        publisher.publish(Topics.LIKE_CHANGED, String.valueOf(commentId),
                new LikeChanged(Trace.randomEventId(), commentId, userId, delta, System.currentTimeMillis()));
        redis.opsForSet().add(DIRTY_SET, String.valueOf(commentId));
        return new LikeResult(true, n == null ? 0 : n);
    }

    private long currentCount(long commentId) {
        String v = redis.opsForValue().get(key(commentId));
        if (v != null) return Long.parseLong(v);
        Integer db = comments.likeCount(commentId);
        if (db == null) throw new NotFoundException("comment " + commentId);
        return db;
    }
}
