package io.commenthub.comment.service;

import io.commenthub.comment.cache.PageCache;
import io.commenthub.comment.config.Toggles;
import io.commenthub.comment.domain.Comment;
import io.commenthub.comment.domain.CommentView;
import io.commenthub.comment.domain.Page;
import io.commenthub.comment.mapper.CommentMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
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
        if (cursor == null && size == DEFAULT_SIZE) {
            Page p = cache.get(latestKey(videoId), () -> latestPage(comments.latestOffset(videoId, 0, size), size));
            p = overlayLikes(p);
            if (p.items().isEmpty()) return p;
            return p.withSummary(toggles.summaryEnabled ? summaryFor(videoId, p) : null);
        }
        List<Comment> rows;
        if (cursor == null) {
            rows = comments.latestOffset(videoId, 0, size);
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
        Cursor.HotCursor c = cursor == null ? null : Cursor.parseHotCursor(cursor);
        if (toggles.hotMode == Toggles.HotMode.ZSET && (c == null || c.kind() != Cursor.HotKind.DB)) {
            Page z = hotFromZset(videoId, c, size);
            if (z != null) return overlayLikes(z);
        }
        // 榜单不可用、已换代或切回 DB 时，按游标里的点赞数和 id 续页。
        if (c != null && c.kind() != Cursor.HotKind.LEGACY) {
            return overlayLikes(hotPage(comments.hotAfter(videoId, c.likeCount(), c.id(), size), size));
        }
        long offset = c == null ? 0 : c.offset();
        if (offset == 0 && size == DEFAULT_SIZE) {
            return overlayLikes(cache.get(hotKey(videoId), () -> hotPage(comments.hotOffset(videoId, 0, size), size)));
        }
        return overlayLikes(hotPage(comments.hotOffset(videoId, offset, size), size));
    }

    private Page hotFromZset(long videoId, Cursor.HotCursor cursor, int size) {
        long offset = cursor == null ? 0 : cursor.offset();
        String key = HotRankJob.key(videoId);
        Long card = redis.opsForZSet().zCard(key);
        if (card == null || card == 0 || offset >= card) return null;
        String meta = redis.opsForValue().get(HotRankJob.metaKey(videoId));
        Long generation = HotRankJob.generation(meta);
        if (cursor != null && cursor.kind() == Cursor.HotKind.ZSET
                && generation != null && cursor.generation() != generation) {
            return null;
        }
        Set<ZSetOperations.TypedTuple<String>> range = redis.opsForZSet()
                .reverseRangeWithScores(key, offset, offset + size - 1L);
        if (range == null || range.isEmpty()) return null;
        boolean legacy = range.stream().anyMatch(t -> !HotRankJob.isPadded(t.getValue()));
        boolean capped = HotRankJob.capped(meta, card);
        if (legacy) return pageLegacy(videoId, offset, size, capped);
        return pageFromRange(range, offset, size, card, generation, capped);
    }

    /** 旧成员没补齐位数，整榜取出来按点赞数、id 排，页内顺序才和 SQL 一致。 */
    private Page pageLegacy(long videoId, long offset, int size, boolean capped) {
        Set<String> all = redis.opsForZSet().reverseRange(HotRankJob.key(videoId), 0, -1);
        if (all == null || all.isEmpty()) return null;
        List<Long> ids = all.stream().map(Long::parseLong).toList();
        Map<Long, Comment> byId = loadComments(ids);
        List<Comment> sorted = byId.values().stream()
                .sorted(Comparator.comparingInt((Comment c) -> c.getLikeCount() == null ? 0 : c.getLikeCount()).reversed()
                        .thenComparing(Comparator.comparingLong(Comment::getId).reversed()))
                .toList();
        if (offset >= sorted.size()) return null;
        int from = (int) offset;
        int to = (int) Math.min(sorted.size(), offset + size);
        List<Comment> slice = sorted.subList(from, to);
        String next = null;
        if (offset + slice.size() < sorted.size()) {
            next = Cursor.hot(offset + slice.size());
        } else if (capped && !slice.isEmpty()) {
            Comment last = slice.get(slice.size() - 1);
            next = Cursor.hotDb(last.getLikeCount(), last.getId());
        }
        return new Page(slice.stream().filter(c -> c.getStatus() != 3).map(CommentView::of).toList(), next, null);
    }

    private Page pageFromRange(Set<ZSetOperations.TypedTuple<String>> range, long offset, int size, long card,
                               Long generation, boolean capped) {
        List<Long> ids = new ArrayList<>(range.size());
        ZSetOperations.TypedTuple<String> last = null;
        for (ZSetOperations.TypedTuple<String> t : range) {
            ids.add(Long.parseLong(t.getValue()));
            last = t;
        }
        Map<Long, Comment> byId = loadComments(ids);
        List<CommentView> items = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Comment c = byId.get(id);
            if (c != null && c.getStatus() != 3) items.add(CommentView.of(c));
        }
        String next = null;
        if (last != null && last.getValue() != null) {
            long like = last.getScore() == null ? 0 : last.getScore().longValue();
            long id = Long.parseLong(last.getValue());
            boolean moreInZset = offset + range.size() < card;
            if (moreInZset) {
                long nextOffset = offset + range.size();
                next = generation == null ? Cursor.hot(nextOffset) : Cursor.hotZset(nextOffset, generation, like, id);
            } else if (capped) {
                next = Cursor.hotDb(like, id);
            }
        }
        return new Page(items, next, null);
    }

    private Map<Long, Comment> loadComments(List<Long> ids) {
        Map<Long, Comment> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Comment c : comments.byIds(ids)) byId.put(c.getId(), c);
        }
        return byId;
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

    private static Page hotPage(List<Comment> rows, int size) {
        List<CommentView> items = rows.stream().map(CommentView::of).toList();
        String next = null;
        if (items.size() == size) {
            CommentView last = items.get(items.size() - 1);
            next = Cursor.hotDb(last.likeCount(), last.id());
        }
        return new Page(items, next, null);
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
