package io.commenthub.comment.service;

import io.commenthub.comment.domain.Comment;
import io.commenthub.comment.domain.CommentView;
import io.commenthub.comment.mapper.CommentMapper;
import io.commenthub.comment.mapper.VideoMapper;
import io.commenthub.common.event.CommentCreated;
import io.commenthub.common.event.Topics;
import io.commenthub.common.trace.Trace;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 发评论只做两件事：写库、发 CommentCreated 事件，然后返回。
 * 评论数、审核、删缓存都由 consumer 异步完成，写接口的耗时不受它们影响。
 */
@Service
public class CommentWriteService {
    private final CommentMapper comments;
    private final VideoMapper videos;
    private final EventPublisher publisher;

    public CommentWriteService(CommentMapper comments, VideoMapper videos, EventPublisher publisher) {
        this.comments = comments;
        this.videos = videos;
        this.publisher = publisher;
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
        publisher.publish(Topics.COMMENT_CREATED, String.valueOf(videoId),
                new CommentCreated(Trace.randomEventId(), v.id(), videoId, userId, content, v.createdAt()));
        return v;
    }
}
