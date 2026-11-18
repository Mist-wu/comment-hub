package io.commenthub.common.trace;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * trace ID 在 HTTP → gRPC → Kafka → 下游 HTTP 之间传递。
 * 线程内用 MDC 保存，日志 pattern 里打 %X{traceId}，一条 grep 能查到整条链路。
 */
public final class Trace {
    public static final String MDC_KEY = "traceId";
    public static final String HTTP_HEADER = "X-Trace-Id";
    public static final String KAFKA_HEADER = "trace-id";
    public static final String GRPC_KEY = "x-trace-id";

    private Trace() {}

    public static String newId() {
        // 16 字节随机十六进制，够用且比 UUID 短
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return Long.toHexString(r.nextLong() | Long.MIN_VALUE) + Long.toHexString(r.nextLong() | Long.MIN_VALUE);
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static String currentOrNew() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : newId();
    }

    /** 设置当前线程的 trace ID，返回一个用于恢复现场的 Scope。 */
    public static Scope open(String traceId) {
        String prev = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, traceId == null || traceId.isBlank() ? newId() : traceId);
        return () -> {
            if (prev == null) MDC.remove(MDC_KEY);
            else MDC.put(MDC_KEY, prev);
        };
    }

    public static String randomEventId() {
        return UUID.randomUUID().toString();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
