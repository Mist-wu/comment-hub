package io.commenthub.comment.service;

import io.commenthub.comment.cache.RedisMutex;
import io.commenthub.comment.domain.Comment;
import io.commenthub.comment.mapper.CommentMapper;
import io.commenthub.comment.mapper.VideoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 最热排序的优化版：定时把评论数最多的 N 个视频的前 1000 条评论（按点赞数）算好写进 ZSet。
 * 读的时候 ZREVRANGE 取一页 id 再按主键批量取正文，不再在请求路径上做 filesort。
 * 写入用“临时 key + RENAME”，读方不会看到半成品。多实例时用分布式锁保证只有一个实例在算。
 */
@Component
public class HotRankJob {
    private static final Logger log = LoggerFactory.getLogger(HotRankJob.class);

    public static String key(long videoId) {
        return "hot:z:" + videoId;
    }

    public static String metaKey(long videoId) {
        return key(videoId) + ":meta";
    }

    /** 固定 20 位，ZREVRANGE 的字典序倒序才和数值 id DESC 一致。旧榜单里的 "99" 仍能 parse。 */
    public static String member(long id) {
        return String.format(Locale.ROOT, "%020d", id);
    }

    public static long parseMember(String member) {
        return Long.parseLong(member);
    }

    public static boolean isPadded(String member) {
        if (member == null || member.length() != 20) return false;
        for (int i = 0; i < member.length(); i++) {
            char c = member.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** meta 形如 {@code generation:0} 或 {@code generation:1}。没有 meta 的是旧榜单。 */
    public static Long generation(String meta) {
        if (meta == null) return null;
        int i = meta.indexOf(':');
        if (i <= 0) return null;
        try {
            return Long.parseLong(meta.substring(0, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 写满了 topComments 才可能还有榜外的评论。旧榜单没有 meta 时，满 1000 条按写满处理。 */
    public static boolean capped(String meta, long card) {
        if (meta != null && meta.endsWith(":1")) return true;
        if (meta != null && meta.endsWith(":0")) return false;
        return card >= 1000;
    }

    private final VideoMapper videos;
    private final CommentMapper comments;
    private final StringRedisTemplate redis;
    private final RedisMutex mutex;
    private final int topVideos;
    private final int topComments;

    public HotRankJob(VideoMapper videos, CommentMapper comments, StringRedisTemplate redis, RedisMutex mutex,
                      @Value("${hub.hot-rank.videos:100}") int topVideos,
                      @Value("${hub.hot-rank.comments:1000}") int topComments) {
        this.videos = videos;
        this.comments = comments;
        this.redis = redis;
        this.mutex = mutex;
        this.topVideos = topVideos;
        this.topComments = topComments;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        Thread.ofVirtual().name("hot-rank-warmup").start(this::scheduled);
    }

    @Scheduled(fixedDelayString = "${hub.hot-rank.interval-ms:60000}", initialDelay = 60_000)
    public void scheduled() {
        String token = mutex.tryLock("lock:hot-rank", Duration.ofMinutes(5));
        if (token == null) return;
        try {
            rebuild();
        } finally {
            mutex.unlock("lock:hot-rank", token);
        }
    }

    public int rebuild() {
        long t0 = System.currentTimeMillis();
        long generation = t0;
        List<Long> ids = videos.topByComments(topVideos);
        for (long v : ids) {
            List<Comment> top = comments.topByLikes(v, topComments);
            if (top.isEmpty()) continue;
            Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
            for (Comment c : top) {
                // 成员补齐到 20 位。同分时 ZREVRANGE 按字典序倒序，补齐后就是 id DESC，和 SQL 一致。
                tuples.add(new DefaultTypedTuple<>(member(c.getId()), (double) c.getLikeCount()));
            }
            String tmp = key(v) + ":tmp";
            redis.delete(tmp);
            redis.opsForZSet().add(tmp, tuples);
            redis.rename(tmp, key(v));
            redis.expire(key(v), Duration.ofMinutes(10));
            boolean full = top.size() >= topComments;
            redis.opsForValue().set(metaKey(v), generation + ":" + (full ? "1" : "0"), Duration.ofMinutes(10));
        }
        log.info("hot rank rebuilt videos={} cost={}ms", ids.size(), System.currentTimeMillis() - t0);
        return ids.size();
    }
}
