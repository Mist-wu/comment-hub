package io.commenthub.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commenthub.ai.v1.AiGatewayGrpc;
import io.commenthub.ai.v1.Label;
import io.commenthub.ai.v1.ModerateReply;
import io.commenthub.ai.v1.ModerateRequest;
import io.commenthub.common.event.CommentCreated;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModerationBatchTest {
    ObjectMapper om = new ObjectMapper();
    Events events = mock(Events.class);
    Dedup dedup = mock(Dedup.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    AiGatewayGrpc.AiGatewayBlockingStub ai = mock(AiGatewayGrpc.AiGatewayBlockingStub.class);
    ConsumerToggles toggles = new ConsumerToggles();
    CommentCreatedListeners listeners;

    @BeforeEach
    void setUp() throws Exception {
        when(events.parse(any(), eq(CommentCreated.class))).thenAnswer(inv -> {
            ConsumerRecord<String, String> rec = inv.getArgument(0);
            return om.readValue(rec.value(), CommentCreated.class);
        });
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        when(dedup.firstTimeBatch(anyString(), any())).thenAnswer(inv ->
                new LinkedHashSet<>((Collection<String>) inv.getArgument(1)));
        when(jdbc.queryForList(contains("consumed_event"), eq(String.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(contains("status = 3"), eq(Long.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.batchUpdate(anyString(), anyList())).thenReturn(new int[]{1});
        when(redis.delete(anyList())).thenReturn(1L);
        when(redis.execute(any(), anyList(), any())).thenReturn(1L);
        when(ai.withDeadlineAfter(anyLong(), any())).thenReturn(ai);
        when(ai.moderate(any())).thenReturn(ModerateReply.newBuilder().setLabel(Label.PASS).build());
        listeners = new CommentCreatedListeners(events, dedup, jdbc, tx, redis, ai, toggles, 4);
    }

    @AfterEach
    void tearDown() {
        listeners.shutdownWait = java.time.Duration.ofMillis(200);
        listeners.shutdown();
    }

    @Test
    void duplicateInBatchCallsModelOncePerId() throws Exception {
        CommentCreated a = ev("a", 1);
        CommentCreated b = ev("b", 2);
        listeners.moderateBatch(List.of(rec(a), rec(a), rec(b)));
        verify(ai, times(2)).moderate(any());
        verify(jdbc, times(1)).batchUpdate(contains("UPDATE comment"), anyList());
    }

    @Test
    void interruptPersistsReturnedPrefixBeforeCommitIndex() throws Exception {
        CountDownLatch aReturned = new CountDownLatch(1);
        CountDownLatch bEntered = new CountDownLatch(1);
        when(ai.moderate(any())).thenAnswer(inv -> {
            ModerateRequest req = inv.getArgument(0);
            if (req.getCommentId() == 2) {
                bEntered.countDown();
                Thread.sleep(20_000);
            } else {
                aReturned.countDown();
            }
            return ModerateReply.newBuilder().setLabel(Label.PASS).build();
        });
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                listeners.moderateBatch(List.of(rec(ev("a", 1)), rec(ev("b", 2))));
            } catch (Throwable ex) {
                thrown.set(ex);
            }
        });
        t.start();
        assertThat(aReturned.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(bEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        t.interrupt();
        t.join(5_000);
        assertThat(t.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(BatchListenerFailedException.class);
        assertThat(((BatchListenerFailedException) thrown.get()).getIndex()).isEqualTo(1);
        verify(jdbc, atLeastOnce()).batchUpdate(contains("UPDATE comment"), anyList());
    }

    @Test
    void redisEvictFailureDoesNotUseCommitIndexAndRetryStillDeletes() throws Exception {
        when(ai.moderate(any())).thenReturn(ModerateReply.newBuilder().setLabel(Label.REJECT).build());
        when(jdbc.queryForList(contains("status = 3"), eq(Long.class), any(Object[].class))).thenReturn(List.of(9L));
        when(redis.delete(anyList())).thenThrow(new RedisConnectionFailureException("down")).thenReturn(1L);
        AtomicInteger lookups = new AtomicInteger();
        when(jdbc.queryForList(contains("consumed_event"), eq(String.class), any(Object[].class))).thenAnswer(inv ->
                lookups.getAndIncrement() == 0 ? List.of() : List.of("e1"));
        CommentCreated e = ev("e1", 10);
        assertThatThrownBy(() -> listeners.moderateBatch(List.of(rec(e))))
                .isInstanceOf(RedisConnectionFailureException.class);
        listeners.moderateBatch(List.of(rec(e)));
        verify(ai, times(1)).moderate(any());
        verify(redis, times(2)).delete(anyList());
    }

    @Test
    void databaseFailureIsNotAPrefixCommit() throws Exception {
        when(jdbc.batchUpdate(anyString(), anyList())).thenThrow(new RuntimeException("db down"));
        Throwable ex = org.assertj.core.api.Assertions.catchThrowable(() ->
                listeners.moderateBatch(List.of(rec(ev("a", 1)))));
        assertThat(ex).isNotInstanceOf(BatchListenerFailedException.class);
        assertThat(ex).hasMessageContaining("db down");
    }

    @Test
    void countRedisRunsAgainWhenTheFirstAttemptFailsAfterCommit() throws Exception {
        when(redis.execute(any(), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(1L);
        AtomicInteger n = new AtomicInteger();
        when(dedup.firstTimeBatch(eq("counter"), any())).thenAnswer(inv -> {
            if (n.getAndIncrement() == 0) return new LinkedHashSet<>((Collection<String>) inv.getArgument(1));
            return new LinkedHashSet<String>();
        });
        List<ConsumerRecord<String, String>> batch = List.of(rec(ev("a", 1)));
        assertThatThrownBy(() -> listeners.count(batch)).isInstanceOf(RedisConnectionFailureException.class);
        listeners.count(batch);
        verify(redis, times(2)).execute(any(), anyList(), any());
        assertThat(CommentCreatedListeners.INCR_ONCE.getScriptAsString()).contains("KEYS[2]").contains("incrby");
    }

    private CommentCreated ev(String id, long commentId) {
        return new CommentCreated(id, commentId, 9, 1, "text", 1L);
    }

    private ConsumerRecord<String, String> rec(CommentCreated e) throws Exception {
        return new ConsumerRecord<>("comment-created", 0, 0, "9", om.writeValueAsString(e));
    }
}
