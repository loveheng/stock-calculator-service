-- 1. 快讯/文章内容主表（移除了 reading_num, comment_num, share_num 等即时变动字段）
CREATE TABLE IF NOT EXISTS cls_article (
    id BIGINT PRIMARY KEY,                      -- 文章ID (唯一去重)
    type INT NOT NULL DEFAULT -1,               -- 文章类型
    title VARCHAR(500),                         -- 标题
    brief TEXT,                                 -- 摘要
    content TEXT,                               -- 正文详情
    ctime BIGINT NOT NULL,                      -- 原始发布时间戳 (秒)
    created_at TIMESTAMPTZ GENERATED ALWAYS AS (to_timestamp(ctime)) STORED, -- 索引友好的时间格式
    author VARCHAR(100) DEFAULT '',             -- 来源/作者 (如 CCTV国际时讯)
    level VARCHAR(10) DEFAULT 'C',              -- 消息评级 (A/B/C)

    -- 稀疏/多媒体数据
    images JSONB,                               -- 图片URL列表: ["https://..."]
    audio_url JSONB,                            -- 音频URL列表: ["https://..."]

    fetched_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP -- 爬取时间
);

-- 主表时间索引（新闻流按时间倒序查询核心）
CREATE INDEX IF NOT EXISTS idx_cls_article_ctime ON cls_article (ctime DESC);
CREATE INDEX IF NOT EXISTS idx_cls_article_level ON cls_article (level);


-- 2. 关联题材/板块表（去掉 attention_num 等即时热度值）
CREATE TABLE IF NOT EXISTS cls_article_subject (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES cls_article(id) ON DELETE CASCADE,
    subject_id INT NOT NULL,                    -- 题材ID (如 1321-人工智能)
    subject_name VARCHAR(100) NOT NULL,         -- 题材名称
    plate_id INT DEFAULT 0,                     -- 板块ID
    channel VARCHAR(50),                        -- 渠道 (cls, stib)

    CONSTRAINT uq_article_subject UNIQUE (article_id, subject_id)
);

CREATE INDEX IF NOT EXISTS idx_article_subject_id ON cls_article_subject (subject_id);
CREATE INDEX IF NOT EXISTS idx_article_subject_article_id ON cls_article_subject (article_id);


-- 3. 关联股票快照表（保留发布快讯那一刻的股价与涨跌幅快照）
CREATE TABLE IF NOT EXISTS cls_article_stock (
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES cls_article(id) ON DELETE CASCADE,
    stock_id VARCHAR(30) NOT NULL,              -- 股票代码 (如 sh603067, 920138.BJ)
    name VARCHAR(100) NOT NULL,                 -- 股票名称
    last_price NUMERIC(10, 4),                  -- 触发快讯时的瞬时价
    rise_range NUMERIC(8, 4),                   -- 触发快讯时的涨跌幅(%)
    is_stib BOOLEAN DEFAULT FALSE,              -- 是否科创板

    CONSTRAINT uq_article_stock UNIQUE (article_id, stock_id)
);

CREATE INDEX IF NOT EXISTS idx_article_stock_code ON cls_article_stock (stock_id);
CREATE INDEX IF NOT EXISTS idx_article_stock_article_id ON cls_article_stock (article_id);
