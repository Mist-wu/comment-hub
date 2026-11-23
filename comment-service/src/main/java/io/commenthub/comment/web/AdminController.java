package io.commenthub.comment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.commenthub.comment.cache.PageCache;
import io.commenthub.comment.config.Toggles;
import io.commenthub.comment.service.HotRankJob;
import io.commenthub.comment.service.LikeReconciler;
import io.commenthub.comment.service.VideoBloom;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 只给压测脚本和复盘用，生产环境应放在内网或加鉴权。 */
@RestController
@RequestMapping("/admin")
public class AdminController {
    private final Toggles toggles;
    private final ObjectMapper om;
    private final HotRankJob hotRankJob;
    private final LikeReconciler reconciler;
    private final PageCache pageCache;
    private final VideoBloom bloom;

    public AdminController(Toggles toggles, ObjectMapper om, HotRankJob hotRankJob, LikeReconciler reconciler, PageCache pageCache,
                           VideoBloom bloom) {
        this.toggles = toggles;
        this.om = om;
        this.hotRankJob = hotRankJob;
        this.reconciler = reconciler;
        this.pageCache = pageCache;
        this.bloom = bloom;
    }

    /** 灌完数据后调一次，不用等定时重建 */
    @PostMapping("/bloom/rebuild")
    public Map<String, Object> rebuildBloom() {
        return Map.of("videos", bloom.rebuild());
    }

    @GetMapping("/toggles")
    public Toggles get() {
        return toggles;
    }

    /** 局部更新：只传要改的字段，例如 {"breakdown":"NONE","ttlJitterSeconds":0} */
    @PostMapping("/toggles")
    public Toggles set(@RequestBody Map<String, Object> patch) throws Exception {
        return om.updateValue(toggles, patch);
    }

    @PostMapping("/hot-rank/rebuild")
    public Map<String, Object> rebuildHot() {
        return Map.of("videos", hotRankJob.rebuild());
    }

    /** 把指定前缀的缓存 key 统一设成 N 秒后过期，用来复现击穿 / 雪崩。 */
    @PostMapping("/cache/expire")
    public Map<String, Object> expire(@RequestParam(defaultValue = "cm:") String prefix,
                                      @RequestParam(defaultValue = "1") int seconds) {
        return Map.of("keys", pageCache.expireAll(prefix, seconds));
    }

    @PostMapping("/cache/flush")
    public Map<String, Object> flush(@RequestParam(defaultValue = "cm:") String prefix) {
        return Map.of("keys", pageCache.deleteAll(prefix));
    }

    /** 点赞数对账：以 comment_like 行数为准，比较 MySQL like_count 与 Redis 计数。 */
    @PostMapping("/reconcile/likes")
    public Map<String, Object> reconcile(@RequestParam(defaultValue = "false") boolean fix,
                                         @RequestParam(defaultValue = "1000") int limit) {
        return reconciler.reconcileDirty(limit, fix);
    }
}
