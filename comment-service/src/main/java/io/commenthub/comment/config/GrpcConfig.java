package io.commenthub.comment.config;

import io.commenthub.ai.v1.AiGatewayGrpc;
import io.commenthub.common.trace.GrpcTraceInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcConfig {
    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel aiGatewayChannel(@Value("${hub.ai-gateway.target:localhost:9090}") String target) {
        return ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .intercept(GrpcTraceInterceptors.client())
                .build();
    }

    @Bean
    AiGatewayGrpc.AiGatewayBlockingStub aiGatewayStub(ManagedChannel ch) {
        return AiGatewayGrpc.newBlockingStub(ch);
    }
}
