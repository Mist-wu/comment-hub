package io.commenthub.comment.service;

import io.commenthub.comment.cache.PageCache;
import io.commenthub.comment.config.Toggles;
import io.commenthub.comment.domain.Comment;
import io.commenthub.comment.domain.CommentView;
import io.commenthub.comment.domain.Page;
import io.commenthub.comment.mapper.CommentMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class CommentQueryService {
    public static final int DEFAULT_SIZE = 20;

    private final CommentMapper comments;
    private final PageCache cache;
    private final VideoBloom bloom;
    private final Toggles toggles;
    private final StringRedisTemplate redis;
    private final CounterService counter;
    private final SummaryClient summary;

    public CommentQueryService(CommentMapper comments, PageCache cache, VideoBloom bloom, Toggles toggles,
                               StringRedisTemplate redis, CounterService counter, SummaryClient summary) {
        this.comments = comments;
        this.cache = cache;
        this.bloom = bloom;
        this.toggles = toggles;
        this.redis = redis;
        this.counter = counter;
        this.summary = summary;
    }

    public static String latestKey(long videoId) {
        return "cm:latest:" + videoId;
    }

    public static String hotKey(long videoId) {
        return "cm:hot:" + videoId;
    }

    /** 最新排序，游标分页。只有默认大小的首页走缓存。 */
    public Page latest(long videoId, String cursor, int size) {
        if (blockedByBloom(videoId)) return empty();
        Page p;
        if (cursor == null && size == DEFAULT_SIZE) {
            p = cache.get(latestKey(videoId), () -> latestPage(comments.latestFirst(videoId, size), size));
            p = overlayLikes(p);
            if (!p.items().isEmpty()) p = p.withSummary(summaryFor(videoId, p));
            return p;
        }
        List<Comment> rows;
        if (cursor == null) {
            rows = comments.latestFirst(videoId, size);
        } else {
            Cursor.Latest c = Cursor.parseLatest(cursor);
            rows = comments.latestAfter(videoId, LocalDateTime.ofInstant(Instant.ofEpochMilli(c.createdAtMillis()), ZoneOffset.UTC), c.id(), size);
        }
        return overlayLikes(latestPage(rows, size));
    }

    /** 朴素版对照：LIMIT offset 分页，不走缓存。 */
    public Page latestByOffset(long videoId, int page, int size) {
        long offset = (long) Math.max(page - 1, 0) * size;
        List<Comment> rows = comments.latestOffset(videoId, offset, size);
        return new Page(rows.stream().map(CommentView::of).toList(), null, null);
    }

    /** 最热排序。DB 模式实时 ORDER BY like_count；ZSET 模式读定时算好的榜单。 */
    public Page hot(long videoId, String cursor, int size) {
        if (blockedByBloom(videoId)) return empty();
        long offset = cursor == null ? 0 : Cursor.parseHot(cursor);
        if (toggles.hotMode == Toggles.HotMode.ZSET) {
            Page z = hotFromZset(videoId, offset, size);
            if (z != null) return overlayLikes(z);
        }
        if (offset == 0 && size == DEFAULT_SIZE) {
            return overlayLikes(cache.get(hotKey(videoId), () -> hotPage(comments.hotOffset(videoId, 0, size), 0, size)));
        }
        return overlayLikes(hotPage(comments.hotOffset(videoId, offset, size), offset, size));
    }

    private Page hotFromZset(long videoId, long offset, int size) {
        String key = HotRankJob.key(videoId);
        Set<String> ids = redis.opsForZSet().reverseRange(key, offset, offset + size - 1);
        if (ids == null || ids.isEmpty()) {
            // 不在榜单里的视频（评论少），或者翻到了榜单之外：回退到库查询
            return null;
        }
        List<Long> idList = ids.stream().map(Long::parseLong).toList();
        Map<Long, Comment> byId = new HashMap<>();
        for (Comment c : comments.byIds(idList)) byId.put(c.getId(), c);
        List<CommentView> items = new ArrayList<>(idList.size());
        for (Long id : idList) {
            Comment c = byId.get(id);
            if (c != null && c.getStatus() != 3) items.add(CommentView.of(c));
        }
        String next = ids.size() < size ? null : Cursor.hot(offset + size);
        return new Page(items, next, null);
    }

    private static Page latestPage(List<Comment> rows, int size) {
        List<CommentView> items = rows.stream().map(CommentView::of).toList();
        String next = null;
        if (items.size() == size) {
            CommentView last = items.get(items.size() - 1);
            next = Cursor.latest(last.createdAt(), last.id());
        }
        return new Page(items, next, null);
    }

    private static Page hotPage(List<Comment> rows, long offset, int size) {
        List<CommentView> items = rows.stream().map(CommentView::of).toList();
        return new Page(items, items.size() == size ? Cursor.hot(offset + size) : null, null);
    }

    /** REDIS 点赞模式下，库里（以及缓存里）的 like_count 会落后，用 Redis 里的实时值覆盖。 */
    private Page overlayLikes(Page p) {
        if (toggles.likeMode != Toggles.LikeMode.REDIS || p.items().isEmpty()) return p;
        List<String> keys = p.items().stream().map(c -> LikeService.key(c.id())).toList();
        List<String> vals = redis.opsForValue().multiGet(keys);
        if (vals == null) return p;
        List<CommentView> out = new ArrayList<>(p.items().size());
        for (int i = 0; i < p.items().size(); i++) {
            String v = vals.get(i);
            out.add(v == null ? p.items().get(i) : p.items().get(i).withLikeCount(Long.parseLong(v)));
        }
        return new Page(out, p.nextCursor(), p.summary());
    }

    private String summaryFor(long videoId, Page p) {
        long n;
        try {
            n = counter.commentCount(videoId);
        } catch (NotFoundException e) {
            return null;
        }
        return summary.summaryOrNull(videoId, n, p.items().stream().map(CommentView::content).toList());
    }

    private boolean blockedByBloom(long videoId) {
        return toggles.penetration == Toggles.Penetration.BLOOM && !bloom.mightExist(videoId);
    }

    private static Page empty() {
        return new Page(List.of(), null, null);
    }
}
