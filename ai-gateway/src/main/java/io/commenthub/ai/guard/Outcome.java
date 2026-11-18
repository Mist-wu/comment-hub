package io.commenthub.ai.guard;

/** 受保护调用的结果：要么有值，要么带降级原因。 */
public record Outcome<T>(T value, String degradeReason) {
    public static <T> Outcome<T> ok(T v) {
        return new Outcome<>(v, null);
    }

    public static <T> Outcome<T> degraded(String reason) {
        return new Outcome<>(null, reason);
    }

    public boolean isOk() {
        return degradeReason == null;
    }
}
