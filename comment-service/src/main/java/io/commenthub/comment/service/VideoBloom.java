package io.commenthub.comment.service;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import io.commenthub.comment.mapper.VideoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 进程内布隆过滤器，装全部视频 ID。1 万个视频、误判率 0.1% 时只占约 18KB。
 * 视频不是通过本服务创建的，定时全量重建即可；如果视频会实时新增，需要在新增时 put。
 * 布隆过滤器“说不在就一定不在”，所以只用它拦截，不用它确认存在。
 */
@Component
public class VideoBloom {
    private static final Logger log = LoggerFactory.getLogger(VideoBloom.class);
    private final VideoMapper videos;
    private volatile BloomFilter<Long> filter;

    public VideoBloom(VideoMapper videos) {
        this.videos = videos;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 600_000, initialDelay = 600_000)
    public void rebuild() {
        List<Long> ids = videos.allIds();
        BloomFilter<Long> f = BloomFilter.create(Funnels.longFunnel(), Math.max(ids.size(), 1000) * 2L, 0.001);
        ids.forEach(f::put);
        filter = f;
        log.info("video bloom rebuilt, ids={}", ids.size());
    }

    /** 还没建好时放行，宁可穿透也不误杀。 */
    public boolean mightExist(long videoId) {
        BloomFilter<Long> f = filter;
        return f == null || f.mightContain(videoId);
    }
}
