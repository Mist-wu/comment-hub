package io.commenthub.comment.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.commenthub.comment.config.Toggles;
import io.commenthub.comment.domain.CommentView;
import io.commenthub.comment.domain.Page;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PageCacheReadTest {
    Toggles toggles = new Toggles();
    ObjectMapper om = new ObjectMapper();
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    RedisMutex mutex = mock(RedisMutex.class);
    PageCache cache;
    Page full = new Page(List.of(new CommentView(7, 1, 2, "hi", 3, 1, 4)), "next", null);
    Page other = new Page(List.of(new CommentView(8, 1, 2, "db", 1, 1, 4)), "db", null);
    Page empty = new Page(List.of(), null, null);

    @BeforeEach
    void setUp() {
        toggles.localCache = false;
        toggles.cacheEnabled = true;
        toggles.cacheTtlSeconds = 30;
        toggles.ttlJitterSeconds = 0;
        when(redis.opsForValue()).thenReturn(values);
        when(mutex.tryLock(anyString(), any())).thenReturn("tok");
        doNothing().when(mutex).unlock(anyString(), anyString());
        cache = new PageCache(redis, om, toggles, new HotKeyDetector(toggles), mutex, new SimpleMeterRegistry());
    }

    @Test
    void noneAndMutexReadFreshLogicalEmptyAndNullMark() throws Exception {
        for (Toggles.Breakdown mode : List.of(Toggles.Breakdown.NONE, Toggles.Breakdown.MUTEX)) {
            toggles.breakdown = mode;
            assertRead(mode.name() + " fresh", wrap(full, future()), full, 0);
            assertRead(mode.name() + " empty", wrap(empty, future()), empty, 0);
            assertRead(mode.name() + " null", PageCache.NULL_MARK, empty, 0);
        }
    }

    @Test
    void noneAndMutexRebuildExpiredLogicalIncludingAfterLock() throws Exception {
        for (Toggles.Breakdown mode : List.of(Toggles.Breakdown.NONE, Toggles.Breakdown.MUTEX)) {
            toggles.breakdown = mode;
            AtomicInteger loads = new AtomicInteger();
            when(values.get("k")).thenReturn(wrap(full, past()), wrap(full, past()));
            Page p = cache.get("k", () -> {
                loads.incrementAndGet();
                return other;
            });
            assertThat(p.items()).extracting(CommentView::id).containsExactly(8L);
            assertThat(p.nextCursor()).isEqualTo("db");
            assertThat(loads).hasValue(1);
        }
    }

    @Test
    void mutexSecondReadKeepsUnexpiredLogicalAndPlain() throws Exception {
        toggles.breakdown = Toggles.Breakdown.MUTEX;
        when(values.get("k")).thenReturn(null, wrap(full, future()));
        AtomicInteger loads = new AtomicInteger();
        Page p = cache.get("k", () -> {
            loads.incrementAndGet();
            return other;
        });
        assertThat(p.items()).extracting(CommentView::id).containsExactly(7L);
        assertThat(p.nextCursor()).isEqualTo("next");
        assertThat(loads).hasValue(0);

        when(values.get("k2")).thenReturn(null, om.writeValueAsString(full));
        Page plain = cache.get("k2", () -> {
            loads.incrementAndGet();
            return other;
        });
        assertThat(plain.items()).extracting(CommentView::id).containsExactly(7L);
        assertThat(loads).hasValue(0);
    }

    @Test
    void logicalReadsPlainNullAndEmptyAndServesExpired() throws Exception {
        toggles.breakdown = Toggles.Breakdown.LOGICAL;
        AtomicInteger loads = new AtomicInteger();
        when(values.get("plain")).thenReturn(om.writeValueAsString(full));
        Page plain = cache.get("plain", () -> {
            loads.incrementAndGet();
            return other;
        });
        assertThat(plain.nextCursor()).isEqualTo("next");

        when(values.get("nil")).thenReturn(PageCache.NULL_MARK);
        Page nil = cache.get("nil", () -> {
            loads.incrementAndGet();
            return other;
        });
        assertThat(nil.items()).isEmpty();
        assertThat(nil.nextCursor()).isNull();

        when(values.get("empty")).thenReturn(wrap(empty, future()));
        Page blank = cache.get("empty", () -> {
            loads.incrementAndGet();
            return other;
        });
        assertThat(blank.items()).isEmpty();
        assertThat(loads).hasValue(0);

        when(values.get("stale")).thenReturn(wrap(full, past()));
        Page stale = cache.get("stale", () -> {
            loads.incrementAndGet();
            return other;
        });
        assertThat(stale.items()).extracting(CommentView::id).containsExactly(7L);
        assertThat(stale.nextCursor()).isEqualTo("next");
    }

    @Test
    void logicalLockThenSecondReadAcceptsPlainNullAndExpired() throws Exception {
        toggles.breakdown = Toggles.Breakdown.LOGICAL;
        when(mutex.tryLock(anyString(), any())).thenReturn("tok");
        AtomicInteger loads = new AtomicInteger();
        when(values.get("k")).thenReturn(null, om.writeValueAsString(full));
        Page plain = cache.get("k", () -> {
            loads.incrementAndGet();
            return other;
        });
        assertThat(plain.items()).extracting(CommentView::id).containsExactly(7L);

        when(values.get("n")).thenReturn(null, PageCache.NULL_MARK);
        Page nil = cache.get("n", () -> {
            loads.incrementAndGet();
            return other;
        });
        assertThat(nil.items()).isEmpty();

        when(values.get("e")).thenReturn(null, wrap(full, past()));
        Page expired = cache.get("e", () -> {
            loads.incrementAndGet();
            return other;
        });
        assertThat(expired.items()).extracting(CommentView::id).containsExactly(7L);
        assertThat(loads).hasValue(0);
    }

    @Test
    void logicalWaitThenSecondReadAcceptsPlainPage() throws Exception {
        toggles.breakdown = Toggles.Breakdown.LOGICAL;
        when(mutex.tryLock(anyString(), any())).thenReturn(null);
        when(values.get("k")).thenReturn(null, om.writeValueAsString(empty));
        AtomicInteger loads = new AtomicInteger();
        Page p = cache.get("k", () -> {
            loads.incrementAndGet();
            return other;
        });
        assertThat(p.items()).isEmpty();
        assertThat(p.nextCursor()).isNull();
        assertThat(loads).hasValue(0);
    }

    @Test
    void shutdownReturnsWhileSlowRebuildIsStillRunning() throws Exception {
        toggles.breakdown = Toggles.Breakdown.LOGICAL;
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean();
        when(values.get("stale")).thenReturn(wrap(full, past()));
        Page stale = cache.get("stale", () -> {
            entered.countDown();
            while (!release.get()) Thread.onSpinWait();
            return other;
        });
        assertThat(stale.items()).extracting(CommentView::id).containsExactly(7L);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        cache.shutdownWait = Duration.ofMillis(200);
        long t0 = System.nanoTime();
        cache.shutdown();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(release).isFalse();
        assertThat(ms).isBetween(150L, 1500L);
        release.set(true);
    }

    private void assertRead(String label, String raw, Page expected, int loads) {
        when(values.get("k")).thenReturn(raw);
        AtomicInteger n = new AtomicInteger();
        Page p = cache.get("k", () -> {
            n.incrementAndGet();
            return other;
        });
        assertThat(p.items()).as(label).containsExactlyElementsOf(expected.items());
        assertThat(p.nextCursor()).as(label).isEqualTo(expected.nextCursor());
        assertThat(n).as(label).hasValue(loads);
    }

    private String wrap(Page page, long expireAt) throws Exception {
        ObjectNode n = om.createObjectNode();
        n.put("e", expireAt);
        n.set("d", om.valueToTree(page));
        return n.toString();
    }

    private static long future() {
        return System.currentTimeMillis() + 60_000;
    }

    private static long past() {
        return System.currentTimeMillis() - 1_000;
    }
}
