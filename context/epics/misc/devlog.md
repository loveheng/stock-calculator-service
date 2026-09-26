---
dev-loop: devlog
format: v1
epic: misc
total-merged: 9
last-merge: 2026-09-26
---
- [2026-09-26] [变更]: 后端「系统动作输出规范」补强（画布「4 只标的建档」散文幻觉 actions:null 实证，前端排查定位缺口）：ACTION_OUTPUT_CONTRACT 由抽象占位示例升级为「抽象骨架 + 画布真实 few-shot」（canvas_add_block brief+kline 双动作完整外壳），新增反幻觉后果句（动作不进块=请求失败）与 ``` 围栏禁令显著化（前端①②④项；③已有，⑤流式截断为独立待办另立）；解析器测试新增契约内嵌 few-shot 可消费断言（提示词↔解析器同源红线回归锚点）；附带核实前端 middleware.js copilot/sync/guide 路由缺口已由前端会话补齐
- [2026-09-26] [验证]: ./mvnw test main 全量 443 全绿（含解析器 17 用例）；main 重启零 ERROR；对话依从性效果待画布重发实测（本环境无付费 DeepSeek 会话）
- [2026-09-26] [变更]: 动作外壳流式截断（规范⑤）：新增 copilot/util/ActionShellStreamFilter 有状态状态机（透传+扣留疑似标签尾巴防跨 chunk 拆分/入壳截留/闭合后恢复壳后正文/flush 兜底——四条口径与 parse cleanedText 逐条对齐），织入 askStream 替换旧「开标签出现即整段抑制」位（修复其两缺陷：半截标签闪现、开标签所在 chunk 的正文前缀被整条丢弃），流尾 flush 补发后再发 done；ActionShellStreamFilterTest 9 用例含「逐字符喂流 ≡ parse cleanedText」终判范式
- [2026-09-26] [验证]: ./mvnw test main 全量 452 全绿（新增 9 用例）；main 重启零 ERROR；流式显示效果（外壳不闪现）待画布聊天实测
