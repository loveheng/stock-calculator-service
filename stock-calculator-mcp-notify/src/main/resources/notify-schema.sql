-- notify 提醒服务表结构（stock_mcp 独立库分表，N3；notify 模块 spring.sql.init 每次启动执行，全部幂等）
-- reminder 生命周期状态机单向：active → done / cancelled（N6，无原地 update）

-- 提醒登记表（docs/notify/design.md §4.1）
CREATE TABLE IF NOT EXISTS reminder (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    trigger_spec JSONB NOT NULL,
    action JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    min_interval_ms BIGINT,
    suppress_until TIMESTAMP,
    fired_at TIMESTAMP,
    fire_count INT NOT NULL DEFAULT 0,
    suppressed_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_reminder_user_active ON reminder (user_id, status);
CREATE INDEX IF NOT EXISTS idx_reminder_status_trigger ON reminder (status, trigger_type);

-- 能力请求在途关联表（§六：临时关联 + 超时兜底，deadline 到未回流降级通知）
CREATE TABLE IF NOT EXISTS capability_request (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    reminder_id BIGINT NOT NULL,
    deadline TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_capability_request_trace UNIQUE (trace_id)
);
CREATE INDEX IF NOT EXISTS idx_capability_request_deadline ON capability_request (deadline);
