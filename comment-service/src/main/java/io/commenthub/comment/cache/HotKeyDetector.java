package io.commenthub.comment.cache;

import io.commenthub.comment.config.Toggles;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 每秒统计一次各 key 的访问次数，超过阈值的 key 在下一秒被认为是热 key。
 * 只判断“热不热”，不做精确 TopK；key 空间是视频数量级，内存可控。
 */
@Component
public class HotKeyDetector {
    private final Toggles toggles;
    private volatile Map<String, LongAdder> window = new ConcurrentHashMap<>();
    private volatile Set<String> hot = Set.of();

    public HotKeyDetector(Toggles toggles) {
        this.toggles = toggles;
    }

    public void record(String key) {
        window.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    public boolean isHot(String key) {
        return hot.contains(key);
    }

    public Set<String> hotKeys() {
        return hot;
    }

    @Scheduled(fixedRate = 1000)
    public void rotate() {
        Map<String, LongAdder> last = window;
        window = new ConcurrentHashMap<>();
        int th = toggles.hotKeyThreshold;
        hot = last.entrySet().stream()
                .filter(e -> e.getValue().sum() >= th)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
