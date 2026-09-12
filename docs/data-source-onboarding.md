# 新数据源接入指南（webhook ingest · 阶段 5）

> 面向对象：向 stock-calculator 接入新数据源（webhook 推送型）的开发者。
> 验收口径（设计文档 §8 阶段 5）：**新数据源接入只改数据服务（stock-calculator-data）+ contract，主服务零改动**。
> 总则（2026-09-12 终态）：主服务的进程内抓取回退路径已删除，MQ 为唯一路径——**新数据抓取/处理任务一律只写 data 服务 + contract**（拉取源落 data/collector 包、处理落 data/worker 包，消息形状进 contract），主服务只做 result 消费、状态机与查询；不存在、也不得再建进程内并行实现。需要新增主服务侧能力（新表/新状态机）时，属于该源的消费端与状态机设计，不属于抓取面。

## 1. 架构总览

```mermaid
flowchart LR
    A[推送方<br>webhook client] -->|POST /api/ingest/{source}<br>HMAC-SHA256 签名| B[data 服务<br>IngestController]
    B --> C[IngestParserRegistry<br>按 source 路由插件]
    C --> D[IngestParserPlugin<br>标准化 ArticleIngestedPayload]
    D -->|result.article.ingested<br>RabbitMQ RESULTS 交换机| E[主服务<br>ClsArticleMqConsumer]
    E --> F[existsById 幂等入库<br>ArticleSavedEvent]
    F --> G[既有向量化链<br>task.embedding.compute]
```

- 认证定案（开放问题 1）：**HMAC-SHA256 + 时间戳防重放**（Stripe 模式，一次做好，后续源免改协议）
- 拓扑零改动：`result.ingest.q` 以 `result.#` 绑定 RESULTS 交换机，新路由键自动落队

## 2. 推送方协议

### 请求

```sh
POST /api/ingest/{source}
Content-Type: application/json
X-Ingest-Timestamp: 1757500000000        # 毫秒时间戳
X-Ingest-Signature: <hex>                # HMAC-SHA256(secret, timestamp + "." + rawBody) 的 hex
Body: 原始 JSON（签名必须基于未经任何改动的原始字节）
```

### 签名算法（Python 示例）

```python
import hmac, hashlib, json, time

secret = b"<共享密钥>"
body = json.dumps({"externalId": "x-1", "title": "标题", "content": "正文..."},
                  ensure_ascii=False, separators=(",", ":"))
ts = str(int(time.time() * 1000))
sig = hmac.new(secret, f"{ts}.{body}".encode(), hashlib.sha256).hexdigest()
# header: X-Ingest-Timestamp: ts   X-Ingest-Signature: sig
```

### 必填/可选字段（generic 源）

| 字段 | 必填 | 说明 |
|---|---|---|
| externalId | ✅ | 源系统唯一 id（幂等规则输入） |
| content | ✅ | 正文 |
| title / brief / author | 可选 | 标题 / 摘要 / 作者 |
| publishedAt | 可选 | epoch 秒 |

### 响应与错误码

| 状态 | 含义 | 推送方动作 |
|---|---|---|
| 202 | 已受理（articleId + traceId） | 无 |
| 400 | 时间戳缺失/超窗（默认 ±300s）、JSON 非法、externalId/content 缺失 | 校正后重试 |
| 401 | 签名不符 | 检查 secret 与签名串（`ts + "." + body`） |
| 404 | 未知 source | 未注册插件，联系管理员 |
| 503 | 服务端 secret 未配置 | 稍后重试 |

**重试语义**：所有失败在 HTTP 层交还推送方重试（服务端不做 ingest 队列缓冲）；重复推送安全——articleId 确定性生成，主服务 `existsById` 幂等去重。

## 3. articleId 幂等规则（契约单点）

`articleId = SHA-256(source|externalId) 前 8 字节大端取有符号 long`（`IngestArticleIds.of`）。
同一 (source, externalId) 恒定 → 重复投递/主服务重试天然幂等。**插件禁止随机 id。**

## 4. data 侧：实现一个新源插件

```java
@Component
@ConditionalOnProperty(prefix = "datasvc.ingest", name = "enabled", havingValue = "true")
public class MySourceParser implements IngestParserPlugin {

    @Override public String source() { return "mysource"; }   // = URL {source}

    @Override public ArticleIngestedPayload parse(JsonNode payload) {
        String externalId = payload.path("id").asText(null);
        // ... 源字段 → 标准化映射，缺字段抛 IllegalArgumentException（→ 400）
        return ArticleIngestedPayload.builder()
                .articleId(IngestArticleIds.of("mysource", externalId))
                .source("mysource").externalId(externalId)
                .title(payload.path("title").asText(null))
                .content(payload.path("body").asText(null))
                .build();
    }
}
```

参考实现：`GenericJsonIngestParser`（generic 源，JSON 直通）。
需契约侧新增字段（如 tags/sourceUrl）时改 contract（R2：禁止两侧各自定义）。

## 5. 部署配置（data 服务）

```yaml
datasvc:
  ingest:
    enabled: true            # 或 DATASVC_INGEST_ENABLED=true
    secret: ${INGEST_SECRET} # 与推送方共享；空值时端点 503
    skew-seconds: 300
```

注意：native 变体如启用 ingest，须在构建期钉死 `datasvc.ingest.enabled`（AOT 固化条件装配，R1 教训，同 worker/collector）。

## 6. 主服务侧（一次性，已完成）

`result.article.ingested` → `ClsArticleMqConsumer.handleArticleIngested`：映射到 CLS 电报实体复用
`saveArticleWithRelations`（level="C"、type=-1、author 缺省 `webhook:{source}`），入库后
ArticleSavedEvent 自动触发向量化。**接入新源无需改此处。**

## 7. 已知限制与预留

- v1 直发 result（轻校验+映射，不做 task 队列缓冲）；高吞吐源将来切 task 模式（新队列 + worker 消费），接口不变
- sourceUrl / tags 载荷字段暂未入库（entity 无列），需要时扩 contract + entity
- CLS 电报表被多源复用（author 前缀区分来源），泛化表名为后续重构项
