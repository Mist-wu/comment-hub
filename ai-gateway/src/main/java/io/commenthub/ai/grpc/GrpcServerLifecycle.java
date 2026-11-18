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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 直接用 grpc-java 起服务，不依赖第三方 starter。请求在虚拟线程上执行。 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);
    private final AiGatewayGrpcService service;
    private final int port;
    private Server server;

    public GrpcServerLifecycle(AiGatewayGrpcService service, @Value("${hub.grpc.port:9090}") int port) {
        this.service = service;
        this.port = port;
    }

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(port)
                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                    .addService(ServerInterceptors.intercept(service, GrpcTraceInterceptors.server()))
                    .addService(ProtoReflectionService.newInstance())
                    .build()
                    .start();
            log.info("gRPC server started on {}", port);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}
