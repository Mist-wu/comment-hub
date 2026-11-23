package io.commenthub.consumer;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 重放死信：从 {topic}-dlt 读出所有未重放的消息，原样（保留 key 和 trace-id 头，去掉 spring 加的异常头）
 * 发回原 topic，然后提交 offset。因为消费者按事件 ID 幂等，重放已经成功过的消息也不会出错。
 */
@Component
public class DltReplayer {
    private static final Logger log = LoggerFactory.getLogger(DltReplayer.class);
    private final KafkaTemplate<String, String> template;
    private final String bootstrap;

    public DltReplayer(KafkaTemplate<String, String> template, @Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.template = template;
        this.bootstrap = bootstrap;
    }

    public Map<String, Object> replay(String topic, int max) throws Exception {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-replayer");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        int n = 0;
        try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
            c.subscribe(List.of(DltNames.topic(topic)));
            int emptyPolls = 0;
            while (n < max && emptyPolls < 3) {
                ConsumerRecords<String, String> recs = c.poll(Duration.ofSeconds(1));
                if (recs.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (ConsumerRecord<String, String> r : recs) {
                    template.send(toReplay(topic, r)).get();
                    n++;
                }
                c.commitSync();
            }
        }
        log.info("replayed {} messages from {}", n, DltNames.topic(topic));
        return Map.of("topic", topic, "replayed", n);
    }

    /** 发回原 topic，保留 key 和 trace-id，去掉 Spring 加的 kafka_dlt 头。 */
    static ProducerRecord<String, String> toReplay(String topic, ConsumerRecord<String, String> r) {
        ProducerRecord<String, String> out = new ProducerRecord<>(topic, r.key(), r.value());
        for (Header h : r.headers()) {
            if (!h.key().startsWith("kafka_dlt")) out.headers().add(h);
        }
        return out;
    }
}
