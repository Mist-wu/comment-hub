package io.commenthub.comment.service;

import io.commenthub.ai.v1.AiGatewayGrpc;
import io.commenthub.ai.v1.SummarizeReply;
import io.commenthub.ai.v1.SummarizeRequest;
import io.commenthub.comment.config.Toggles;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 摘要是锦上添花：任何异常（超时、网关挂了、熔断）都返回 null，前端不显示摘要。
 * 每次调用都设 deadline；deadline 会通过 gRPC 传到网关，网关据此放弃已经没意义的工作。
 */
@Component
public class SummaryClient {
    private static final Logger log = LoggerFactory.getLogger(SummaryClient.class);
    private final AiGatewayGrpc.AiGatewayBlockingStub stub;
    private final Toggles toggles;
    private final MeterRegistry meters;

    public SummaryClient(AiGatewayGrpc.AiGatewayBlockingStub stub, Toggles toggles, MeterRegistry meters) {
        this.stub = stub;
        this.toggles = toggles;
        this.meters = meters;
    }

    public String summaryOrNull(long videoId, long commentCount, List<String> samples) {
        if (!toggles.summaryEnabled || commentCount < toggles.summaryMinComments) return null;
        int deadline = toggles.summaryDeadlineMs;
        AiGatewayGrpc.AiGatewayBlockingStub s = deadline > 0 ? stub.withDeadlineAfter(deadline, TimeUnit.MILLISECONDS) : stub;
        try {
            SummarizeReply r = s.summarize(SummarizeRequest.newBuilder()
                    .setVideoId(videoId).setCommentCount(commentCount).addAllSampleComments(samples).build());
            meters.counter("hub.summary", "result", r.getReady() ? "ready" : "not_ready").increment();
            return r.getReady() && !r.getSummary().isEmpty() ? r.getSummary() : null;
        } catch (StatusRuntimeException e) {
            meters.counter("hub.summary", "result", e.getStatus().getCode().name().toLowerCase()).increment();
            log.debug("summary skipped video={} status={}", videoId, e.getStatus().getCode());
            return null;
        }
    }
}
