package io.commenthub.mockllm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * OpenAI 兼容的 /v1/chat/completions。ai-gateway 指向这里还是 DeepSeek 只差一个 base URL。
 * 用最后一条 user 消息里的标记区分任务：<comment>…</comment> 是审核，<comments>…</comments> 是摘要。
 */
@RestController
public class ChatController {
    private final FaultConfig cfg;
    private final ObjectMapper om;
    private final MeterRegistry meters;

    public ChatController(FaultConfig cfg, ObjectMapper om, MeterRegistry meters) {
        this.cfg = cfg;
        this.om = om;
        this.meters = meters;
    }

    @PostMapping("/v1/chat/completions")
    public ResponseEntity<?> chat(@RequestBody JsonNode body) throws InterruptedException {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() < cfg.timeoutRate) {
            meters.counter("mockllm.requests", "outcome", "hang").increment();
            Thread.sleep(cfg.hangMs);
        }
        Thread.sleep(cfg.latencyMs + (cfg.jitterMs > 0 ? r.nextInt(cfg.jitterMs + 1) : 0));
        if (r.nextDouble() < cfg.errorRate) {
            meters.counter("mockllm.requests", "outcome", "error").increment();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", Map.of("message", "injected failure")));
        }

        StringBuilder prompt = new StringBuilder();
        String user = "";
        for (JsonNode m : body.path("messages")) {
            prompt.append(m.path("content").asText());
            if ("user".equals(m.path("role").asText())) user = m.path("content").asText();
        }
        String content;
        if (user.contains("<comments>")) {
            String inner = between(user, "<comments>", "</comments>");
            List<String> lines = Arrays.stream(inner.split("\n")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            content = FakeModel.summarize(lines);
        } else {
            content = FakeModel.moderate(between(user, "<comment>", "</comment>"));
        }
        if (r.nextDouble() < cfg.badJsonRate) {
            content = "好的，以下是审核结果：" + content.substring(0, content.length() / 2);
        }
        meters.counter("mockllm.requests", "outcome", "ok").increment();

        int in = FakeModel.tokens(prompt.toString());
        int out = FakeModel.tokens(content);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", "mock-" + UUID.randomUUID());
        resp.put("object", "chat.completion");
        resp.put("model", body.path("model").asText("mock"));
        resp.put("choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                "message", Map.of("role", "assistant", "content", content))));
        resp.put("usage", Map.of("prompt_tokens", in, "completion_tokens", out, "total_tokens", in + out,
                "prompt_cache_hit_tokens", 0));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/admin/config")
    public FaultConfig get() {
        return cfg;
    }

    /** 局部更新，例如 {"latencyMs":10000,"errorRate":0.5} */
    @PostMapping("/admin/config")
    public FaultConfig set(@RequestBody Map<String, Object> patch) throws Exception {
        om.readerForUpdating(cfg).readValue(om.writeValueAsBytes(patch));
        return cfg;
    }

    private static String between(String s, String a, String b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b, i + 1);
        return i >= 0 && j > i ? s.substring(i + a.length(), j) : s;
    }
}
