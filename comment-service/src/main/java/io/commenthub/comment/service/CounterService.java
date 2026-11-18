package io.commenthub.comment.service;

import io.commenthub.comment.config.Toggles;
import io.commenthub.comment.mapper.CommentMapper;
import io.commenthub.comment.mapper.VideoMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 评论数。朴素版每次 COUNT(*)；优化版读 Redis，未命中读 video.comment_count（consumer 维护）再回填。
 */
@Service
public class CounterService {
    public static String key(long videoId) {
        return "video:cc:" + videoId;
    }

    private final StringRedisTemplate redis;
    private final VideoMapper videos;
    private final CommentMapper comments;
    private final Toggles toggles;

    public CounterService(StringRedisTemplate redis, VideoMapper videos, CommentMapper comments, Toggles toggles) {
        this.redis = redis;
        this.videos = videos;
        this.comments = comments;
        this.toggles = toggles;
    }

    public long commentCount(long videoId) {
        if (toggles.countMode == Toggles.CountMode.COUNT) {
            return comments.countByVideo(videoId);
        }
        String v = redis.opsForValue().get(key(videoId));
        if (v != null) return Long.parseLong(v);
        Long n = videos.commentCount(videoId);
        if (n == null) throw new NotFoundException("video " + videoId);
        // SET NX：并发回填时谁先写都一样，不会覆盖 consumer 已经 INCR 过的值
        redis.opsForValue().setIfAbsent(key(videoId), String.valueOf(n), Duration.ofHours(1));
        return n;
    }
}
