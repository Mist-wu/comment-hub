package io.commenthub.ai.grpc;

import io.commenthub.common.trace.GrpcTraceInterceptors;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.protobuf.services.ProtoReflectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 直接用 grpc-java 起服务，不依赖第三方 starter。请求在虚拟线程上执行。 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);
    private final AiGatewayGrpcService service;
    private final int port;
    private Server server;
    private ExecutorService grpcExecutor;
    /** 优雅停止和线程池各自最多等这么久。超时后 shutdownNow，不等待未响应的调用。 */
    Duration stopWait = Duration.ofSeconds(5);

    public GrpcServerLifecycle(AiGatewayGrpcService service, @Value("${hub.grpc.port:9090}") int port) {
        this.service = service;
        this.port = port;
    }

    @Override
    public void start() {
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        this.grpcExecutor = exec;
        try {
            server = ServerBuilder.forPort(port)
                    .executor(exec)
                    .addService(ServerInterceptors.intercept(service, GrpcTraceInterceptors.server()))
                    .addService(ProtoReflectionService.newInstance())
                    .build()
                    .start();
            log.info("gRPC server started on {}", server.getPort());
        } catch (IOException e) {
            exec.shutdownNow();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void stop() {
        Server s = server;
        if (s != null) {
            s.shutdown();
            try {
                if (!s.awaitTermination(stopWait.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("gRPC graceful stop exceeded {}ms", stopWait.toMillis());
                    s.shutdownNow();
                    if (!s.awaitTermination(stopWait.toMillis(), TimeUnit.MILLISECONDS)) {
                        log.warn("gRPC still running after shutdownNow");
                    }
                }
            } catch (InterruptedException e) {
                s.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        ExecutorService exec = grpcExecutor;
        if (exec != null) {
            exec.shutdownNow();
            try {
                if (!exec.awaitTermination(stopWait.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("gRPC executor still has tasks after {}ms", stopWait.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    int boundPort() {
        return server == null ? -1 : server.getPort();
    }

    boolean executorStopped() {
        return grpcExecutor != null && grpcExecutor.isShutdown();
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}
