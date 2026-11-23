package io.commenthub.comment.service;

import io.commenthub.comment.cache.PageCache;
import io.commenthub.comment.cache.RedisMutex;
import io.commenthub.comment.config.Toggles;
import io.commenthub.comment.domain.Comment;
import io.commenthub.comment.domain.CommentView;
import io.commenthub.comment.domain.Page;
import io.commenthub.comment.mapper.CommentMapper;
import io.commenthub.comment.mapper.VideoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HotRankPagingTest {
    Toggles toggles = new Toggles();
    CommentMapper comments = mock(CommentMapper.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ZSetOperations<String, String> zset = mock(ZSetOperations.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    CommentQueryService svc;

    @BeforeEach
    void setUp() {
        toggles.hotMode = Toggles.HotMode.ZSET;
        toggles.penetration = Toggles.Penetration.NONE;
        toggles.likeMode = Toggles.LikeMode.DB;
        when(redis.opsForZSet()).thenReturn(zset);
        when(redis.opsForValue()).thenReturn(values);
        svc = new CommentQueryService(comments, mock(PageCache.class), mock(VideoBloom.class), toggles,
                redis, mock(CounterService.class), mock(SummaryClient.class));
    }

    @Test
    void paddedMembersSortAsNumericIdDesc() {
        List<Long> ids = List.of(99L, 100L, 7L, 1000L);
        List<Long> ordered = ids.stream()
                .sorted(java.util.Comparator.comparing(HotRankJob::member).reversed())
                .toList();
        assertThat(ordered).containsExactly(1000L, 100L, 99L, 7L);
        assertThat(HotRankJob.parseMember(HotRankJob.member(99))).isEqualTo(99L);
        assertThat(HotRankJob.parseMember("99")).isEqualTo(99L);
    }

    @Test
    void rebuildWritesPaddedMembersAndMeta() {
        VideoMapper videos = mock(VideoMapper.class);
        RedisMutex mutex = mock(RedisMutex.class);
        when(mutex.tryLock(anyString(), any())).thenReturn("tok");
        when(videos.topByComments(anyInt())).thenReturn(List.of(1L));
        when(comments.topByLikes(1L, 1000)).thenReturn(List.of(comment(100, 5), comment(99, 5)));
        HotRankJob job = new HotRankJob(videos, comments, redis, mutex, 100, 1000);
        job.rebuild();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> cap = org.mockito.ArgumentCaptor.forClass(Set.class);
        verify(zset).add(eq("hot:z:1:tmp"), cap.capture());
        assertThat(cap.getValue()).allMatch(t -> HotRankJob.isPadded(t.getValue()));
        assertThat(cap.getValue()).extracting(ZSetOperations.TypedTuple::getValue)
                .contains(HotRankJob.member(99), HotRankJob.member(100));
        verify(values).set(eq("hot:z:1:meta"), org.mockito.ArgumentMatchers.matches("\\d+:0"), any(Duration.class));
    }

    @Test
    void legacyBoardOrdersSameScoreByNumericId() {
        when(zset.zCard("hot:z:1")).thenReturn(2L);
        when(values.get("hot:z:1:meta")).thenReturn(null);
        when(zset.reverseRangeWithScores("hot:z:1", 0, 19)).thenReturn(tuples("99", 5, "100", 5));
        when(zset.reverseRange("hot:z:1", 0, -1)).thenReturn(Set.of("99", "100"));
        when(comments.byIds(any())).thenReturn(List.of(comment(99, 5), comment(100, 5)));
        Page p = svc.hot(1, null, 20);
        assertThat(p.items()).extracting(CommentView::id).containsExactly(100L, 99L);
        assertThat(p.nextCursor()).isNull();
    }

    @Test
    void oldCursorAtTheEndOfAFullBoardDoesNotStopEarly() {
        when(zset.zCard("hot:z:1")).thenReturn(1000L);
        when(values.get("hot:z:1:meta")).thenReturn(null);
        LinkedHashSet<ZSetOperations.TypedTuple<String>> range = new LinkedHashSet<>();
        List<Comment> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long id = 990 + i;
            range.add(new DefaultTypedTuple<>(HotRankJob.member(id), 4d));
            rows.add(comment(id, 4));
        }
        when(zset.reverseRangeWithScores("hot:z:1", 990, 1019)).thenReturn(range);
        when(comments.byIds(any())).thenReturn(rows);
        Page p = svc.hot(1, Cursor.hot(990), 30);
        assertThat(p.items()).hasSize(10);
        assertThat(p.nextCursor()).isNotNull();
        Cursor.HotCursor next = Cursor.parseHotCursor(p.nextCursor());
        assertThat(next.kind()).isEqualTo(Cursor.HotKind.DB);
        assertThat(next.likeCount()).isEqualTo(4);
        assertThat(next.id()).isEqualTo(999);
    }

    @Test
    void zsetCursorFallsBackToKeysetWhenGenerationChanges() {
        when(zset.zCard("hot:z:1")).thenReturn(100L);
        when(values.get("hot:z:1:meta")).thenReturn("2:0");
        when(comments.hotAfter(1L, 5L, 100L, 20)).thenReturn(List.of(comment(98, 4)));
        Page p = svc.hot(1, Cursor.hotZset(20, 1, 5, 100), 20);
        assertThat(p.items()).extracting(CommentView::id).containsExactly(98L);
        verify(comments).hotAfter(1L, 5L, 100L, 20);
    }

    @Test
    void size20And30StayInsideAPinnedBoard() {
        when(zset.zCard("hot:z:1")).thenReturn(100L);
        when(values.get("hot:z:1:meta")).thenReturn("7:0");
        stubRange(0, 20);
        Page a = svc.hot(1, null, 20);
        assertThat(a.items()).hasSize(20);
        assertThat(Cursor.parseHotCursor(a.nextCursor()).kind()).isEqualTo(Cursor.HotKind.ZSET);
        stubRange(20, 30);
        Page b = svc.hot(1, a.nextCursor(), 30);
        assertThat(b.items()).extracting(CommentView::id).containsExactly(79L, 78L, 77L, 76L, 75L, 74L, 73L, 72L, 71L, 70L,
                69L, 68L, 67L, 66L, 65L, 64L, 63L, 62L, 61L, 60L, 59L, 58L, 57L, 56L, 55L, 54L, 53L, 52L, 51L, 50L);
        assertThat(a.items().get(a.items().size() - 1).id()).isEqualTo(80L);
        assertThat(b.items().get(0).id()).isNotEqualTo(80L);
    }

    private void stubRange(long offset, int size) {
        LinkedHashSet<ZSetOperations.TypedTuple<String>> range = new LinkedHashSet<>();
        List<Comment> rows = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            long id = 99 - offset - i;
            range.add(new DefaultTypedTuple<>(HotRankJob.member(id), 3d));
            rows.add(comment(id, 3));
        }
        when(zset.reverseRangeWithScores("hot:z:1", offset, offset + size - 1L)).thenReturn(range);
        when(comments.byIds(any())).thenReturn(rows);
    }

    private static Set<ZSetOperations.TypedTuple<String>> tuples(String a, double as, String b, double bs) {
        LinkedHashSet<ZSetOperations.TypedTuple<String>> set = new LinkedHashSet<>();
        set.add(new DefaultTypedTuple<>(a, as));
        set.add(new DefaultTypedTuple<>(b, bs));
        return set;
    }

    private static Comment comment(long id, int likes) {
        Comment c = new Comment();
        c.setId(id);
        c.setVideoId(1L);
        c.setUserId(1L);
        c.setContent("c" + id);
        c.setLikeCount(likes);
        c.setStatus(1);
        c.setCreatedAt(LocalDateTime.of(2024, 1, 1, 0, 0));
        return c;
    }
}
