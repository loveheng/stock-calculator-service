-- public.cls_article definition

-- Drop table

-- DROP TABLE public.cls_article;

CREATE TABLE IF NOT EXISTS public.cls_article (
	id int8 NOT NULL,
	"type" int4 DEFAULT -1 NOT NULL,
	title varchar(500) NULL,
	brief text NULL,
	"content" text NULL,
	ctime int8 NOT NULL,
	created_at timestamptz GENERATED ALWAYS AS (to_timestamp(ctime::double precision)) STORED,
	author varchar(100) DEFAULT ''::character varying NULL,
	"level" varchar(10) DEFAULT 'C'::character varying NULL,
	images jsonb NULL,
	audio_url jsonb NULL,
	fetched_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	CONSTRAINT cls_article_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_cls_article_ctime ON public.cls_article USING btree (ctime DESC);
CREATE INDEX IF NOT EXISTS idx_cls_article_level ON public.cls_article USING btree (level);


-- public.cls_article_stock definition

-- Drop table

-- DROP TABLE public.cls_article_stock;

CREATE TABLE if not exists public.cls_article_stock (
	id bigserial NOT NULL,
	article_id int8 NOT NULL,
	stock_id varchar(32) NOT NULL,
	last_price numeric(12, 3) NULL,
	rise_range numeric(8, 2) NULL,
	created_at timestamp DEFAULT CURRENT_TIMESTAMP NULL,
	CONSTRAINT cls_article_stock_pkey PRIMARY KEY (id),
	CONSTRAINT uk_article_stock UNIQUE (article_id, stock_id)
);

-- stock_id 不是复合唯一索引的第一列，单独查询 stock_id 时保留该索引有明显收益
CREATE INDEX if not exists idx_cas_stock_id ON public.cls_article_stock USING btree (stock_id);


-- public.cls_article_subject definition

-- Drop table

-- DROP TABLE public.cls_article_subject;

CREATE TABLE if not exists public.cls_article_subject (
	id bigserial NOT NULL,
	article_id int8 NOT NULL,
	subject_id int8 NOT NULL,
	created_at timestamp DEFAULT CURRENT_TIMESTAMP NULL,
	CONSTRAINT cls_article_subject_pkey PRIMARY KEY (id),
	CONSTRAINT uk_article_subject UNIQUE (article_id, subject_id)
);

CREATE INDEX if not exists idx_csub_subject_id ON public.cls_article_subject USING btree (subject_id);


-- public.cls_subject definition

-- Drop table

-- DROP TABLE public.cls_subject;

CREATE TABLE if not exists public.cls_subject (
	subject_id int8 NOT NULL,
	subject_name varchar(128) NOT NULL,
	plate_id int8 NULL,
	channel varchar(64) NULL,
	created_at timestamp DEFAULT CURRENT_TIMESTAMP NULL,
	updated_at timestamp DEFAULT CURRENT_TIMESTAMP NULL,
	CONSTRAINT cls_subject_pkey PRIMARY KEY (subject_id)
);


-- public.stock definition

-- Drop table

-- DROP TABLE public.stock;

CREATE TABLE if not exists public.stock (
	stock_id varchar(32) NOT NULL,
	"name" varchar(64) NOT NULL,
	old_name varchar(64) NOT NULL,
	is_stib bool DEFAULT false NULL,
	created_at timestamp DEFAULT CURRENT_TIMESTAMP NULL,
	updated_at timestamp DEFAULT CURRENT_TIMESTAMP NULL,
	CONSTRAINT stock_pkey PRIMARY KEY (stock_id)
);

-- ============================================================
-- E2EE 用户服务（docs/e2ee-auth/design.md §D.3）
-- IF NOT EXISTS 幂等，无需停机；gen_random_uuid() 为 PG13+ 内置
-- ============================================================
CREATE TABLE IF NOT EXISTS public.users (
	id uuid NOT NULL,
	email varchar(255) NOT NULL,
	password_hash varchar(60) NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	CONSTRAINT users_pkey PRIMARY KEY (id),
	CONSTRAINT users_email_key UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS public.user_profiles (
	id uuid NOT NULL,
	password_payload text NOT NULL,
	password_iv varchar(32) NOT NULL,
	recovery_payload text NOT NULL,
	recovery_iv varchar(32) NOT NULL,
	updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	CONSTRAINT user_profiles_pkey PRIMARY KEY (id),
	CONSTRAINT user_profiles_id_fkey FOREIGN KEY (id) REFERENCES public.users (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS public.auth_sessions (
	id uuid NOT NULL DEFAULT gen_random_uuid(),
	user_id uuid NOT NULL,
	token_hash varchar(64) NOT NULL,
	scope varchar(16) NOT NULL DEFAULT 'full',
	expires_at timestamptz NOT NULL,
	last_seen_at timestamptz NULL,
	revoked_at timestamptz NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	CONSTRAINT auth_sessions_pkey PRIMARY KEY (id),
	CONSTRAINT auth_sessions_token_hash_key UNIQUE (token_hash)
);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_user_id ON public.auth_sessions USING btree (user_id);
CREATE INDEX IF NOT EXISTS idx_auth_sessions_expires_at ON public.auth_sessions USING btree (expires_at);

CREATE TABLE IF NOT EXISTS public.otp_codes (
	id bigserial NOT NULL,
	email varchar(255) NOT NULL,
	code_hash varchar(64) NOT NULL,
	purpose varchar(16) NOT NULL DEFAULT 'recovery',
	attempts int4 NOT NULL DEFAULT 0,
	expires_at timestamptz NOT NULL,
	consumed_at timestamptz NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
	CONSTRAINT otp_codes_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_otp_codes_email ON public.otp_codes USING btree (email);

-- ============================================================
-- Copilot AI 聊天表（schema 新增，P1）
-- ============================================================

CREATE TABLE IF NOT EXISTS ai_chat_session (
    id                BIGSERIAL PRIMARY KEY,
    user_id           VARCHAR(64)  NOT NULL,
    scope_id          VARCHAR(100) NOT NULL,
    title             VARCHAR(100) NOT NULL,
    last_message_at   BIGINT,
    ctime             BIGINT       NOT NULL,
    deleted_at        BIGINT       DEFAULT 0,
    -- 记忆提炼水位与在途锁（docs/copilot/memory-profile.md §四）
    last_memory_extracted_message_id BIGINT   DEFAULT 0,
    memory_extract_dispatched_at     TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_chat_session_user_scope
    ON ai_chat_session(user_id, scope_id) WHERE deleted_at = 0;

CREATE TABLE IF NOT EXISTS ai_chat_message (
    id                BIGSERIAL PRIMARY KEY,
    session_id        BIGINT       NOT NULL,
    role              VARCHAR(10)  NOT NULL,
    content           TEXT         NOT NULL,
    client_message_id VARCHAR(40),
    status            VARCHAR(20)  DEFAULT 'ok',
    context_overview  VARCHAR(255),
    time_anchor       VARCHAR(100),
    channel           VARCHAR(30),
    model             VARCHAR(50),
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    ctime             BIGINT       NOT NULL,
    deleted_at        BIGINT       DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_chat_message_client_id
    ON ai_chat_message(client_message_id) WHERE client_message_id IS NOT NULL AND deleted_at = 0;
CREATE INDEX IF NOT EXISTS idx_ai_chat_message_session_id
    ON ai_chat_message(session_id, id DESC) WHERE deleted_at = 0;
CREATE INDEX IF NOT EXISTS idx_ai_chat_message_cid
    ON ai_chat_message(client_message_id) WHERE client_message_id IS NOT NULL;

-- =====================================================================
-- Copilot 记忆固化与用户画像（docs/copilot/memory-profile.md §四）
-- =====================================================================

CREATE TABLE IF NOT EXISTS copilot_memory (
    id                  BIGSERIAL     PRIMARY KEY,
    user_id             VARCHAR(64)   NOT NULL,
    session_id          BIGINT        NOT NULL,
    topic               VARCHAR(64)   NOT NULL,
    content             VARCHAR(2000) NOT NULL,
    source_message_ids  JSONB,
    status              VARCHAR(10)   DEFAULT 'active',
    pinned              BOOLEAN       DEFAULT FALSE,
    ctime               BIGINT        NOT NULL,
    created_at          TIMESTAMPTZ   DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ   DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_copilot_memory_session_topic UNIQUE (session_id, topic)
);
-- user_id 对齐 ai_chat_session.user_id（auth 用户 UUID 文本，user_custom_stat 同款修正先例）；
-- 窗口内 (session_id, topic) 唯一 = 提炼即改写；跨窗口不去重（窗口即用户自分类主题）
CREATE INDEX IF NOT EXISTS idx_copilot_memory_user_topic_ctime
    ON copilot_memory(user_id, topic, ctime DESC);
CREATE INDEX IF NOT EXISTS idx_copilot_memory_user_updated
    ON copilot_memory(user_id, updated_at);

CREATE TABLE IF NOT EXISTS copilot_user_profile (
    user_id                    VARCHAR(64)  PRIMARY KEY,
    profile                    JSONB,
    profile_version            INTEGER      DEFAULT 0 NOT NULL,
    blacklisted_features       JSONB        DEFAULT '[]',
    last_profile_extracted_at  TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ  DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMPTZ  DEFAULT CURRENT_TIMESTAMP
);

-- 记忆链存量库幂等升级（新库由上方 CREATE 直接带列，以下 ALTER 为 no-op）
ALTER TABLE public.ai_chat_session ADD COLUMN IF NOT EXISTS last_memory_extracted_message_id bigint DEFAULT 0 NOT NULL;
ALTER TABLE public.ai_chat_session ADD COLUMN IF NOT EXISTS memory_extract_dispatched_at timestamptz;

-- =====================================================================
-- Copilot Prompt 模版（P1 配置驱动路由）：DB 为唯一准源，启动由 CopilotPromptSync
-- 全量镜像至 Redis（copilot:prompt:{tag}），运行时解析器只读 Redis。
-- 与实体 @UniqueConstraint / @Index 名称严格对齐（Hibernate ddl-auto=none，建表仅靠本脚本）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS copilot_prompt_template (
    id      BIGSERIAL PRIMARY KEY,
    tag     VARCHAR(100) NOT NULL,
    content TEXT         NOT NULL,
    ctime   BIGINT       NOT NULL,
    mtime   BIGINT       NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_copilot_prompt_template_tag
    ON copilot_prompt_template(tag);

CREATE TABLE IF NOT EXISTS copilot_prompt_template_history (
    id        BIGSERIAL PRIMARY KEY,
    tag       VARCHAR(100) NOT NULL,
    rev       INTEGER      NOT NULL,
    content   TEXT         NOT NULL,
    operation VARCHAR(16)  NOT NULL,
    ctime     BIGINT       NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_cpt_history_tag_rev
    ON copilot_prompt_template_history(tag, rev);
CREATE INDEX IF NOT EXISTS idx_cpt_history_tag_ctime
    ON copilot_prompt_template_history(tag, ctime DESC);


-- ============================================================
-- 服务端密文同步（docs/server-sync/design.md §3 / D5 / D8 / D10 / D11 / E1 / E7）
-- ============================================================

-- DROP TABLE IF EXISTS public.user_sync_history;
-- DROP TABLE IF EXISTS public.user_sync_data;

CREATE TABLE IF NOT EXISTS public.user_sync_data (
	user_id           varchar(64) NOT NULL,
	encrypted_payload text NOT NULL,
	version           int8 NOT NULL,
	payload_hash      varchar(64) NULL,
	payload_bytes     int4 NOT NULL,
	created_at        timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at        timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT user_sync_data_pkey PRIMARY KEY (user_id)
);

CREATE TABLE IF NOT EXISTS public.user_sync_history (
	id                bigserial NOT NULL,
	user_id           varchar(64) NOT NULL,
	version           int8 NOT NULL,
	encrypted_payload text NOT NULL,
	payload_bytes     int4 NOT NULL,
	created_at        timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT user_sync_history_pkey PRIMARY KEY (id),
	CONSTRAINT uq_user_sync_history UNIQUE (user_id, version)
);

-- 回滚：DROP TABLE IF EXISTS public.user_sync_history; DROP TABLE IF EXISTS public.user_sync_data;
-- 历史裁剪规则（service 层）：成功写入 newVersion 后 DELETE version < newVersion - 5，保留恰 5 份（D8）
-- 历史唯一冲突由 INSERT … ON CONFLICT DO NOTHING 吸收（E7，整库回滚场景）

-- ============================================================
-- 自定义统计定义持久化（D17 契约：docs/custom-stats-server-sync.md）
-- 明文 JSONB 直存（非 E2EE 快照通道，登录 Bearer 即可用）；LWW 由客户端仲裁，
-- 服务端不参与版本冲突处理；payload 内业务时间戳原样存取，服务端绝不改写
-- ============================================================
CREATE TABLE IF NOT EXISTS public.user_custom_stat (
	id                bigserial NOT NULL,
	user_id           varchar(64) NOT NULL,
	def_id            varchar(64) NOT NULL,
	payload           jsonb NOT NULL,
	updated_at_client varchar(40) NOT NULL,
	created_at        timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at        timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT user_custom_stat_pkey PRIMARY KEY (id),
	CONSTRAINT uq_user_custom_stat_user_def UNIQUE (user_id, def_id)
);
CREATE INDEX IF NOT EXISTS idx_user_custom_stat_user ON public.user_custom_stat USING btree (user_id);

-- 回滚：DROP TABLE IF EXISTS public.user_custom_stat;
-- 注：user_id 为 varchar(64)（auth 用户 UUID 文本）；D17 文档示例写 BIGINT 系前端笔误，
--     对齐 user_sync_data / ai_chat_session 既有先例（E1）

-- ============================================================
-- cls_article 向量化基础设施（docs/ai-pipeline/cls-article-vector.md §3.2）
-- pgvector 扩展 + 状态表；vector_store 主表由 Spring AI PgVectorStore
-- initialize-schema 自动建表（vector(1024) + HNSW cosine 索引），不手写 DDL
-- ============================================================
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS public.cls_article_embedding (
	article_id int8 NOT NULL,
	status varchar(10) DEFAULT 'PENDING' NOT NULL,
	fail_count int4 DEFAULT 0 NOT NULL,
	model varchar(64) DEFAULT '@cf/baai/bge-m3' NOT NULL,
	content_hash varchar(64) NULL,
	error varchar(500) NULL,
	embedded_at timestamptz NULL,
	created_at timestamp DEFAULT CURRENT_TIMESTAMP NULL,
	updated_at timestamp DEFAULT CURRENT_TIMESTAMP NULL,
	CONSTRAINT cls_article_embedding_pkey PRIMARY KEY (article_id)
);

CREATE INDEX IF NOT EXISTS idx_cae_status ON public.cls_article_embedding USING btree (status);

-- P1 追加：永久失败计次列（幂等，存量库补列；spring.sql.init 每次启动执行不报错）
ALTER TABLE public.cls_article_embedding ADD COLUMN IF NOT EXISTS fail_count int4 DEFAULT 0 NOT NULL;

-- 回滚：DROP TABLE IF EXISTS public.cls_article_embedding;
-- 注：与 cls_article 无物理外键（现库惯例，引用完整性由应用层保证）；
--     三态语义：不存在行或 PENDING=未处理，DONE=已生成，FAILED=永久失败终态（fail_count 计次）；
--     model 留档支撑将来换模型全量重嵌（R9）

-- ============================================================
-- announcement 域三表（docs/ai-pipeline/announcement-rag.md §3）
-- 主表：元数据 + 状态机游标（PENDING/DONE/FAILED）；不存 PDF、不存正文（D5/D7）
-- ============================================================
CREATE TABLE IF NOT EXISTS public.announcement (
	id bigserial NOT NULL,
	announcement_id text NOT NULL,
	title text NOT NULL,
	adjunct_url text NULL,
	se_date date NULL,
	sec_code varchar(32) NULL,
	sec_name varchar(64) NULL,
	status varchar(16) DEFAULT 'PENDING' NOT NULL,
	status_reason text NULL,
	fail_count int4 DEFAULT 0 NOT NULL,
	summary text NULL,
	created_at timestamptz DEFAULT now() NOT NULL,
	updated_at timestamptz DEFAULT now() NOT NULL,
	CONSTRAINT announcement_pkey PRIMARY KEY (id),
	CONSTRAINT uk_announcement_announcement_id UNIQUE (announcement_id)
);

CREATE INDEX IF NOT EXISTS idx_announcement_sec ON public.announcement USING btree (sec_code, se_date);

-- PENDING 游标消费专用部分索引（仅 DDL 可表达；实体侧用普通方法查询）
CREATE INDEX IF NOT EXISTS idx_announcement_pending ON public.announcement USING btree (se_date DESC, id DESC) WHERE status = 'PENDING';

-- 1:1 溯源表：结构树/切片选择 JSONB（重放重建依据，D5），无正文大列
CREATE TABLE IF NOT EXISTS public.announcement_content (
	announcement_id int8 NOT NULL,
	extractor_version text NULL,
	char_count int4 NULL,
	page_count int4 NULL,
	structure_json jsonb NULL,
	selection_json jsonb NULL,
	CONSTRAINT announcement_content_pkey PRIMARY KEY (announcement_id),
	CONSTRAINT fk_ann_content_announcement FOREIGN KEY (announcement_id) REFERENCES public.announcement(id) ON DELETE CASCADE
);

-- 订阅表：用户×股票权限；订阅即触发抓取（SubscriptionCreatedEvent 与抓取逻辑分离，D13）
CREATE TABLE IF NOT EXISTS public.announcement_subscription (
	id bigserial NOT NULL,
	user_id uuid NOT NULL,
	stock_id varchar(32) NOT NULL,
	org_id varchar(64) NULL,
	created_at timestamptz DEFAULT now() NOT NULL,
	CONSTRAINT announcement_subscription_pkey PRIMARY KEY (id),
	CONSTRAINT uk_ann_sub_user_stock UNIQUE (user_id, stock_id),
	CONSTRAINT fk_ann_sub_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_ann_sub_stock ON public.announcement_subscription USING btree (stock_id);

-- 回滚：DROP TABLE IF EXISTS public.announcement_subscription;
--       DROP TABLE IF EXISTS public.announcement_content;
--       DROP TABLE IF EXISTS public.announcement;
-- 注：三表均幂等建表；announcement_content 对主表物理外键级联（设计 §3）；
--     subscription.org_id 可空（订阅时未必已知，采集期 topSearch 回填）

-- 常态拉取自循环控制面（docs/architecture/pull-loop-unification.md §3，monitor 域）：
-- 配置表为调速/停启事实源（data.sql 播种，看门狗周期性快照下发 data）；
-- 心跳表为判活依据（last_renew_time 超期 → 补种）与仪表盘口径。
CREATE TABLE IF NOT EXISTS public.pull_task_config (
	task_code varchar(64) NOT NULL,
	enabled boolean DEFAULT true NOT NULL,
	ttl_ms int8 DEFAULT 480000 NOT NULL,
	-- 日历型定时任务扩展（docs/architecture/pull-loop-unification.md §8，L8-L13）：
	-- schedule_mode=CALENDAR 时 cron_expression/timezone/next_expected_time 生效，
	-- ttl_ms 不参与日历调度（哨兵 0）；LOOP 行为与此四列无关
	schedule_mode varchar(16) DEFAULT 'LOOP' NOT NULL,
	cron_expression varchar(64),
	timezone varchar(64) DEFAULT 'Asia/Shanghai' NOT NULL,
	next_expected_time timestamptz,
	updated_at timestamptz DEFAULT now() NOT NULL,
	CONSTRAINT pull_task_config_pkey PRIMARY KEY (task_code)
);

-- 存量库幂等升级（新库由上方 CREATE 直接带列，以下 ALTER 为 no-op）
ALTER TABLE public.pull_task_config ADD COLUMN IF NOT EXISTS schedule_mode varchar(16) DEFAULT 'LOOP' NOT NULL;
ALTER TABLE public.pull_task_config ADD COLUMN IF NOT EXISTS cron_expression varchar(64);
ALTER TABLE public.pull_task_config ADD COLUMN IF NOT EXISTS timezone varchar(64) DEFAULT 'Asia/Shanghai' NOT NULL;
ALTER TABLE public.pull_task_config ADD COLUMN IF NOT EXISTS next_expected_time timestamptz;

CREATE TABLE IF NOT EXISTS public.pull_heartbeat (
	task_code varchar(64) NOT NULL,
	last_renew_time timestamptz NOT NULL,
	depth int4 DEFAULT 0 NOT NULL,
	applied_ttl_ms int8 DEFAULT 0 NOT NULL,
	updated_at timestamptz DEFAULT now() NOT NULL,
	CONSTRAINT pull_heartbeat_pkey PRIMARY KEY (task_code)
);

-- 回滚：DROP TABLE IF EXISTS public.pull_heartbeat;
--       DROP TABLE IF EXISTS public.pull_task_config;

-- =====================================================================
-- app_task_config 合表迁移段（任务管理统一 2026-09-18，docs/architecture/pull-loop-unification.md §10）：
-- 存量库将原 main 本地定时任务行并入 pull_task_config（schedule_mode='CALENDAR'、ttl_ms=0 哨兵），
-- 游标（next_expected_time）与 enabled/cron/timezone 随迁，认领语义不变（CalendarTaskClaimScheduler）。
-- 幂等可重跑：原表已 DROP 时本段整体 no-op（新库由 pull_task_config CREATE + data.sql 直接就位）。
CREATE TABLE IF NOT EXISTS public.app_task_config (
	task_code varchar(64) NOT NULL,
	enabled boolean DEFAULT true NOT NULL,
	cron_expression varchar(64),
	timezone varchar(64) DEFAULT 'Asia/Shanghai' NOT NULL,
	next_expected_time timestamptz,
	updated_at timestamptz DEFAULT now() NOT NULL,
	CONSTRAINT app_task_config_pkey PRIMARY KEY (task_code)
);

INSERT INTO public.pull_task_config (task_code, enabled, ttl_ms, schedule_mode, cron_expression, timezone, next_expected_time, updated_at)
SELECT task_code, enabled, 0, 'CALENDAR', cron_expression, timezone, next_expected_time, updated_at
FROM public.app_task_config
ON CONFLICT (task_code) DO NOTHING;

DROP TABLE IF EXISTS public.app_task_config;

-- =====================================================================
-- cls_article 关键词检索 trgm 索引（短查询路由 2026-09-19，docs/news-search）：
-- 短查询（实体名/代码/题材词）路由 content LIKE 精确路径，48万行长文本 seq scan 不可行
-- → pg_trgm GIN（LIKE '%kw%' 可走索引）。幂等：扩展/索引已存在时 no-op；
-- 首次对存量建索引需数十秒（启动期一次性，spring.sql.init 同步执行）。
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_cls_article_content_trgm
	ON public.cls_article USING gin (content gin_trgm_ops);

-- =====================================================================
-- announcement 关键词检索 trgm 索引（公告双路径路由 2026-09-19，docs/news-search）：
-- 关键词精确路径对 title/summary LIKE '%kw%'（sec_code/sec_name 短列走序扫描即可），
-- 与 cls_article 同款 pg_trgm GIN。幂等：索引已存在时 no-op；存量摘要列建索引
-- 耗时与行数线性（启动期一次性，spring.sql.init 同步执行）。
CREATE INDEX IF NOT EXISTS idx_announcement_title_trgm
	ON public.announcement USING gin (title gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_announcement_summary_trgm
	ON public.announcement USING gin (summary gin_trgm_ops);

-- =====================================================================
-- news-kg：《新闻联播》要闻时序知识图谱（2026-09-19，docs/ai-pipeline/cls-news-kg.md §5）。
-- 管线：main 发布 task.kg.extract → data 无状态 LLM 抽取 → result.kg.done 上行
-- → main 证据落库（cls_article_kg 状态机 + kg_evidence 判重）→ 融合进图谱四表。
-- 全部幂等（IF NOT EXISTS / UNIQUE 约束判重），kg_relation.valid_from/valid_to 二期启用。
-- =====================================================================

-- 抽取任务状态表（1 篇 = 1 任务，复刻 cls_article_embedding 范式）
CREATE TABLE IF NOT EXISTS public.cls_article_kg (
	article_id int8 NOT NULL,
	status varchar(10) DEFAULT 'PENDING' NOT NULL,
	status_reason varchar(200) NULL,
	fail_count int4 DEFAULT 0 NOT NULL,
	content_hash varchar(64) NOT NULL,
	extracted_at timestamptz NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT cls_article_kg_pkey PRIMARY KEY (article_id)
);
CREATE INDEX IF NOT EXISTS idx_cls_article_kg_pending
	ON public.cls_article_kg (article_id) WHERE status = 'PENDING';

-- 抽取证据表（一篇文章一版，改稿重抽覆盖；图谱可随时从证据重建）
CREATE TABLE IF NOT EXISTS public.kg_evidence (
	id bigserial NOT NULL,
	article_id int8 NOT NULL,
	content_hash varchar(64) NOT NULL,
	payload jsonb NOT NULL,
	model varchar(100) NULL,
	extracted_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT kg_evidence_pkey PRIMARY KEY (id),
	CONSTRAINT uq_kg_evidence_article UNIQUE (article_id)
);

-- 实体表（字典锚点 + 自由实体；MERGED/canonical_id 二期别名归并启用）
CREATE TABLE IF NOT EXISTS public.kg_entity (
	id bigserial NOT NULL,
	name varchar(200) NOT NULL,
	entity_type varchar(30) NOT NULL,
	anchor_type varchar(20) NULL,
	anchor_id varchar(64) NULL,
	aliases jsonb NULL,
	first_seen_at timestamptz NULL,
	last_seen_at timestamptz NULL,
	mention_count int4 DEFAULT 0 NOT NULL,
	status varchar(10) DEFAULT 'ACTIVE' NOT NULL,
	canonical_id int8 NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT kg_entity_pkey PRIMARY KEY (id),
	CONSTRAINT uq_kg_entity_type_name UNIQUE (entity_type, name)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_kg_entity_anchor
	ON public.kg_entity (anchor_type, anchor_id) WHERE anchor_id IS NOT NULL;

-- 关系边表（一期落库但 valid_from/valid_to 不启用）
CREATE TABLE IF NOT EXISTS public.kg_relation (
	id bigserial NOT NULL,
	subject_entity_id int8 NOT NULL,
	object_entity_id int8 NOT NULL,
	predicate varchar(50) NOT NULL,
	valid_from timestamptz NULL,
	valid_to timestamptz NULL,
	confidence numeric(4,3) NULL,
	evidence_article_id int8 NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT kg_relation_pkey PRIMARY KEY (id),
	CONSTRAINT uq_kg_relation
		UNIQUE (subject_entity_id, object_entity_id, predicate, evidence_article_id)
);
CREATE INDEX IF NOT EXISTS idx_kg_relation_subject ON public.kg_relation (subject_entity_id);
CREATE INDEX IF NOT EXISTS idx_kg_relation_object ON public.kg_relation (object_entity_id);

-- 事件表（一期时序主体：事件时间线）
CREATE TABLE IF NOT EXISTS public.kg_event (
	id bigserial NOT NULL,
	article_id int8 NOT NULL,
	event_time timestamptz NULL,
	event_time_text varchar(100) NULL,
	title varchar(300) NOT NULL,
	detail text NULL,
	event_type varchar(30) NULL,
	content_hash varchar(64) NOT NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT kg_event_pkey PRIMARY KEY (id),
	CONSTRAINT uq_kg_event_article_hash UNIQUE (article_id, content_hash)
);
CREATE INDEX IF NOT EXISTS idx_kg_event_time ON public.kg_event (event_time);

-- 事件-实体关联
CREATE TABLE IF NOT EXISTS public.kg_event_entity (
	id bigserial NOT NULL,
	event_id int8 NOT NULL,
	entity_id int8 NOT NULL,
	role varchar(30) NULL,
	CONSTRAINT kg_event_entity_pkey PRIMARY KEY (id),
	CONSTRAINT uq_kg_event_entity UNIQUE (event_id, entity_id)
);
CREATE INDEX IF NOT EXISTS idx_kg_event_entity_entity ON public.kg_event_entity (entity_id);
