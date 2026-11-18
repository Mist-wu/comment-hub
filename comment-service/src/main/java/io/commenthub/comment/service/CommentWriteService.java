package io.commenthub.comment.service;

import io.commenthub.ai.v1.AiGatewayGrpc;
import io.commenthub.ai.v1.ModerateReply;
import io.commenthub.ai.v1.ModerateRequest;
import io.commenthub.comment.config.Toggles;
import io.commenthub.comment.domain.Comment;
import io.commenthub.comment.domain.CommentView;
import io.commenthub.comment.mapper.CommentMapper;
import io.commenthub.comment.mapper.VideoMapper;
import io.commenthub.common.event.CommentCreated;
import io.commenthub.common.event.Topics;
import io.commenthub.common.trace.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 发评论只做两件事：写库、发 CommentCreated 事件，然后返回。
 * 评论数、审核、删缓存都由 consumer 异步完成，写接口的耗时不受它们影响。
 */
@Service
public class CommentWriteService {
    private static final Logger log = LoggerFactory.getLogger(CommentWriteService.class);
    private final CommentMapper comments;
    private final VideoMapper videos;
    private final EventPublisher publisher;
    private final Toggles toggles;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final AiGatewayGrpc.AiGatewayBlockingStub ai;

    public CommentWriteService(CommentMapper comments, VideoMapper videos, EventPublisher publisher, Toggles toggles,
                               JdbcTemplate jdbc, StringRedisTemplate redis, AiGatewayGrpc.AiGatewayBlockingStub ai) {
        this.comments = comments;
        this.videos = videos;
        this.publisher = publisher;
        this.toggles = toggles;
        this.jdbc = jdbc;
        this.redis = redis;
        this.ai = ai;
    }

    public CommentView post(long videoId, long userId, String content) {
        if (videos.commentCount(videoId) == null) throw new NotFoundException("video " + videoId);
        Comment c = new Comment();
        c.setVideoId(videoId);
        c.setUserId(userId);
        c.setContent(content);
        c.setLikeCount(0);
        c.setStatus(0); // 先发后审
        c.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS));
        comments.insert(c);
        CommentView v = CommentView.of(c);
        if (toggles.syncWrite) {
            return syncSideEffects(v);
        }
        String eventId = Trace.randomEventId();
        publisher.publish(Topics.COMMENT_CREATED, String.valueOf(videoId),
                new CommentCreated(eventId, v.id(), videoId, userId, content, v.createdAt()));
        log.info("comment posted id={} video={} event={}", v.id(), videoId, eventId);
        return v;
    }

    /**
     * 朴素版：所有副作用都在请求线程里同步完成。热门视频的 video 行变成热点行，
     * 请求耗时还叠加了一次模型调用。只用于和异步版做对比。
     */
    private CommentView syncSideEffects(CommentView v) {
        jdbc.update("UPDATE video SET comment_count = comment_count + 1 WHERE id = ?", v.videoId());
        redis.delete(List.of(CommentQueryService.latestKey(v.videoId()), CounterService.key(v.videoId())));
        ModerateReply r = ai.withDeadlineAfter(5, TimeUnit.SECONDS).moderate(ModerateRequest.newBuilder()
                .setCommentId(v.id()).setVideoId(v.videoId()).setContent(v.content()).build());
        int status = switch (r.getLabel()) {
            case REJECT -> 3;
            case REVIEW -> 2;
            default -> 1;
        };
        jdbc.update("UPDATE comment SET status = ? WHERE id = ?", status, v.id());
        log.info("comment posted (sync) id={} video={} label={}", v.id(), v.videoId(), r.getLabel());
        return new CommentView(v.id(), v.videoId(), v.userId(), v.content(), 0, status, v.createdAt());
    }
}
