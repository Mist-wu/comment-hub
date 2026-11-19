package io.commenthub.comment.cache;

/** 等互斥锁超时：缓存正在重建且重建很慢。接口层转成 503 + Retry-After。 */
public class CacheBusyException extends RuntimeException {
    public CacheBusyException(String key) {
        super("cache rebuilding, retry later: " + key);
    }
}
