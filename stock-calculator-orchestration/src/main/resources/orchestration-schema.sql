-- orchestration 三表（docs/architecture/agent-orchestration.md §六；stock_mcp 库分表，D6）
-- orchestration 模块 spring.sql.init 每次启动幂等执行；回滚 spring.sql.init = 停用
-- vector 扩展 per-database 生效：新库必须自建（mcp-schema.sql 同款惯例）
CREATE EXTENSION IF NOT EXISTS vector;

-- 6.1 工具注册表：mcp 工具与 main REST 接口统一描述（D5），规划 prompt 直接输入
CREATE TABLE IF NOT EXISTS tool_registry (
    tool_name VARCHAR(128) PRIMARY KEY,
    kind VARCHAR(8) NOT NULL,
    endpoint TEXT NOT NULL,
    param_schema JSONB NOT NULL DEFAULT '{}',
    description TEXT NOT NULL,
    domain VARCHAR(32) NOT NULL,
    risk VARCHAR(8) NOT NULL DEFAULT 'read',
    output_policy VARCHAR(16) NOT NULL DEFAULT 'keep_head',
    execution_mode VARCHAR(16) NOT NULL DEFAULT 'sync',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_tool_registry_domain ON tool_registry (domain) WHERE enabled;

-- 6.2 规划路径：复用锚 = 规范化意图向量（D3），status 流水线 draft → candidate → verified / deprecated（D10）
CREATE TABLE IF NOT EXISTS plan (
    id BIGSERIAL PRIMARY KEY,
    intent_text TEXT NOT NULL,
    intent_domains TEXT[] NOT NULL DEFAULT '{}',
    intent_embedding vector(1024),
    param_schema JSONB NOT NULL DEFAULT '{}',
    plan_dag JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    needs_review BOOLEAN NOT NULL DEFAULT FALSE,
    use_count BIGINT NOT NULL DEFAULT 0,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_plan_status ON plan (status);
-- HNSW cosine（§6.2：Filtered Vector Search 前置 status/domain 标量过滤，向量索引只加速排序段）
CREATE INDEX IF NOT EXISTS idx_plan_hnsw ON plan USING hnsw (intent_embedding vector_cosine_ops);

-- 6.3 执行实例：plan_dag_snapshot 创建时冗余（版本漂移防护，§八），node_states 按 output_policy 瘦身
CREATE TABLE IF NOT EXISTS task_instance (
    id BIGSERIAL PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    plan_dag_snapshot JSONB NOT NULL,
    trace_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    params JSONB NOT NULL DEFAULT '{}',
    node_states JSONB NOT NULL DEFAULT '{}',
    status VARCHAR(16) NOT NULL DEFAULT 'running',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_task_instance_trace UNIQUE (trace_id)
);
CREATE INDEX IF NOT EXISTS idx_task_instance_status ON task_instance (status);
CREATE INDEX IF NOT EXISTS idx_task_instance_plan ON task_instance (plan_id);

-- 步 6-2 mq_wait 挂起支撑：挂起截止时间（Zombie 防御，@Scheduled 扫描超时置 timeout）
ALTER TABLE task_instance ADD COLUMN IF NOT EXISTS wait_deadline TIMESTAMP;
