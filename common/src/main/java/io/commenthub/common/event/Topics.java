package io.commenthub.common.event;

public final class Topics {
    public static final String COMMENT_CREATED = "comment-created";
    public static final String LIKE_CHANGED = "like-changed";
    /** spring-kafka DeadLetterPublishingRecoverer 默认在原 topic 后加 -dlt */
    public static final String DLT_SUFFIX = "-dlt";

    private Topics() {}
}
