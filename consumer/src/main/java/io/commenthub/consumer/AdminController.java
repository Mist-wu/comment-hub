package io.commenthub.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {
    private final ConsumerToggles toggles;
    private final ObjectMapper om;
    private final DltReplayer replayer;

    public AdminController(ConsumerToggles toggles, ObjectMapper om, DltReplayer replayer) {
        this.toggles = toggles;
        this.om = om;
        this.replayer = replayer;
    }

    @GetMapping("/toggles")
    public ConsumerToggles get() {
        return toggles;
    }

    @PostMapping("/toggles")
    public ConsumerToggles set(@RequestBody Map<String, Object> patch) throws Exception {
        return om.updateValue(toggles, patch);
    }

    /** scripts/replay-dlt.sh 调这里 */
    @PostMapping("/dlt/replay")
    public Map<String, Object> replay(@RequestParam String topic, @RequestParam(defaultValue = "100000") int max) throws Exception {
        return replayer.replay(topic, max);
    }
}
