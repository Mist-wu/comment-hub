-- comment-hub 表结构。docker compose 首次启动时由 MySQL 自动执行；集成测试也用这份。
CREATE DATABASE IF NOT EXISTS comment_hub DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE comment_hub;

CREATE TABLE IF NOT EXISTS video (
    id            BIGINT       NOT NULL PRIMARY KEY,
    title         VARCHAR(128) NOT NULL,
    -- 由 consumer 维护的冗余计数，避免 COUNT(*)
    comment_count INT          NOT NULL DEFAULT 0,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS comment (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    video_id   BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    content    VARCHAR(500) NOT NULL,
    like_count INT          NOT NULL DEFAULT 0,
    -- 0 待审 1 通过 2 复核 3 拒绝（拒绝的不展示）
    status     TINYINT      NOT NULL DEFAULT 0,
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    -- 游标分页：WHERE video_id = ? AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC
    KEY idx_video_created (video_id, created_at, id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS comment_like (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    comment_id BIGINT      NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    -- 重复点赞靠唯一键挡住：INSERT IGNORE 影响行数为 0 即已点过
    UNIQUE KEY uk_user_comment (user_id, comment_id),
    -- 对账：SELECT COUNT(*) FROM comment_like WHERE comment_id = ?
    KEY idx_comment (comment_id)
) ENGINE = InnoDB;

-- 消费者幂等：同一事件在同一消费者组里只生效一次
CREATE TABLE IF NOT EXISTS consumed_event (
    consumer   VARCHAR(32) NOT NULL,
    event_id   CHAR(36)    NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (consumer, event_id),
    KEY idx_created (created_at)
) ENGINE = InnoDB;

-- 发事件失败时兜底：写入这里，由定时任务重发
CREATE TABLE IF NOT EXISTS event_outbox (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    topic      VARCHAR(64)  NOT NULL,
    msg_key    VARCHAR(64)  NOT NULL,
    payload    TEXT         NOT NULL,
    trace_id   VARCHAR(64),
    created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB;

-- 模型调用记账：每次调用一行，开始时写 started，结束时回填 token、费用、耗时
CREATE TABLE IF NOT EXISTS ai_call (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task              VARCHAR(16)  NOT NULL,         -- moderate / summarize
    provider          VARCHAR(16)  NOT NULL,         -- mock / deepseek
    model             VARCHAR(64)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,         -- started / success / parse_error / error / timeout
    biz_id            BIGINT,
    input_tokens      INT          NOT NULL DEFAULT 0, -- 未命中缓存的输入
    cache_read_tokens INT          NOT NULL DEFAULT 0, -- 命中上游前缀缓存的输入
    output_tokens     INT          NOT NULL DEFAULT 0,
    total_tokens      INT          NOT NULL DEFAULT 0,
    -- 费用（人民币，单位：百万分之一元），按配置的单价计算；单价未配置时为 NULL，不估算
    cost_micro_cny    BIGINT,
    duration_ms       INT,
    error             VARCHAR(255),
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at       DATETIME(3),
    KEY idx_provider_created (provider, created_at)
) ENGINE = InnoDB;

-- 审核质量评估：人工标注的小样本
CREATE TABLE IF NOT EXISTS moderation_gold (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    content     VARCHAR(500) NOT NULL,
    human_label VARCHAR(8)   NOT NULL,   -- PASS / REVIEW / REJECT
    model_label VARCHAR(8),
    model_conf  DOUBLE,
    rule_label  VARCHAR(8)
) ENGINE = InnoDB;
