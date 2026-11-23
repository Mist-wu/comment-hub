package io.commenthub.ai.grpc;

import io.commenthub.ai.service.ModerationService;
import io.commenthub.ai.service.SummaryService;
import io.commenthub.ai.v1.AiGatewayGrpc;
import io.commenthub.ai.v1.Label;
import io.commenthub.ai.v1.ModerateReply;
import io.commenthub.ai.v1.ModerateRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GrpcServerLifecycleTest {
    @Test
    void idleStopClosesTheExecutor() {
        GrpcServerLifecycle life = new GrpcServerLifecycle(service(new AtomicBoolean(true)), 0);
        life.stopWait = Duration.ofMillis(200);
        life.start();
        long t0 = System.nanoTime();
        life.stop();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(500);
        assertThat(life.executorStopped()).isTrue();
        assertThat(life.isRunning()).isFalse();
    }

    @Test
    void slowCallDoesNotHoldStop() throws Exception {
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        ModerationService mod = mock(ModerationService.class);
        when(mod.moderate(anyLong(), any(), any())).thenAnswer(inv -> {
            entered.countDown();
            while (!release.get()) Thread.onSpinWait();
            return ModerateReply.newBuilder().setLabel(Label.PASS).build();
        });
        GrpcServerLifecycle life = new GrpcServerLifecycle(
                new AiGatewayGrpcService(mod, mock(SummaryService.class)), 0);
        life.stopWait = Duration.ofMillis(200);
        life.start();
        ManagedChannel ch = ManagedChannelBuilder.forAddress("127.0.0.1", life.boundPort()).usePlaintext().build();
        Thread call = new Thread(() -> {
            try {
                AiGatewayGrpc.newBlockingStub(ch).moderate(ModerateRequest.newBuilder().setContent("x").build());
            } catch (Exception ignored) {
            }
        });
        call.setDaemon(true);
        call.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        life.stop();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(release).isFalse();
        assertThat(ms).isBetween(150L, 2500L);
        assertThat(life.executorStopped()).isTrue();
        release.set(true);
        ch.shutdownNow();
        call.join(2_000);
    }

    private static AiGatewayGrpcService service(AtomicBoolean release) {
        ModerationService mod = mock(ModerationService.class);
        when(mod.moderate(anyLong(), any(), any())).thenAnswer(inv -> {
            while (!release.get()) Thread.onSpinWait();
            return ModerateReply.newBuilder().setLabel(Label.PASS).build();
        });
        return new AiGatewayGrpcService(mod, mock(SummaryService.class));
    }
}
