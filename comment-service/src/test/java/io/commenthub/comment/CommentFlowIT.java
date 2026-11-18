package io.commenthub.comment;

import io.commenthub.comment.config.Toggles;
import io.commenthub.comment.domain.CommentView;
import io.commenthub.comment.domain.Page;
import io.commenthub.comment.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用真的 MySQL、Redis、Kafka 跑 comment-service 的核心链路。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CommentFlowIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.3")
            .withDatabaseName("comment_hub").withUsername("root").withPassword("test")
            .withInitScript("db/schema.sql");
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> mysql.getJdbcUrl() + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
        r.add("spring.datasource.username", () -> "root");
        r.add("spring.datasource.password", () -> "test");
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("hub.ai-gateway.target", () -> "localhost:1"); // 网关不存在：摘要应静默降级
    }

    @Autowired CommentWriteService writer;
    @Autowired CommentQueryService reader;
    @Autowired LikeService likes;
    @Autowired Toggles toggles;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redisT;
    @Autowired VideoBloom bloom;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM comment_like");
        jdbc.update("DELETE FROM comment");
        jdbc.update("DELETE FROM video");
        jdbc.update("INSERT INTO video (id, title, comment_count) VALUES (1, 'v1', 0), (2, 'v2', 0)");
        redisT.getConnectionFactory().getConnection().serverCommands().flushAll();
        bloom.rebuild();
        toggles.likeMode = Toggles.LikeMode.REDIS;
    }

    @Test
    void bloomFilterBlocksUnknownVideoButOnlyAfterItHasData() {
        toggles.penetration = Toggles.Penetration.BLOOM;
        assertThat(bloom.mightExist(1)).isTrue();
        assertThat(bloom.mightExist(424242)).isFalse();
        jdbc.update("DELETE FROM video");
        bloom.rebuild();
        assertThat(bloom.mightExist(424242)).isTrue(); // 空表时不启用，不会误杀
    }

    @Test
    void cursorPaginationWalksAllCommentsInOrderWithoutDuplicates() {
        List<Long> posted = new ArrayList<>();
        for (int i = 0; i < 45; i++) posted.add(writer.post(1, 100 + i, "第 " + i + " 条").id());
        Collections.reverse(posted); // 最新的在前

        List<Long> seen = new ArrayList<>();
        String cursor = null;
        do {
            Page p = reader.latest(1, cursor, 10);
            p.items().forEach(c -> seen.add(c.id()));
            cursor = p.nextCursor();
        } while (cursor != null);
        assertThat(seen).containsExactlyElementsOf(posted);
        // 与 LIMIT offset 的结果一致
        assertThat(reader.latestByOffset(1, 3, 10).items().stream().map(CommentView::id).toList())
                .containsExactlyElementsOf(posted.subList(20, 30));
    }

    @Test
    void firstPageIsCachedAndPostingDoesNotEvictSynchronously() {
        writer.post(1, 1, "a");
        Page first = reader.latest(1, null, 20);
        assertThat(first.items()).hasSize(1);
        assertThat(first.summary()).isNull(); // 网关不可达 → 不显示摘要，接口照常返回
        assertThat(redisT.hasKey(CommentQueryService.latestKey(1))).isTrue();
        writer.post(1, 2, "b");
        // 删缓存由 consumer 完成，这里仍是旧的首页 —— 这就是 cache-aside 的不一致窗口
        assertThat(reader.latest(1, null, 20).items()).hasSize(1);
        redisT.delete(CommentQueryService.latestKey(1));
        assertThat(reader.latest(1, null, 20).items()).hasSize(2);
    }

    @Test
    void unknownVideoIsRejectedOnWrite() {
        assertThatThrownBy(() -> writer.post(999, 1, "x")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void dbModeLikesAreIdempotentAndCorrectUnderConcurrency() throws Exception {
        toggles.likeMode = Toggles.LikeMode.DB;
        long cid = writer.post(1, 1, "hot").id();
        assertThat(likes.like(7, cid).changed()).isTrue();
        assertThat(likes.like(7, cid).changed()).isFalse(); // 唯一键挡住重复点赞
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> fs = new ArrayList<>();
        for (int u = 1000; u < 1200; u++) {
            long user = u;
            fs.add(pool.submit(() -> likes.like(user, cid)));
        }
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        assertThat(jdbc.queryForObject("SELECT like_count FROM comment WHERE id=?", Integer.class, cid)).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_like WHERE comment_id=?", Integer.class, cid)).isEqualTo(201);
        likes.unlike(7, cid);
        likes.unlike(7, cid);
        assertThat(jdbc.queryForObject("SELECT like_count FROM comment WHERE id=?", Integer.class, cid)).isEqualTo(200);
    }

    @Test
    void redisModeCountsInRedisAndLeavesMysqlToConsumer() {
        toggles.likeMode = Toggles.LikeMode.REDIS;
        long cid = writer.post(1, 1, "hot").id();
        jdbc.update("UPDATE comment SET like_count = 5 WHERE id = ?", cid); // 历史点赞
        assertThat(likes.like(1, cid).likeCount()).isEqualTo(6);          // 从库里的 5 初始化
        assertThat(likes.like(1, cid).changed()).isFalse();
        assertThat(likes.like(2, cid).likeCount()).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT like_count FROM comment WHERE id=?", Integer.class, cid)).isEqualTo(5);
        assertThat(reader.latest(1, null, 20).items().get(0).likeCount()).isEqualTo(7); // 读时用 Redis 覆盖
    }
}
