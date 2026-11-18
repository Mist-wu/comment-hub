package io.commenthub.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commenthub.ai.v1.*;
import io.commenthub.common.event.CommentCreated;
import io.commenthub.common.event.LikeChanged;
import io.commenthub.common.event.Topics;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费者幂等：同一条消息投递两次，结果和投递一次一样；失败的消息进死信，重放后补齐。
 * ai-gateway 用一个进程内的假 gRPC 服务代替。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConsumerIdempotencyIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.3")
            .withDatabaseName("comment_hub").withUsername("root").withPassword("test").withInitScript("db/schema.sql");
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    static Server fakeGateway;
    static int port;

    @BeforeAll
    static void startGateway() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        fakeGateway = ServerBuilder.forPort(port).addService(new AiGatewayGrpc.AiGatewayImplBase() {
            @Override
            public void moderate(ModerateRequest req, StreamObserver<ModerateReply> out) {
                Label l = req.getContent().contains("骗子") ? Label.REJECT : Label.PASS;
                out.onNext(ModerateReply.newBuilder().setLabel(l).setSource(Source.LLM).setConfidence(0.9).setReason("t").build());
                out.onCompleted();
            }
        }).build().start();
    }

    @AfterAll
    static void stopGateway() {
        fakeGateway.shutdownNow();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", () -> "root");
        r.add("spring.datasource.password", () -> "test");
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("hub.ai-gateway.target", () -> "localhost:" + port);
    }

    @Autowired KafkaTemplate<String, String> kafkaT;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper om;
    @Autowired ConsumerToggles toggles;
    @Autowired DltReplayer replayer;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM consumed_event");
        jdbc.update("DELETE FROM comment");
        jdbc.update("DELETE FROM video");
        jdbc.update("INSERT INTO video (id, title, comment_count) VALUES (1, 'v', 0)");
        toggles.moderationFailRate = 0;
    }

    @Test
    void sameCommentCreatedTwiceIsAppliedOnce() throws Exception {
        jdbc.update("INSERT INTO comment (id, video_id, user_id, content, status) VALUES (10, 1, 1, '你是骗子', 0)");
        CommentCreated e = new CommentCreated(UUID.randomUUID().toString(), 10, 1, 1, "你是骗子", System.currentTimeMillis());
        send(Topics.COMMENT_CREATED, "1", e);
        send(Topics.COMMENT_CREATED, "1", e); // 重放

        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE event_id=?", Integer.class, e.eventId()) == 2);
        Thread.sleep(1000); // 给第二条消息时间被处理（并被跳过）
        assertThat(jdbc.queryForObject("SELECT comment_count FROM video WHERE id=1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM comment WHERE id=10", Integer.class)).isEqualTo(3);
    }

    @Test
    void likeEventsAreMergedAndDeduplicated() throws Exception {
        jdbc.update("INSERT INTO comment (id, video_id, user_id, content, like_count, status) VALUES (20, 1, 1, 'x', 100, 1)");
        LikeChanged a = new LikeChanged(UUID.randomUUID().toString(), 20, 1, 1, System.currentTimeMillis());
        LikeChanged b = new LikeChanged(UUID.randomUUID().toString(), 20, 2, 1, System.currentTimeMillis());
        LikeChanged c = new LikeChanged(UUID.randomUUID().toString(), 20, 1, -1, System.currentTimeMillis());
        for (LikeChanged ev : new LikeChanged[]{a, b, c, a, b}) send(Topics.LIKE_CHANGED, "20", ev);
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE consumer='like-flush'", Integer.class) == 3);
        Thread.sleep(1000);
        assertThat(jdbc.queryForObject("SELECT like_count FROM comment WHERE id=20", Integer.class)).isEqualTo(101);
    }

    @Test
    void failedModerationGoesToDltAndReplayFixesIt() throws Exception {
        jdbc.update("INSERT INTO comment (id, video_id, user_id, content, status) VALUES (30, 1, 1, '正常评论', 0)");
        toggles.moderationFailRate = 1.0;
        CommentCreated e = new CommentCreated(UUID.randomUUID().toString(), 30, 1, 1, "正常评论", System.currentTimeMillis());
        send(Topics.COMMENT_CREATED, "1", e);
        // 计数不受审核失败影响
        await(() -> jdbc.queryForObject("SELECT comment_count FROM video WHERE id=1", Integer.class) == 1);
        Thread.sleep(6000); // 1 秒间隔重试 3 次后进死信
        assertThat(jdbc.queryForObject("SELECT status FROM comment WHERE id=30", Integer.class)).isZero();

        toggles.moderationFailRate = 0;
        int replayed = (int) replayer.replay(Topics.COMMENT_CREATED, 100).get("replayed");
        assertThat(replayed).isGreaterThanOrEqualTo(1);
        await(() -> jdbc.queryForObject("SELECT status FROM comment WHERE id=30", Integer.class) == 1);
        // 重放的消息也被计数消费者收到，但被去重挡住
        Thread.sleep(1000);
        assertThat(jdbc.queryForObject("SELECT comment_count FROM video WHERE id=1", Integer.class)).isEqualTo(1);
    }

    private void send(String topic, String key, Object ev) throws Exception {
        kafkaT.send(topic, key, om.writeValueAsString(ev)).get();
    }

    private static void await(Callable<Boolean> cond) throws Exception {
        long end = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        while (System.currentTimeMillis() < end) {
            try {
                if (Boolean.TRUE.equals(cond.call())) return;
            } catch (Exception ignored) {
            }
            Thread.sleep(200);
        }
        throw new AssertionError("condition not met within 60s");
    }
}
