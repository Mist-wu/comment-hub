package io.commenthub.consumer;

import io.commenthub.common.event.Topics;
import io.commenthub.common.trace.Trace;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DltNamesTest {
    @Test
    void publisherAndReplayerShareDashDltSuffixAndPartition() {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(Topics.COMMENT_CREATED, 3, 8, "video-9", "{\"eventId\":\"e\"}");
        rec.headers().add(Trace.KAFKA_HEADER, "abc".getBytes(StandardCharsets.UTF_8));
        rec.headers().add("kafka_dlt-exception-message", "boom".getBytes(StandardCharsets.UTF_8));
        var dead = KafkaConfig.deadLetter(rec);
        assertThat(dead.topic()).isEqualTo("comment-created-dlt");
        assertThat(dead.partition()).isEqualTo(3);
        assertThat(DltNames.topic(Topics.COMMENT_CREATED)).isEqualTo(dead.topic());
        assertThat(dead.topic()).doesNotEndWith(".DLT");

        ProducerRecord<String, String> replay = DltReplayer.toReplay(Topics.COMMENT_CREATED, rec);
        assertThat(replay.topic()).isEqualTo(Topics.COMMENT_CREATED);
        assertThat(replay.key()).isEqualTo("video-9");
        assertThat(replay.value()).contains("eventId");
        assertThat(new String(replay.headers().lastHeader(Trace.KAFKA_HEADER).value(), StandardCharsets.UTF_8)).isEqualTo("abc");
        assertThat(headerNames(replay)).doesNotContain("kafka_dlt-exception-message");
    }

    private static java.util.List<String> headerNames(ProducerRecord<String, String> rec) {
        java.util.List<String> names = new java.util.ArrayList<>();
        rec.headers().forEach(h -> names.add(h.key()));
        return names;
    }
}
