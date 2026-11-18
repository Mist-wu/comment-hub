package io.commenthub.comment.domain;

import java.time.ZoneOffset;

/** 对外返回的评论。createdAt 用毫秒时间戳，也是游标的一部分。 */
public record CommentView(long id, long videoId, long userId, String content, long likeCount, int status, long createdAt) {
    public static CommentView of(Comment c) {
        return new CommentView(c.getId(), c.getVideoId(), c.getUserId(), c.getContent(),
                c.getLikeCount(), c.getStatus(), c.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli());
    }

    public CommentView withLikeCount(long n) {
        return new CommentView(id, videoId, userId, content, n, status, createdAt);
    }
}
