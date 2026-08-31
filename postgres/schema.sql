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