package io.commenthub.ai.grpc;

import io.commenthub.ai.service.ModerationService;
import io.commenthub.ai.service.SummaryService;
import io.commenthub.ai.v1.*;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class AiGatewayGrpcService extends AiGatewayGrpc.AiGatewayImplBase {
    private static final Logger log = LoggerFactory.getLogger(AiGatewayGrpcService.class);
    private final ModerationService moderation;
    private final SummaryService summary;

    public AiGatewayGrpcService(ModerationService moderation, SummaryService summary) {
        this.moderation = moderation;
        this.summary = summary;
    }

    @Override
    public void moderate(ModerateRequest req, StreamObserver<ModerateReply> out) {
        try {
            ModerateReply r = moderation.moderate(req.getCommentId(), req.getContent(), remainingMs());
            log.info("moderate comment={} label={} source={} degrade={}", req.getCommentId(), r.getLabel(), r.getSource(), r.getDegradeReason());
            out.onNext(r);
            out.onCompleted();
        } catch (Exception e) {
            out.onError(Status.INTERNAL.withDescription(e.toString()).asRuntimeException());
        }
    }

    @Override
    public void summarize(SummarizeRequest req, StreamObserver<SummarizeReply> out) {
        try {
            out.onNext(summary.summarize(req.getVideoId(), req.getCommentCount(), req.getSampleCommentsList(), remainingMs()));
            out.onCompleted();
        } catch (Exception e) {
            out.onError(Status.INTERNAL.withDescription(e.toString()).asRuntimeException());
        }
    }

    /** 调用方的 deadline 随 gRPC 传过来，模型调用的超时不会超过它。 */
    private static Long remainingMs() {
        Deadline d = Context.current().getDeadline();
        return d == null ? null : Math.max(d.timeRemaining(TimeUnit.MILLISECONDS), 0);
    }
}
