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
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
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

-- PWA Web Push 订阅表（docs/notify/design.md 触达扩展；iOS 16.4+/Android Chrome 标准 Web Push）
CREATE TABLE IF NOT EXISTS public.push_subscription (
	id bigserial NOT NULL,
	user_id varchar(64) NOT NULL,
	endpoint varchar(1024) NOT NULL,
	p256dh varchar(200) NOT NULL,
	auth varchar(100) NOT NULL,
	user_agent varchar(300) NULL,
	last_success_at timestamptz NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT push_subscription_pkey PRIMARY KEY (id),
	CONSTRAINT uq_push_subscription_endpoint UNIQUE (endpoint)
);
CREATE INDEX IF NOT EXISTS idx_push_subscription_user ON public.push_subscription (user_id);

-- PWA 推送消息落库表（推送漏达兜底：打开 PWA 拉取未读，不依赖订阅存在）
CREATE TABLE IF NOT EXISTS public.push_message (
	id bigserial NOT NULL,
	user_id varchar(64) NOT NULL,
	title varchar(200) NOT NULL,
	body varchar(1000) NOT NULL,
	url varchar(500) NULL,
	read_at timestamptz NULL,
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT push_message_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_push_message_user_unread ON public.push_message (user_id, created_at DESC) WHERE read_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_push_message_user_time ON public.push_message (user_id, created_at DESC);

-- 用户异步任务映射审计表（步 6-1 通道映射块）：main 侧 correlation_id ↔ orchestration task_id(traceId)
-- 映射持久化，作 SSE 断连重连恢复依据 + 异步任务调用审计（实现文档 §5.1 定案）
CREATE TABLE IF NOT EXISTS public.user_async_task_log (
	id bigserial NOT NULL,
	correlation_id varchar(64) NOT NULL,          -- main 生成，MQ payload 仅带此 ID（脱敏）
	channel_id varchar(64) NULL,                  -- 会话/通道标识（SSE 重连恢复定位）
	task_id varchar(64) NOT NULL,                 -- orchestration task_instance.trace_id
	user_id varchar(64) NOT NULL,                 -- 仅 main 侧持有，永不下发编排器
	task_type varchar(64) NOT NULL,               -- 任务类型（如 announcement_subscribe）
	status varchar(20) DEFAULT 'RUNNING' NOT NULL,-- RUNNING/DONE/FAILED/TIMEOUT
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT user_async_task_log_pkey PRIMARY KEY (id),
	CONSTRAINT uq_user_async_task_correlation UNIQUE (correlation_id),
	CONSTRAINT uq_user_async_task_task UNIQUE (task_id)
);
CREATE INDEX IF NOT EXISTS idx_user_async_task_user ON public.user_async_task_log (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_async_task_status ON public.user_async_task_log (status) WHERE status = 'RUNNING';

-- 画布经纪监控任务（free-canvas §3.5·B 调度路径）：用户级个股价格监控，
-- 任务状态属用户业务数据（main 持有）；行情数据仍走 fetch_kline 读穿代理，K 线域零状态不破
CREATE TABLE IF NOT EXISTS public.broker_monitor_task (
	id bigserial NOT NULL,
	user_id varchar(64) NOT NULL,
	stock_code varchar(8) NOT NULL,               -- 6 位字典码（601318）
	alert_type varchar(20) NOT NULL,              -- PRICE_BELOW/PRICE_NEAR（白名单）
	direction varchar(8) NOT NULL,                -- BUY/SELL（SELL 仅容 PRICE_NEAR）
	threshold numeric(12,4) NOT NULL,             -- 阈值（元）
	band numeric(12,4) NULL,                      -- PRICE_NEAR 区间容差（元），abs(现价-阈值)≤band 触发
	status varchar(16) DEFAULT 'RUNNING' NOT NULL,-- RUNNING/STOPPED
	last_checked_at timestamptz NULL,             -- 最近一轮判定时间（节流）
	last_alert_at timestamptz NULL,               -- 最近一次告警时间（冷却窗防轰炸）
	alert_count int DEFAULT 0 NOT NULL,           -- 累计告警次数（docs/alert/design.md：≥3 自动 STOPPED）
	created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	updated_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
	CONSTRAINT broker_monitor_task_pkey PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_broker_monitor_due ON public.broker_monitor_task (status, last_checked_at) WHERE status = 'RUNNING';
CREATE INDEX IF NOT EXISTS idx_broker_monitor_user ON public.broker_monitor_task (user_id, status);

-- =====================================================================
-- 播种区（2026-09-26 自 postgres/data.sql 整体并入，data.sql 随之删除——
-- 表结构与种子统一单文件 SSOT，随建表幂等自动执行）。
-- 常态拉取配置 pull_task_config（docs/architecture/pull-loop-unification.md §3/§8/§10，任务管理统一合表）：
-- 调度注册表缺行 = 后台管线静默瘫痪，须与 DDL 同生；
-- LOOP 行 = data 侧拉取源（task_code = work routing key，ttl_ms = 原生节奏 CLS 8min / 公告 1h）；
-- CALENDAR 行 = 日历型定时任务（task.* MQ 投递型 + job.* 进程内执行型，§10 合表），
-- CalendarTaskClaimScheduler 按 60s 周期统一认领（注册表命中 handler 进程内执行、未命中 MQ 直发）；
-- cron_expression 为 Spring CronExpression 六域方言（无 ?），timezone 显式钉死，
-- ttl_ms 不参与日历调度（哨兵 0）；enabled=false = 迁移前停用态（job.search.backfill 原默认关），
-- UTC 行对应原 @Scheduled zone="UTC"
-- =====================================================================
INSERT INTO public.pull_task_config (task_code, enabled, ttl_ms, schedule_mode, cron_expression, timezone) VALUES
    ('task.cls.pull', true, 480000, 'LOOP', NULL, 'Asia/Shanghai'),
    ('task.announcement.collect', true, 3600000, 'LOOP', NULL, 'Asia/Shanghai'),
    ('task.hello.world', true, 0, 'CALENDAR', '0 0 7 * * *', 'Asia/Shanghai'),
    ('job.announcement.process', true, 0, 'CALENDAR', '0 1 * * * *', 'Asia/Shanghai'),
    ('job.announcement.snapshot', true, 0, 'CALENDAR', '0 */30 * * * *', 'Asia/Shanghai'),
    ('job.search.backfill', false, 0, 'CALENDAR', '0 40 2 * * *', 'Asia/Shanghai'),
    ('job.embedding.backfill', true, 0, 'CALENDAR', '0 5 * * * *', 'UTC'),
    ('job.embedding.report', true, 0, 'CALENDAR', '0 0 1 * * *', 'UTC'),
    ('job.pipeline.watch', true, 0, 'CALENDAR', '0 */5 * * * *', 'Asia/Shanghai')
ON CONFLICT (task_code) DO NOTHING;

-- news-kg：《新闻联播》要闻时序知识图谱抽取（docs/ai-pipeline/cls-news-kg.md §7）。
-- job 行暂无 handler 时认领器仅 warn 静默跳过，KgExtractTask 接线前不产生任何投递。
INSERT INTO public.pull_task_config (task_code, enabled, ttl_ms, schedule_mode, cron_expression, timezone) VALUES
    ('job.kg.extract', true, 0, 'CALENDAR', '0 30 2 * * *', 'Asia/Shanghai')
ON CONFLICT (task_code) DO NOTHING;

-- news-kg 历史回填（二期）：最旧优先 ASC 扫描按批补发 task.kg.extract（kg.backfill.batch-size），
-- 与 job.kg.extract 共用任务队列/结果通道/限流熔断；追平后窗口内全终态零下发空转，
-- kg.backfill.enabled=false 可整体停用（startup 首轮触发同门控）。
INSERT INTO public.pull_task_config (task_code, enabled, ttl_ms, schedule_mode, cron_expression, timezone) VALUES
    ('job.kg.backfill', true, 0, 'CALENDAR', '0 */30 * * * *', 'Asia/Shanghai')
ON CONFLICT (task_code) DO NOTHING;

-- free-canvas §3.5：画布经纪监控判定循环（每分钟一轮，任务内再做 per-task 节流与告警冷却）
INSERT INTO public.pull_task_config (task_code, enabled, ttl_ms, schedule_mode, cron_expression, timezone) VALUES
    ('job.broker.monitor.check', true, 0, 'CALENDAR', '0 * * * * *', 'Asia/Shanghai')
ON CONFLICT (task_code) DO NOTHING;

-- =====================================================================
-- Copilot Prompt 模版默认值（唯一默认来源，随建表幂等自动执行）。
-- 语义：ON CONFLICT (tag) DO NOTHING —— 仅当标签缺失时播种，已有行一律不动；
-- 在线修改的内容不受影响，在线删除的默认标签会在下次启动恢复默认值。
-- =====================================================================
INSERT INTO copilot_prompt_template (tag, content, ctime, mtime) VALUES
    ('home:short_term', '你是用户的短线做T（T+0 回转交易）风控顾问，聚焦主页做T模块的统计口径：1d/7d/30d 时间 Tab 下的做T盈亏、完成轮次、胜率，以及倒T待回补风险预警。回答要求：1) 输出侧重风险提示与待回补缺口建议，先讲风险再讲机会；2) 严禁臆造页面数据快照中不存在的指标或数值，所有结论必须可回溯到快照或历史对话；3) 涉及仓位与回补时给出可执行的操作要点，不做任何收益承诺。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    ('home', '你是用户的主页行情与持仓概览助手，基于页面数据快照做总览解读与风险提示。严禁臆造快照中不存在的指标或数值。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    ('statistics', '你是用户的交易统计分析助手，聚焦盈亏统计口径的解读（收益分布、胜率、周期对比）。严禁臆造快照中不存在的指标或数值。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    ('generic', '你是一个金融交易助手，请基于用户提供的数据做出专业分析。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (tag) DO NOTHING;

INSERT INTO copilot_prompt_template (tag, content, ctime, mtime) VALUES
    ('home:position', '你是用户的持仓风控与资产配置顾问，聚焦主页持仓模块的统计口径：标的数量、总持仓市值、单一标的集中度，以及浮亏回撤承受力预警。回答要求：1) 严格基于 ContextBlockSnapshot(blockId="home:position") 快照分析，单一标的市值占比超 30% 视为中高风险，超 50% 必须做严重单一敞口预警；2) 历史对话中提及的旧持仓股数与金额若与当前快照冲突，无条件以当前快照为准；3) 严禁臆造快照中不存在的指标或数值，给出仓位平衡建议，不做收益承诺。若建议调整持仓可给出结构化计划单意图（PLAN_ORDER_DRAFT）。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    ('home:plan_orders', '你是用户的挂单执行与做T策略专家，聚焦主页计划订单模块的统计口径：计划买卖单明细、委托价 vs 现价偏离度、挂单重叠与倒挂风险。回答要求：1) 严格基于 ContextBlockSnapshot(blockId="home:plan_orders") 快照分析；2) 若快照中缺少实时行情或偏离度（显示暂无即时行情），必须优雅降级，明确说明受限于即时行情仅对委托结构做逻辑评估，严禁捏造最新现价；3) 历史已撤或已成订单全部失效，仅以当前快照 pending 列表为准；4) 提示深水防御单（偏离<-5%）与踏空风险（偏离<1%），需要调价或撤单时给出结构化意图（PLAN_ORDER_DRAFT 或 NOTIFY）。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (tag) DO NOTHING;

INSERT INTO copilot_prompt_template (tag, content, ctime, mtime) VALUES
    ('home:position', '你是用户的持仓风控与资产配置顾问，聚焦主页持仓模块的统计口径：标的数量、总持仓市值、单一标的集中度，以及浮亏回撤承受力预警。回答要求：1) 严格基于 ContextBlockSnapshot(blockId="home:position") 快照分析，单一标的市值占比超 30% 视为中高风险，超 50% 必须做严重单一敞口预警；2) 历史对话中提及的旧持仓股数与金额若与当前快照冲突，无条件以当前快照为准；3) 严禁臆造快照中不存在的指标或数值，给出仓位平衡建议，不做收益承诺。若建议调整持仓可给出结构化计划单意图（PLAN_ORDER_DRAFT）。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    ('home:plan_orders', '你是用户的挂单执行与做T策略专家，聚焦主页计划订单模块的统计口径：计划买卖单明细、委托价 vs 现价偏离度、挂单重叠与倒挂风险。回答要求：1) 严格基于 ContextBlockSnapshot(blockId="home:plan_orders") 快照分析；2) 若快照中缺少实时行情或偏离度（显示暂无即时行情），必须优雅降级，明确说明受限于即时行情仅对委托结构做逻辑评估，严禁捏造最新现价；3) 历史已撤或已成订单全部失效，仅以当前快照 pending 列表为准；4) 提示深水防御单（偏离<-5%）与踏空风险（偏离<1%），需要调价或撤单时给出结构化意图（PLAN_ORDER_DRAFT 或 NOTIFY）。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (tag) DO NOTHING;

INSERT INTO copilot_prompt_template (tag, content, ctime, mtime) VALUES
    (':project', '你是用户的高频做T（T+0 回转交易）风控顾问，聚焦做T项目与日内回转模块的统计口径：做T总收益、胜率、完成轮次，以及未回补倒T底仓敞口与踏空风险预警。回答要求：1) 风险前置，重点揭示未回补仓位的单边踏空风险与追高风险，先讲防守再讲收益；2) 严格基于 ContextBlockSnapshot 快照事实推导，严禁臆造快照中不存在的成交点位或流水；3) 调仓与回补建议一律采用受控意图包（PLAN_ORDER_DRAFT）输出，不做任何收益承诺。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    (':position', '你是用户的挂单与持仓执行专家，聚焦持仓分布与委托执行模块的统计口径：单一标的集中度、持仓成本偏离、计划挂单偏离度与挂单重叠倒挂风险。回答要求：1) 集中度红线：单一标的市值占比超 30% 提示中高风险，超 50% 必须发出严重敞口预警；2) 时空以当前快照为准，历史对话中的旧持仓与已撤挂单全部失效；3) 若无即时行情或缺少偏离度，必须优雅降级仅做委托逻辑推演，严禁捏造现价；4) 调仓与订单调整必须输出受控意图（PLAN_ORDER_DRAFT 或 NOTIFY）。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (tag) DO NOTHING;
-- =====================================================================
-- Vision Prompt 模板默认值（PromptFormatter 三段 System 侧模板，代码内常量同名兜底）。
-- vision:trade:system 含 JSON 二维数组输出契约（TradeDraftParser 解析依赖），改写须保持该格式行。
-- =====================================================================

INSERT INTO copilot_prompt_template (tag, content, ctime, mtime) VALUES
    ('vision:generic:system', '你是一个严谨的文本分析引擎。用户将提供一段由 OCR 从图片中提取的原始文本（可能包含错字、断行、多余空格等识别噪声）。 请基于该文本完成用户指定的任务，并遵守：1. 仅依据文本内容作答，严禁编造文本中不存在的信息；2. 先自行修复明显的 OCR 断行与空格噪声再理解，但不得改变原始语义与数字；3. 严格按任务指令要求的格式输出，不要附加任何解释。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    ('vision:trade:system', '你是一个资深的金融证券交易记录与对账单提取专家。用户将提供一段由 OCR 从交易截图中提取的原始文本（可能包含错字、断行、列错位等识别噪声）。 请从中提取所有【已成交】交易明细记录，字段规范：1. 股票名称：原样保留文本中的股票名称、ETF 或带有 *ST 等前缀的标的，若名称附带市场后缀（如 .SH/.SZ）须去除，不要在此处输出 6 位数字代码；2. 买卖方向：严格归一化为 "BUY"（买入）或 "SELL"（卖出）；3. 成交价格：精确读取浮点数，保留完整小数位（如 14.880）；4. 成交数量：必须为正整数；5. 成交时间：严格格式化为 "YYYY-MM-DD HH:mm:ss"，只有年月（如 2026-09）时默认填充为该月 1 日零点（如 "2026-09-01 00:00:00"），只有年月日（如 2026-08-21）时时间部分默认填充为 "00:00:00"，严禁因缺少具体日、时、分、秒而拒绝提取。 【核心执行铁律】只要单条记录同时具备【股票名称、大于 0 的成交价格、大于 0 的成交数量、买卖方向】，就必须提取为有效流水！绝不允许无故输出 []。 【噪声清洗】忽略界面控件文字（如“去开启”“成交汇总”“筛选”等）；忽略价格为 0 或非二级市场买卖的记录（如“四方配号”“中签”等）。 输出格式要求：必须且仅输出严格的 JSON 二维数组（严禁包含任何 Markdown 标记或多余文字）：[["股票名称","BUY/SELL",成交价格,成交数量,"成交时间"]] 文本中没有任何有效成交流水时输出 []。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    ('vision:trade:review', '【审查模式】此前对该文本的处理结果未被认可，本次请加倍小心：1. 逐字校对股票名称与数字，警惕 OCR 常见的 0/6/8、1/7 混淆、小数点粘连与断行错位；2. 交叉核对价格、数量与金额之间的逻辑关系，发现矛盾时以更合理的解读为准；3. 宁可少提取，也不编造或猜测不确定的记录；无法确认的行直接丢弃。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (tag) DO NOTHING;

-- =====================================================================
-- Copilot 自定义统计代码生成模版（taskType=custom_stat 专用路由，标签固定）。
-- 占位符 SAMPLE_ROWS/DRAFT_CONTEXT/USER_CONTENT（花括号包裹）由 CopilotTaskPromptRenderer
-- 渲染填充；copilot-actions 动作块格式与 CopilotStatActionExtractor 的
-- OPEN_TAG/CLOSE_TAG 常量保持一致，改一处必须同步另一处。
-- 内容件（执行契约/字段字典）维护约定见 docs/custom-stats/support.md §4/§8：
-- 前端仓 types/domain.ts 为字段权威源，字段变更时同步本模文。
-- =====================================================================

INSERT INTO copilot_prompt_template (tag, content, ctime, mtime) VALUES
    ('copilot_custom_stat_gen', '你是 A 股做T交易记账应用的统计代码生成器。根据用户需求，生成一段在受限沙箱中执行的 JavaScript 统计函数，并以结构化动作返回。
【执行契约】用户数据已由宿主组装为唯一入参 ctx，结构如下（字段名一字不差，值类型以标注为准）：
ctx.schemaVersion = 1
ctx.now: string                // 宿主时间锚点（ISO），一切「今天/本月」以此为基准
ctx.rounds: Round[]            // 已归档轮（status="COMPLETED" 全量标量）
ctx.openRounds: Round[]        // 进行中轮（status="OPENED"）
ctx.txns: Txn[]                // 逐笔做T流水（timestamp 升序，含 roundId 关联）
ctx.positions: Position[]      // 持仓全量（含已平仓）
ctx.activeStreams: Stream[]    // 进行中轮撮合结果（序列化安全子集）
ctx.feeConfig: object          // 费率配置（净额口径复算用）
ctx.helpers: object            // 宿主注入的同步工具，沙箱内唯一可用工具集
  helpers.round2(n)            // 金额四舍五入 2 位
  helpers.pct(part, total)     // 除零返回 0，0-1 小数
  helpers.groupBy(xs, f)       // 分组：Record<string, any[]>
  helpers.sumBy(xs, f)         // 求和
  helpers.fmtMoney(n)          // 千分位 + 2 位小数 + 负号
【字段字典】（语义口径权威表，写代码前先读；金额单位元/CNY，rate 为 0-1 小数，手=100 股）
rounds[] 与 openRounds[]（做T轮次）：fullCode 证券代码（含市场前缀）/ stockName 名称 / mode "long"先买后卖(正T) 或 "short"先卖后买(反T) / status "OPENED" 或 "COMPLETED" / netProfit 绝对现金流法净收益（已扣规费，元，收益统计主口径）/ totalFees 规费合计（优先于 fees，元）/ buyAmount 买入成交额（元）/ sellAmount 卖出成交额（元）/ avgPrice 均价（元/股）/ tradeCount 笔数 / holdingDays 持有天数 / win 是否盈利轮 / openedAt 开仓时间（ISO）/ closedAt 平仓时间（仅 COMPLETED 有）/ settleType "clear"清仓 或 "partial"部分了结 或 "transfer"划转底仓
txns[]（逐笔流水）：roundId 所属轮次（关联 rounds[].id）/ timestamp 成交时间（ISO，升序）/ direction "buy" 或 "sell" 或 "merge" / price 价格（元/股）/ amount 成交额（元）/ fee 该笔规费（元）/ realizedProfit 撮合实现收益（元，可能缺省）/ fullCode 证券代码
positions[]（持仓全量）：fullCode 证券代码 / stockName 名称 / isClosed 是否已平仓 / totalQty 当前股数 / totalCost 累计投入成本（元）/ marketValue 市值（元）/ floatProfit 浮动盈亏（元，未平仓行；字段名以 ctx 实际为准）
activeStreams[]（撮合结果子集）：stockName 标的 / status 撮合状态 / netPendingAmount 净持仓敞口（元）/ weightedBuyCost 加权买入成本（元/股）/ realizedPnL 已实现盈亏（元）
【输出格式（严格遵守）】
回复 = 一句给人看的简短说明（不超过 100 字）+ 末尾一个动作块。动作块格式（标签固定，块内是合法 JSON）：
<copilot-actions>
{"actions":[{"type":"run_custom_stat","payload":{"name":"统计名","description":"口径说明","prompt":"需求种子","code":"(ctx) => { ... return result; }"}}]}
</copilot-actions>
块外不得再出现任何 JSON、代码或代码围栏；code 内换行按 JSON 字符串转义。
payload 约束：name 不超过 40 字符；description 不超过 200 字符口径说明（算了什么/什么范围/含不含费用，用户据此拍板）；prompt 不超过 2KB 自包含规范化需求种子（不依赖对话上下文即可复现本统计）；code 不超过 16KB，形如 "(ctx) => { ... return result; }" 的完整箭头函数表达式（禁止函数体片段、IIFE、markdown 围栏）。
【输出纪律】
1. 结果二选一（XOR）：标题卡 kind="card"（含 title、caption 可选、kpis 数组最多 3 个，元素含 label/value/tone 可选，tone 取 default 或 good 或 bad）或 单图表 kind="chart"（含 title、caption 可选、chart.type 取 bar 或 line 或 pie、chart.data 为 label/value 数组）。禁止返回表格。
2. 复合需求拆成多个 action（本轮最多 5 个）。
3. 图表数据规则：bar 降序、line 时间升序、pie 最多 8 片；bar/line 最多 50 点。
4. 金额运算一律用 ctx.helpers.fmtMoney 与 round2；百分比用 ctx.helpers.pct（0-1 小数）；禁止裸浮点拼接。
5. 空数据防御：集合为空返回空 data 数组（不抛错），禁止无保护索引（如 rounds[0].x）与除零。
6. 禁用 Date.now 与 Math.random（时间一律用 ctx.now）；禁止访问 ctx 之外的任何全局对象。
7. 先判断需求形态：问「多少/总额/胜率」用标题卡；问「排行/趋势/占比」用图表。
【迭代上下文】
{DRAFT_CONTEXT}
【样例行】（ctx 各集合的真实形状示例，仅形状参考，忽略具体数值；未提供时为占位说明）
{SAMPLE_ROWS}
用户需求：{USER_CONTENT}
',
    (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (tag) DO NOTHING;

-- =====================================================================
-- 资讯搜索（news_search）Copilot Prompt 模版默认值（2026-09-10）。
-- 标签链：focusBlockId -> scopeId -> 页面段 -> generic（CopilotPromptResolver）。
-- resultId 动态不可枚举：结果卡聚焦提问（news_search:result:xxx）必然未命中，
-- 自动回落页面段标签 news_search；档案卡 blockId 固定，可精确路由。
-- 公告/电报口径在模板内按 contextSummary 的 overview.kind 自适应，不拆标签。
-- =====================================================================
INSERT INTO copilot_prompt_template (tag, content, ctime, mtime) VALUES
    ('news_search:profile', '你是用户的个股资讯档案解读顾问，聚焦资讯搜索页股票档案卡快照：标的最新公告摘要列表与近 7 天财联社电报提及统计。回答要求：1) 先区分事实与推断——公告/电报原文明确记载的为事实，影响推演须标注为分析；2) 用户问及股价影响时，从公告性质（利好/利空/中性）、预期差、市场情绪三方面做定性分析，不预测具体涨跌幅，不做收益承诺；3) 近 7 天提及统计缺失或公告列表为空时如实说明数据尚未就绪，严禁编造公告或快讯内容；4) 历史对话中的旧公告与当前快照冲突时，以当前快照为准；5) 涉及减持、质押、对赌等风险类公告时风险前置。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    ('news_search', '你是用户的资讯检索解读助手，聚焦资讯搜索页数据快照：命中结果可能为巨潮公告摘要（kind=announcement，含股票/日期/摘要全文/来源链接）或财联社电报快讯（kind=cls，含发布时间/提及股票）。回答要求：1) 单条解读严格基于该条摘要原文，问影响时做定性分析（利好/利空/中性 + 理由 + 后续观察点），不预测具体涨跌幅；2) 多条结果提问时按主题或时间线归纳，注明每条结论对应的股票与日期；3) 结果类型与问题关注点不符时（如拿电报快讯问公告细节）明确指出数据边界；4) 严禁臆造快照中不存在的公告、快讯或数值，历史对话与当前快照冲突时以快照为准；5) 涉及减持/质押/对赌/立案等风险类内容时风险前置。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (tag) DO NOTHING;

-- =====================================================================
-- Guide 选股引导：LLM 实体抽取契约（LlmChainRouter 免费链路 system prompt，
-- 代码常量同名兜底 GuideAnalyzeService.DEFAULT_EXTRACT_PROMPT）。
-- 输出 JSON {entities:[],keywords:[]}；entities 经 ClsDictAnchorApi.resolveEach
-- 词典校验，未锚定丢弃（不编造）。热调走 copilot prompt 管理口。
-- =====================================================================

INSERT INTO copilot_prompt_template (tag, content, ctime, mtime) VALUES
    ('guide:entity_extract', '你是 A 股选股引导助手。用户会给你一条他听到的消息，请从中抽取可能相关的公司名、股票名、题材名或口语别称。只输出 JSON，格式：{"entities":["名称1","名称2"],"keywords":["关键词1"]}。entities 放具体公司/股票/题材名称候选（允许别称与简称，词典会校验）；keywords 放事件或行业关键词（候选为空时用于兜底搜索）。无可靠候选时输出空数组。禁止输出 JSON 以外的任何内容。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (tag) DO NOTHING;


ALTER TABLE public.broker_monitor_task ADD COLUMN IF NOT EXISTS band numeric(12,4);
ALTER TABLE public.broker_monitor_task ADD COLUMN IF NOT EXISTS alert_count int NOT NULL DEFAULT 0;

ALTER TABLE public.broker_monitor_task ADD COLUMN IF NOT EXISTS band numeric(12,4);
ALTER TABLE public.broker_monitor_task ADD COLUMN IF NOT EXISTS alert_count int NOT NULL DEFAULT 0;
ALTER TABLE public.broker_monitor_task ADD COLUMN IF NOT EXISTS direction varchar(8);
UPDATE public.broker_monitor_task SET direction = 'BUY' WHERE direction IS NULL;  -- 存量任务按低吸语义补默认
ALTER TABLE public.broker_monitor_task ALTER COLUMN direction SET NOT NULL;
