package io.commenthub.comment.service;

import io.commenthub.comment.mapper.CommentLikeMapper;
import io.commenthub.comment.mapper.CommentMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 点赞数对账：以 comment_like 的行数为真值（唯一键保证一人一行），
 * 检查最近有变动的评论（like:dirty 集合）的 MySQL like_count 和 Redis 计数。
 *
 * <p>在 Kafka 还有积压时，MySQL 落后是正常的；所以 fix 应在积压清零后执行（压测脚本会先等 lag=0）。
 */
@Component
public class LikeReconciler {
    private final StringRedisTemplate redis;
    private final CommentLikeMapper likes;
    private final CommentMapper comments;

    public LikeReconciler(StringRedisTemplate redis, CommentLikeMapper likes, CommentMapper comments) {
        this.redis = redis;
        this.likes = likes;
        this.comments = comments;
    }

    public Map<String, Object> reconcileDirty(int limit, boolean fix) {
        List<String> ids = Optional.ofNullable(redis.opsForSet().pop(LikeService.DIRTY_SET, limit)).orElse(List.of());
        int dbDiff = 0, redisDiff = 0;
        List<Map<String, Object>> samples = new ArrayList<>();
        for (String s : ids) {
            long id = Long.parseLong(s);
            long truth = likes.countByComment(id);
            Integer db = comments.likeCount(id);
            String rv = redis.opsForValue().get(LikeService.key(id));
            Long rc = rv == null ? null : Long.parseLong(rv);
            boolean dbBad = db != null && db != truth;
            boolean redisBad = rc != null && rc != truth;
            if (dbBad) dbDiff++;
            if (redisBad) redisDiff++;
            if ((dbBad || redisBad) && samples.size() < 20) {
                samples.add(Map.of("commentId", id, "truth", truth, "mysql", String.valueOf(db), "redis", String.valueOf(rc)));
            }
            if (fix) {
                if (dbBad) comments.setLikeCount(id, truth);
                if (redisBad) redis.opsForValue().set(LikeService.key(id), String.valueOf(truth));
            }
        }
        if (!fix && !ids.isEmpty()) redis.opsForSet().add(LikeService.DIRTY_SET, ids.toArray(String[]::new));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checked", ids.size());
        out.put("mysqlMismatch", dbDiff);
        out.put("redisMismatch", redisDiff);
        out.put("fixed", fix);
        out.put("samples", samples);
        return out;
    }
}
