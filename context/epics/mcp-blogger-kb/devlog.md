---
dev-loop: devlog
format: v1
epic: mcp-blogger-kb
total-merged: 0
last-merge: none
---

# mcp-blogger-kb 开发过程日志

（散修/小改动按行追加；≥5 条自动归并进 memory.md）

## 追加区
- [2026-09-20] [变更]: M1-text 落地——kb_source 注册表（/admin/source 注册即灌入/移除停更保数据/清单）+ KbWeiboBackupParser（微博备份一条=一个观点单元，纯媒体条目与标记行剔除，published_at 提取）+ KbIngestService.ingestSource（博主伪书 category=blogger + source_id 关联，与灌书共用批量向量化 embedAndWrite，幂等=按书 truncate 重载）+ kb_search 过滤 removed 源；kb_book/kb_chunk 补列 source_id/published_at（ALTER IF NOT EXISTS）
- [2026-09-20] [变更]: E2E 实证——杀旧实例释放 18081 后新构建启动 validate 通过；注册「麻辣新鲜」微博备份 59 条→55 块全带 published_at（09-06→09-20）；DELETE 后 55 块保留（停更保数据）、重注册重灌 55/55；MCP 三步协议真调 kb_search「黄金的避险功能」命中「麻辣新鲜/微博 2026-09-06 06:45」出处与书籍段落同榜；单测 33/33
- [2026-09-20] [变更]: M1b RSS 轮询落地——KbRssFeedParser（JDK DOM 零依赖，RSS2.0/Atom 通吃，标题型 feed 的 description 复读标题去重，块=标题+链接；RFC-822/ISO 双时间容错折算系统时区；原拟 Rome 按零新依赖+标准实现优先改定）+ KbRssClient + KbRssPoller（kb.rss.* 门控默认 6h 逐源 fail-open）+ POST /admin/source/{name}/refresh 手动刷新；ingestSourceRss 按 content_hash 增量只补新（chunk_index 续排，chapter_path=条目标题首行）。E2E：注册「政策法规」（gov.cn 标题型 feed）首拉 11/11、二刷 0 新 11 跳、11 块带 +08 折算 published_at、MCP kb_search 真调命中带链接出处；单测 38/38
