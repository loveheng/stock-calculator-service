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
-- E2EE 用户服务（docs/e2ee-auth-backend-design.md §D.3）
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
    deleted_at        BIGINT       DEFAULT 0
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
