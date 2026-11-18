package io.commenthub.common.event;

/**
 * 点赞 / 取消点赞事件。key = commentId，同一条评论落在同一分区，批量合并后一次 UPDATE。
 * delta 为 +1 或 -1。
 */
public record LikeChanged(
        String eventId,
        long commentId,
        long userId,
        int delta,
        long atMillis) {
}
