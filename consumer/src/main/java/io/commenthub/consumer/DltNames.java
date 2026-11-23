package io.commenthub.consumer;

/**
 * 死信 topic 名。Spring Kafka 3.2 的默认后缀是 {@code .DLT}，这里发布和重放都用 {@code -dlt}。
 * 两边必须走同一个函数，删掉自定义 resolver 时不能只改一边。
 */
public final class DltNames {
    public static final String SUFFIX = "-dlt";

    private DltNames() {}

    public static String topic(String original) {
        return original + SUFFIX;
    }
}
