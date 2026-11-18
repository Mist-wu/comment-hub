package io.commenthub.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commenthub.common.trace.Trace;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class Events {
    private final ObjectMapper om;
    private final MeterRegistry meters;

    public Events(ObjectMapper om, MeterRegistry meters) {
        this.om = om;
        this.meters = meters;
    }

    public <T> T parse(ConsumerRecord<String, String> rec, Class<T> type) {
        try {
            return om.readValue(rec.value(), type);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad event at " + rec.topic() + "@" + rec.offset(), e);
        }
    }

    public static String traceId(ConsumerRecord<String, String> rec) {
        Header h = rec.headers().lastHeader(Trace.KAFKA_HEADER);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    /** 端到端延迟 = 处理完成时刻 - 事件产生时刻（生产者写入的时间戳）。 */
    public void recordE2e(String consumer, long eventMillis) {
        Timer.builder("hub.e2e.latency").tag("consumer", consumer).publishPercentileHistogram()
                .register(meters).record(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - eventMillis)));
    }
}
