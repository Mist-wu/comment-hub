package io.commenthub.ai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.commenthub.ai.guard.GuardedLlm;
import io.commenthub.ai.guard.KeywordRules;
import io.commenthub.ai.guard.Outcome;
import io.commenthub.ai.llm.ModerationParser;
import io.commenthub.ai.llm.ModerationParser.Verdict;
import io.commenthub.ai.llm.Prompts;
import io.commenthub.ai.v1.ModerateReply;
import io.commenthub.ai.v1.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 审核：相同内容（刷屏、复制粘贴）先查本地缓存；再走受保护的模型调用；
 * 输出解析失败重试一次，仍失败或任何一道关拦下，都降级到关键词规则。这个方法永远不抛异常。
 */
@Service
public class ModerationService {
    private static final Logger log = LoggerFactory.getLogger(ModerationService.class);
    /** 审核输出是一个小 JSON，200 足够；设成 80 时真模型会被截断（见 docs/cost.md） */
    static final int MAX_TOKENS = 200;
    private final GuardedLlm guard;
    private final LlmInvoker llm;
    private final Prompts prompts;
    private final Cache<String, Verdict> byContent = Caffeine.newBuilder()
            .maximumSize(50_000).expireAfterWrite(Duration.ofMinutes(30)).build();

    public ModerationService(GuardedLlm guard, LlmInvoker llm, Prompts prompts) {
        this.guard = guard;
        this.llm = llm;
        this.prompts = prompts;
    }

    public ModerateReply moderate(long commentId, String content, Long callerRemainingMs) {
        String key = content.strip();
        Verdict cached = byContent.getIfPresent(key);
        if (cached != null) return reply(cached, Source.CACHE, "");

        String user = prompts.moderateUser(content);
        int est = LlmInvoker.estimate(prompts.moderateSystem, user, MAX_TOKENS);
        Outcome<Verdict> o = guard.call("moderate", est, callerRemainingMs, timeout -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                var r = llm.invoke("moderate", commentId, prompts.moderateSystem, user, true, MAX_TOKENS, est, timeout);
                Optional<Verdict> v = ModerationParser.parse(r.content());
                if (v.isPresent()) return v.get();
                log.warn("moderation parse failed attempt={} output={}", attempt + 1, r.content());
            }
            return null;
        });
        if (o.isOk()) {
            byContent.put(key, o.value());
            return reply(o.value(), Source.LLM, "");
        }
        return reply(KeywordRules.judge(content), Source.RULE, o.degradeReason());
    }

    private static ModerateReply reply(Verdict v, Source s, String degrade) {
        return ModerateReply.newBuilder().setLabel(v.label()).setReason(v.reason()).setConfidence(v.confidence())
                .setSource(s).setDegradeReason(degrade).build();
    }
}
