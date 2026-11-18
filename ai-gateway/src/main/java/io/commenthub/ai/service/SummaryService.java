package io.commenthub.ai.service;

import io.commenthub.ai.GatewayToggles;
import io.commenthub.ai.guard.GuardedLlm;
import io.commenthub.ai.guard.Outcome;
import io.commenthub.ai.llm.Prompts;
import io.commenthub.ai.v1.Source;
import io.commenthub.ai.v1.SummarizeReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评论区摘要，按“视频 + 评论数档位”缓存：评论数跨过 100 / 1000 / 1 万 / 10 万时才重新生成，
 * 档位内评论再多也不花钱。
 *
 * <p>读路径不等模型：未命中时启动一次后台生成（同一个 key 同时只有一个在生成），本次返回 ready=false。
 * 生成失败写 30 秒的失败标记，模型挂掉期间不会被读流量反复触发。
 */
@Service
public class SummaryService {
    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);
    private static final int MAX_TOKENS = 160;
    private final StringRedisTemplate redis;
    private final GuardedLlm guard;
    private final LlmInvoker llm;
    private final Prompts prompts;
    private final GatewayToggles toggles;
    private final Map<String, Boolean> inFlight = new ConcurrentHashMap<>();

    public SummaryService(StringRedisTemplate redis, GuardedLlm guard, LlmInvoker llm, Prompts prompts, GatewayToggles toggles) {
        this.redis = redis;
        this.guard = guard;
        this.llm = llm;
        this.prompts = prompts;
        this.toggles = toggles;
    }

    /** 档位：0: <100, 1: <1000, 2: <1万, 3: <10万, 4: ≥10万 */
    public static int tier(long commentCount) {
        int t = 0;
        for (long n = commentCount; n >= 100 && t < 4; n /= 10) t++;
        return t;
    }

    public static String key(long videoId, long commentCount) {
        return "sum:" + videoId + ":" + tier(commentCount);
    }

    public SummarizeReply summarize(long videoId, long commentCount, List<String> samples, Long callerRemainingMs) {
        String key = key(videoId, commentCount);
        String hit = redis.opsForValue().get(key);
        if (hit != null) return SummarizeReply.newBuilder().setReady(true).setSummary(hit).setSource(Source.CACHE).build();
        if (Boolean.TRUE.equals(redis.hasKey(key + ":fail"))) {
            return SummarizeReply.newBuilder().setReady(false).setDegradeReason("recent_failure").build();
        }
        if (!toggles.summaryAsync) {
            // 朴素版：在请求线程里同步等模型
            Outcome<String> o = generate(videoId, key, samples, callerRemainingMs);
            return o.isOk()
                    ? SummarizeReply.newBuilder().setReady(true).setSummary(o.value()).setSource(Source.LLM).build()
                    : SummarizeReply.newBuilder().setReady(false).setDegradeReason(o.degradeReason()).build();
        }
        if (inFlight.putIfAbsent(key, Boolean.TRUE) == null) {
            Map<String, String> mdc = MDC.getCopyOfContextMap();
            Thread.ofVirtual().name("summary-" + videoId).start(() -> {
                if (mdc != null) MDC.setContextMap(mdc);
                try {
                    generate(videoId, key, samples, null);
                } finally {
                    inFlight.remove(key);
                    MDC.clear();
                }
            });
        }
        return SummarizeReply.newBuilder().setReady(false).setDegradeReason("generating").build();
    }

    private Outcome<String> generate(long videoId, String key, List<String> samples, Long callerRemainingMs) {
        String user = prompts.summarizeUser(samples);
        int est = LlmInvoker.estimate(prompts.summarizeSystem, user, MAX_TOKENS);
        Outcome<String> o = guard.call("summarize", est, callerRemainingMs, timeout -> {
            var r = llm.invoke("summarize", videoId, prompts.summarizeSystem, user, false, MAX_TOKENS, est, timeout);
            String s = r.content().strip();
            return s.isEmpty() ? null : s;
        });
        if (o.isOk()) {
            redis.opsForValue().set(key, o.value(), Duration.ofHours(6));
        } else {
            log.info("summary degraded video={} reason={}", videoId, o.degradeReason());
            redis.opsForValue().set(key + ":fail", o.degradeReason(), Duration.ofSeconds(30));
        }
        return o;
    }
}
