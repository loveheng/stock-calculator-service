-- stock_mcp 独立库初始化（与主库 schema.sql 无关——主库文件在 main 模块 resources；mcp 模块 spring.sql.init 每次启动执行，全部幂等）
-- vector 扩展 per-database 生效：新库必须自建（主库已建不影响本库）
CREATE EXTENSION IF NOT EXISTS vector;

-- 书目元数据（检索出处拼接源；D2 独立库 / D4 自建表，见 docs/mcp/design.md §5）
CREATE TABLE IF NOT EXISTS kb_book (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    author VARCHAR(128),
    category VARCHAR(64),
    difficulty VARCHAR(16),
    reading_order INT,
    source_id BIGINT,
    embedding_model VARCHAR(64) NOT NULL DEFAULT '@cf/baai/bge-m3',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_kb_book_title UNIQUE (title)
);

-- 书块（向量 1024 维 bge-m3 + HNSW cosine；content_hash/model 留档沿用 R9 惯例）
-- 无物理外键：与主库惯例一致，引用完整性由应用层保证
CREATE TABLE IF NOT EXISTS kb_chunk (
    id BIGSERIAL PRIMARY KEY,
    book_id BIGINT NOT NULL,
    chapter_path VARCHAR(512),
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64),
    published_at TIMESTAMP,
    model VARCHAR(64) NOT NULL DEFAULT '@cf/baai/bge-m3',
    embedding vector(1024),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_book ON kb_chunk (book_id, chunk_index);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_hnsw ON kb_chunk USING hnsw (embedding vector_cosine_ops);

-- 行情日线落库（D9，2026-09-20：增量同步 + 全量分析读取；前复权口径，除权漂移靠重叠重灌+手动 resync 修复）
CREATE TABLE IF NOT EXISTS quote_daily (
    id BIGSERIAL PRIMARY KEY,
    stock_id VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    open NUMERIC(12,4) NOT NULL,
    high NUMERIC(12,4) NOT NULL,
    low NUMERIC(12,4) NOT NULL,
    close NUMERIC(12,4) NOT NULL,
    volume NUMERIC(18,2) NOT NULL,
    amount NUMERIC(18,2),
    amplitude NUMERIC(8,4),
    pct_chg NUMERIC(8,4),
    chg NUMERIC(10,4),
    turnover NUMERIC(8,4),
    adjust VARCHAR(8) NOT NULL DEFAULT 'qfq',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_quote_daily_sid_date UNIQUE (stock_id, trade_date)
);
-- 存量库补列（CREATE IF NOT EXISTS 不补列；2026-09-20 日线字段扩展至东财全 11 字段）
ALTER TABLE quote_daily ADD COLUMN IF NOT EXISTS amount NUMERIC(18,2);
ALTER TABLE quote_daily ADD COLUMN IF NOT EXISTS amplitude NUMERIC(8,4);
ALTER TABLE quote_daily ADD COLUMN IF NOT EXISTS pct_chg NUMERIC(8,4);
ALTER TABLE quote_daily ADD COLUMN IF NOT EXISTS chg NUMERIC(10,4);
ALTER TABLE quote_daily ADD COLUMN IF NOT EXISTS turnover NUMERIC(8,4);

-- 订阅源注册表（mcp-blogger-kb M1：博主观点库；text=txt 文件路径，rss=feed URL 拉取属 M1b）
CREATE TABLE IF NOT EXISTS kb_source (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    location VARCHAR(512),
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    last_ingested_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_kb_source_name UNIQUE (name)
);
-- 存量库补列（CREATE IF NOT EXISTS 不补列；M1 观点时效与源关联）
ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS published_at TIMESTAMP;
ALTER TABLE kb_book ADD COLUMN IF NOT EXISTS source_id BIGINT;
-- 存量库补列（M2 人格卡留档：生成模型 + 生成日期，照 embedding_model 纪律）
ALTER TABLE kb_book ADD COLUMN IF NOT EXISTS persona_model VARCHAR(64);
ALTER TABLE kb_book ADD COLUMN IF NOT EXISTS persona_generated_at TIMESTAMP;
