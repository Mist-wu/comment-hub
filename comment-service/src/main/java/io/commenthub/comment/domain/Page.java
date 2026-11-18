package io.commenthub.comment.domain;

import java.util.List;

/** 一页评论。nextCursor 为 null 表示没有下一页；summary 为 null 表示不显示摘要。 */
public record Page(List<CommentView> items, String nextCursor, String summary) {
    public Page withSummary(String s) {
        return new Page(items, nextCursor, s);
    }
}
