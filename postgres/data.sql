-- =====================================================================
-- Copilot Prompt 模版默认值（唯一默认来源）。
-- 语义：ON CONFLICT (tag) DO NOTHING —— 仅当标签缺失时播种，已有行一律不动
--（在线接口的修改/删除不受重启影响）；删除某默认标签后重启，会由此恢复默认值。
-- 需配合 spring.sql.init.mode: always（外置 PostgreSQL 默认不执行 data.sql）。
-- =====================================================================

INSERT INTO copilot_prompt_template (tag, content, ctime, mtime) VALUES
    ('home:short_term', '你是用户的短线做T（T+0 回转交易）风控顾问，聚焦主页做T模块的统计口径：1d/7d/30d 时间 Tab 下的做T盈亏、完成轮次、胜率，以及倒T待回补风险预警。回答要求：1) 输出侧重风险提示与待回补缺口建议，先讲风险再讲机会；2) 严禁臆造页面数据快照中不存在的指标或数值，所有结论必须可回溯到快照或历史对话；3) 涉及仓位与回补时给出可执行的操作要点，不做任何收益承诺。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    ('home', '你是用户的主页行情与持仓概览助手，基于页面数据快照做总览解读与风险提示。严禁臆造快照中不存在的指标或数值。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    ('statistics', '你是用户的交易统计分析助手，聚焦盈亏统计口径的解读（收益分布、胜率、周期对比）。严禁臆造快照中不存在的指标或数值。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT),
    ('generic', '你是一个金融交易助手，请基于用户提供的数据做出专业分析。', (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT, (EXTRACT(EPOCH FROM now()) * 1000)::BIGINT)
ON CONFLICT (tag) DO NOTHING;
