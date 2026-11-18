package io.commenthub.consumer;

import io.commenthub.ai.v1.AiGatewayGrpc;
import io.commenthub.common.trace.GrpcTraceInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {
    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /**
     * 失败处理：间隔 1 秒重试 3 次，仍失败就投到 {原 topic}-dlt，分区号保持不变。
     * 死信消息头里自带原 topic / 分区 / offset / 异常信息，方便排查和重放。
     */
    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer dlt = new DeadLetterPublishingRecoverer(template,
                (rec, ex) -> new TopicPartition(rec.topic() + "-dlt", rec.partition()));
        DefaultErrorHandler h = new DefaultErrorHandler(dlt, new FixedBackOff(1000L, 3L));
        h.setRetryListeners((rec, ex, attempt) ->
                log.warn("retry topic={} offset={} attempt={} err={}", rec.topic(), rec.offset(), attempt, ex.toString()));
        return h;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> cf, DefaultErrorHandler eh) {
        ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure((ConcurrentKafkaListenerContainerFactory) f, cf);
        f.setCommonErrorHandler(eh);
        f.setConcurrency(3);
        return f;
    }

    /** 点赞落库用批量监听：一次拉一批，按评论合并成一条 UPDATE。 */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> batchFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer, ConsumerFactory<Object, Object> cf, DefaultErrorHandler eh) {
        ConcurrentKafkaListenerContainerFactory<String, String> f = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure((ConcurrentKafkaListenerContainerFactory) f, cf);
        f.setBatchListener(true);
        f.setCommonErrorHandler(eh);
        f.setConcurrency(3);
        return f;
    }

    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel aiGatewayChannel(@Value("${hub.ai-gateway.target:localhost:9090}") String target) {
        return ManagedChannelBuilder.forTarget(target).usePlaintext().intercept(GrpcTraceInterceptors.client()).build();
    }

    @Bean
    AiGatewayGrpc.AiGatewayBlockingStub aiGatewayStub(ManagedChannel ch) {
        return AiGatewayGrpc.newBlockingStub(ch);
    }
}
