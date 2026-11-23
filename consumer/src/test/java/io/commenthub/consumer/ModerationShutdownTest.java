package io.commenthub.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commenthub.ai.v1.AiGatewayGrpc;
import io.commenthub.ai.v1.Label;
import io.commenthub.ai.v1.ModerateReply;
import io.commenthub.common.event.CommentCreated;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModerationShutdownTest {
    @SuppressWarnings("unchecked")
    private final AiGatewayGrpc.AiGatewayBlockingStub ai = mock(AiGatewayGrpc.AiGatewayBlockingStub.class);

    @Test
    void idleShutdownIsFast() {
        CommentCreatedListeners listeners = listener(1);
        listeners.shutdownWait = Duration.ofMillis(200);
        long t0 = System.nanoTime();
        listeners.shutdown();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(500);
    }

    @Test
    void queuedTaskIsInterruptedAndSlowTaskDoesNotHoldShutdown() throws Exception {
        CommentCreatedListeners listeners = listener(1);
        CountDownLatch holding = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        when(ai.moderate(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                holding.countDown();
                while (!release.get()) Thread.onSpinWait();
            }
            return ModerateReply.newBuilder().setLabel(Label.PASS).build();
        });
        ObjectMapper om = new ObjectMapper();
        Thread worker = new Thread(() -> {
            try {
                listeners.moderateBatch(List.of(rec(om, "a", 1), rec(om, "b", 2)));
            } catch (Exception ignored) {
            }
        });
        worker.start();
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
        listeners.shutdownWait = Duration.ofMillis(300);
        long t0 = System.nanoTime();
        listeners.shutdown();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(release).isFalse();
        assertThat(ms).isBetween(250L, 1500L);
        assertThat(calls).hasValue(1);
        release.set(true);
        worker.join(5_000);
        verify(ai, times(1)).moderate(any());
    }

    private CommentCreatedListeners listener(int parallelism) {
        Events events = mock(Events.class);
        Dedup dedup = mock(Dedup.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ObjectMapper om = new ObjectMapper();
        when(events.parse(any(), eq(CommentCreated.class))).thenAnswer(inv -> {
            ConsumerRecord<String, String> rec = inv.getArgument(0);
            return om.readValue(rec.value(), CommentCreated.class);
        });
        when(tx.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        when(dedup.firstTimeBatch(anyString(), any())).thenAnswer(inv ->
                new LinkedHashSet<>((Collection<String>) inv.getArgument(1)));
        when(jdbc.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), eq(Long.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.batchUpdate(anyString(), anyList())).thenReturn(new int[]{1});
        when(ai.withDeadlineAfter(anyLong(), any())).thenReturn(ai);
        when(ai.moderate(any())).thenReturn(ModerateReply.newBuilder().setLabel(Label.PASS).build());
        return new CommentCreatedListeners(events, dedup, jdbc, tx, redis, ai, new ConsumerToggles(), parallelism);
    }

    private static ConsumerRecord<String, String> rec(ObjectMapper om, String id, long comment) throws Exception {
        CommentCreated e = new CommentCreated(id, comment, 1, 1, "t", 1);
        return new ConsumerRecord<>("comment-created", 0, 0, "1", om.writeValueAsString(e));
    }
}
