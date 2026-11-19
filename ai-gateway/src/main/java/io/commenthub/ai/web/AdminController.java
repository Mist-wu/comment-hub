package io.commenthub.ai.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commenthub.ai.GatewayToggles;
import io.commenthub.ai.guard.GuardedLlm;
import io.commenthub.ai.llm.CostLedger;
import io.commenthub.ai.service.EvalService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final GatewayToggles toggles;
    private final ObjectMapper om;
    private final GuardedLlm guard;
    private final CostLedger ledger;
    private final EvalService eval;

    public AdminController(GatewayToggles toggles, ObjectMapper om, GuardedLlm guard, CostLedger ledger, EvalService eval) {
        this.toggles = toggles;
        this.om = om;
        this.guard = guard;
        this.ledger = ledger;
        this.eval = eval;
    }

    @GetMapping("/toggles")
    public GatewayToggles get() {
        return toggles;
    }

    @PostMapping("/toggles")
    public GatewayToggles set(@RequestBody Map<String, Object> patch) throws Exception {
        om.readerForUpdating(toggles).readValue(om.writeValueAsBytes(patch));
        return toggles;
    }

    @GetMapping("/breaker")
    public Map<String, Object> breaker() {
        CircuitBreaker cb = guard.breaker();
        CircuitBreaker.Metrics m = cb.getMetrics();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", cb.getState().name());
        out.put("failureRate", m.getFailureRate());
        out.put("slowCallRate", m.getSlowCallRate());
        out.put("bufferedCalls", m.getNumberOfBufferedCalls());
        out.put("notPermitted", m.getNumberOfNotPermittedCalls());
        return out;
    }

    @PostMapping("/breaker/reset")
    public Map<String, Object> reset() {
        guard.breaker().reset();
        return breaker();
    }

    @GetMapping("/cost")
    public List<Map<String, Object>> cost() {
        return ledger.summary();
    }

    /** 用 moderation_gold 里人工标注过的样本评估审核准确率。只在 provider=deepseek 时有意义。 */
    @PostMapping("/eval/moderation")
    public Map<String, Object> evalModeration(@RequestParam(defaultValue = "sample") String source,
                                              @RequestParam(defaultValue = "1000") int limit) {
        return eval.run(source, limit);
    }
}
