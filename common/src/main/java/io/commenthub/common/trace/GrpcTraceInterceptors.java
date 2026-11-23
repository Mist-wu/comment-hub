package io.commenthub.common.trace;

import io.grpc.*;

/** gRPC 客户端把 MDC 里的 trace ID 写进 metadata；服务端读出来放回 MDC。 */
public final class GrpcTraceInterceptors {
    public static final Metadata.Key<String> TRACE_KEY =
            Metadata.Key.of(Trace.GRPC_KEY, Metadata.ASCII_STRING_MARSHALLER);

    private GrpcTraceInterceptors() {}

    public static ClientInterceptor client() {
        return new ClientInterceptor() {
            @Override
            public <Q, R> ClientCall<Q, R> interceptCall(MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
                    @Override
                    public void start(Listener<R> listener, Metadata headers) {
                        String id = Trace.current();
                        if (id != null) headers.put(TRACE_KEY, id);
                        super.start(listener, headers);
                    }
                };
            }
        };
    }

    public static ServerInterceptor server() {
        return new ServerInterceptor() {
            @Override
            public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
                String id = headers.get(TRACE_KEY);
                if (id == null) id = Trace.newId();
                final String traceId = id;
                ServerCall.Listener<Q> delegate = next.startCall(call, headers);
                // 每个回调都在可能不同的线程上执行，所以每次都设置 MDC
                return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
                    @Override public void onMessage(Q message) { try (var s = Trace.open(traceId)) { super.onMessage(message); } }
                    @Override public void onHalfClose() { try (var s = Trace.open(traceId)) { super.onHalfClose(); } }
                    @Override public void onCancel() { try (var s = Trace.open(traceId)) { super.onCancel(); } }
                    @Override public void onComplete() { try (var s = Trace.open(traceId)) { super.onComplete(); } }
                    @Override public void onReady() { try (var s = Trace.open(traceId)) { super.onReady(); } }
                };
            }
        };
    }
}
