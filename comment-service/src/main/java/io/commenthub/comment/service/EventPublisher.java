package io.commenthub.comment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.commenthub.comment.mapper.OutboxMapper;
import io.commenthub.common.trace.Trace;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 发 Kafka 事件，trace ID 放进消息头。
 * 发送失败（broker 不可用、超时）时把事件写进 event_outbox，由定时任务重发，避免“库写成功、事件丢了”。
 * 仍然存在的窗口：库提交后、发送回调前进程崩溃。点赞有对账（LikeReconciler），CommentCreated 目前没有，见 docs/design.md 第 4 节。
 */
@Component
public class EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper om;
    private final OutboxMapper outbox;

    public EventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper om, OutboxMapper outbox) {
        this.kafka = kafka;
        this.om = om;
        this.outbox = outbox;
    }

    public void publish(String topic, String key, Object event) {
        String payload;
        try {
            payload = om.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String traceId = Trace.currentOrNew();
        send(topic, key, payload, traceId).whenComplete((r, ex) -> {
            if (ex != null) {
                log.warn("kafka send failed, write outbox topic={} key={} err={}", topic, key, ex.toString());
                outbox.insert(topic, key, payload, traceId);
            }
        });
    }

    private CompletableFuture<?> send(String topic, String key, String payload, String traceId) {
        ProducerRecord<String, String> rec = new ProducerRecord<>(topic, key, payload);
        rec.headers().add(Trace.KAFKA_HEADER, traceId.getBytes(StandardCharsets.UTF_8));
        try {
            return kafka.send(rec);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void drainOutbox() {
        for (Map<String, Object> row : outbox.oldest(500)) {
            long id = ((Number) row.get("id")).longValue();
            try {
                send((String) row.get("topic"), (String) row.get("msgKey"), (String) row.get("payload"),
                        Objects.requireNonNullElseGet((String) row.get("traceId"), Trace::newId)).get();
                outbox.delete(id);
            } catch (Exception e) {
                log.warn("outbox resend failed id={} err={}", id, e.toString());
                return; // broker 还没恢复，下一轮再试
            }
        }
    }
}
