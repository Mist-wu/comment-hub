package io.commenthub.common.event;

/**
 * 发评论后发出的事件。key = videoId，保证同一视频的事件有序。
 * eventId 是消费者去重的依据。
 */
public record CommentCreated(
        String eventId,
        long commentId,
        long videoId,
        long userId,
        String content,
        long createdAtMillis) {
}
