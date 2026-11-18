package io.commenthub.ai.service;

import io.commenthub.ai.guard.GuardedLlm;
import io.commenthub.ai.llm.CostLedger;
import io.commenthub.ai.llm.LlmClient;
import io.commenthub.ai.llm.LlmUsage;
import org.springframework.stereotype.Component;

import java.net.http.HttpTimeoutException;
import java.time.Duration;

/** 一次 HTTP 调用 = 记账表里一行。解析失败的重试也单独记一行，因为它同样花钱。 */
@Component
public class LlmInvoker {
    private final LlmClient client;
    private final CostLedger ledger;
    private final GuardedLlm guard;

    public LlmInvoker(LlmClient client, CostLedger ledger, GuardedLlm guard) {
        this.client = client;
        this.ledger = ledger;
        this.guard = guard;
    }

    public LlmClient.Reply invoke(String task, Long bizId, String system, String user, boolean json, int maxTokens,
                                  int estTokens, Duration timeout) throws Exception {
        long id = ledger.start(task, bizId);
        long t0 = System.currentTimeMillis();
        try {
            LlmClient.Reply r = client.chat(system, user, json, maxTokens, timeout);
            ledger.finish(id, task, "success", r.usage(), System.currentTimeMillis() - t0, null);
            guard.charge(r.usage().total() - estTokens);
            return r;
        } catch (HttpTimeoutException e) {
            ledger.finish(id, task, "timeout", LlmUsage.ZERO, System.currentTimeMillis() - t0, e.toString());
            throw e;
        } catch (Exception e) {
            ledger.finish(id, task, "error", LlmUsage.ZERO, System.currentTimeMillis() - t0, e.toString());
            throw e;
        }
    }

    static int estimate(String system, String user, int maxTokens) {
        return (int) Math.ceil((system.length() + user.length()) / 1.5) + maxTokens;
    }
}
