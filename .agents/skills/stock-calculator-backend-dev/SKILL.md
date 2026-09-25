---
name: stock-calculator-backend-dev
description: stock-calculator 后端（Spring Boot 4 / Java 21 / Jakarta JPA / PostgreSQL）代码模式规范：统一风格（Lombok/构造注入/@Slf4j/异常/ApiResponse），Entity（主表/字典/关联三型 + JSONB/时间戳/生成列）、Repository、Service（判重写入/字典 upsert/事务组合）、TaskService（字典与关联分离解析 + 安全取值）、Util/Config 模板，及标准实现优先、文档联动原则。写后端任意模块、建表/接口/定时任务、发现非标准实现时使用。
---

# stock-calculator 后端代码模式

## 一、适用范围与项目锚点

- 包名: `com.zzh.stock_calculator`；技术栈: Spring Boot 4 / Java 21 / Jakarta EE (jakarta.persistence.*) / PostgreSQL (JSONB) / Spring Data JPA + Hibernate
- 模块结构、领域包清单、编译与测试命令不放本 skill（防结构事实漂移）——唯一事实源：`stock-calculator-workflow`「模块结构」「构建命令」；功能 → 域归属唯一源：`stock-calculator-service-index` 归属表

- Modulith 边界（写码时适用）：跨域只允许引用对方**基包下的类型**；子包（entity/repository/impl 等）对外不可见，违规由 `ModulithVerifyTest`（Spring Modulith verify()）直接报错；新代码先按 `stock-calculator-service-index` 归属表判断领域，确属跨域共享才放顶层 common / config / util

## 二、通用代码风格

### 2.1 Lombok 三件套
所有数据类必须使用以下三个注解，顺序固定：
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
```

### 2.2 依赖注入
所有 Spring Bean 使用 `@RequiredArgsConstructor` + `private final` 字段注入：
```java
@Slf4j
@Service          // 或 @Component / @Repository
@RequiredArgsConstructor
public class XxxService {
    private final XxxRepository xxxRepository;
    private final YyyService yyyService;
}
```

### 2.3 日志
统一使用 `@Slf4j`，用 `log.info()` / `log.warn()` / `log.error()`，不要用 `System.out`。

### 2.4 异常处理
```java
@Getter
public class BusinessException extends RuntimeException {
    private final int code;
    public BusinessException(int code, String message) { ... }
    public BusinessException(String message) { ... }  // 默认 code=400
}
```

### 2.5 统一响应
```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;
    public static <T> ApiResponse<T> success(T data) { ... }
    public static <T> ApiResponse<T> fail(int code, String message) { ... }
}
```

### 2.6 边界推演防腐注释（隐性业务规则落盘）

凡实现中为边界条件写了**非显然逻辑**（复合计算、隐式前置条件、反直觉分支、时区/单位/精度陷阱），**同轮**将推演一句话写入该方法或类的 JavaDoc——写「为什么」不写「是什么」：这类领域知识只藏在实现里，未来重构极易被当冗余删掉，注释让它随代码生存并进入 review 视野。宏观业务规则（影响对外契约或域核心流程的）不塞注释，走 §十四 文档联动落 `docs/<域>/`。

**未定论隐患另走标记**：本节记**已定论**的领域规则；若该实现**未实证**（未验证的接口行为、猜测的边界）或 catch 分支**猜测性兜底**（空 catch、只记日志、返回默认值），按 `ai-sideeffect-guard §1` 留 `// UNCERTAIN:` / `// DEGRADE:`（DEGRADE 须在兜底前打 `[DEGRADE] <场景key>` 日志）——扫描与处置口径见该 skill §2/§4，高危项转 `风险` 待办。

## 三、Entity / DTO 模式

### 3.1 通用注解模板
任何新表对应的 Entity 都必须按此模板：
```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "表名", indexes = {
        @Index(name = "idx_xxx_yyy", columnList = "yyy DESC"),
        ...
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_xxx_yyy", columnNames = {"field_a", "field_b"})
})
public class XxxEntity {
    // 主键：自增用 IDENTITY，外部ID手动设
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // BIGSERIAL 自增
    // 或 @Column(nullable = false)  // 外部ID，手动写入
    private Long id;

    // 普通字段
    @Column(nullable = false, length = 100)
    private String name;

    // 默认值（配合 @Builder.Default）
    @Column(length = 10)
    @Builder.Default
    private String level = "C";

    // 数值精度
    @Column(precision = 10, scale = 4)
    private BigDecimal price;

    // 外键字段（用普通 Long 字段，不建 @ManyToOne 关联）
    @Column(name = "article_id", nullable = false)
    private Long articleId;
}
```

### 3.2 JSONB 字段
```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "JSONB")
private List<String> images;
```

### 3.3 时间戳与生成列
#### 3.3.1 创建时间（不可更新）
```java
@CreationTimestamp
@Column(name = "created_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
        updatable = false)
private LocalDateTime createdAt;
```

#### 3.3.2 更新时间（自动更新）
```java
@UpdateTimestamp
@Column(name = "updated_at", columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
private LocalDateTime updatedAt;
```

#### 3.3.3 数据库生成列（不参与读写）
```java
@Column(name = "created_at", insertable = false, updatable = false,
        columnDefinition = "TIMESTAMPTZ GENERATED ALWAYS AS (to_timestamp(ctime)) STORED")
private OffsetDateTime createdAt;
```

#### 3.3.4 时间类型选择
- `TIMESTAMP WITHOUT TIME ZONE` → `java.time.LocalDateTime`
- `TIMESTAMPTZ` → `java.time.OffsetDateTime`

### 3.4 三种实体类型

#### 3.4.1 主表实体（如 cls_article）
- 主键可能是外部ID（手动写入），也可能是自增
- 包含完整的业务字段
- JSONB 字段用 `@JdbcTypeCode(SqlTypes.JSON)`

#### 3.4.2 字典表实体（如 stock, cls_subject）
- 主键是业务ID（如 stock_id, subject_id），手动写入
- 包含元数据字段（name, old_name, is_stib 等）
- 带 `created_at` / `updated_at` 时间戳
- 入库时采用 upsert 模式：不存在才插入
```java
// 示例：Stock 实体主键为 String
@Id
@Column(name = "stock_id", nullable = false, length = 32)
private String stockId;
```

#### 3.4.3 关联表实体（如 cls_article_stock, cls_article_subject）
- 自增主键，`@GeneratedValue(strategy = GenerationType.IDENTITY)`
- 只包含关联字段 + 快照数据，不含冗余元数据
- 联合唯一约束 `UNIQUE(父表_id, 业务_id)`
```java
@Table(name = "link_table", uniqueConstraints = {
        @UniqueConstraint(name = "uk_xxx", columnNames = {"article_id", "stock_id"})
})
```

### 3.5 原则
- 不要建 `@ManyToOne` / `@OneToMany` 关联，外键用 `Long articleId` 平铺
- 不要建双向关联，避免循环引用和 lazy loading 问题
- `@Builder.Default` 与字段默认值同时使用，确保 Lombok builder 也能得到默认值
- 字典表和关联表分离：字典存元数据，关联存关系+快照

## 四、Repository 模式

Repository 放在所属领域的 `<domain>.repository` 包（模块内子包对外不可见，Modulith 边界见 §一）：

```java
@Repository
public interface XxxRepository extends JpaRepository<XxxEntity, Long> {
    // 按需添加查询方法
    List<XxxEntity> findByArticleId(Long articleId);
    boolean existsByStockId(String stockId);
}
```

- `@Repository` 可省略（Spring Data JPA 自动注册），但建议保留以表意
- 复杂查询写在 Repository 接口方法名中，不写 `@Query` 除非必要

## 五、Service 模式

Service 放在所属领域的 `<domain>.service` 包：

### 5.1 标准 Service 模板
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class XxxService {
    private final XxxRepository xxxRepository;

    /**
     * 保存前检查是否存在，不存在则写入
     * @return true=新增; false=已存在
     */
    @Transactional
    public boolean saveIfNotExists(XxxEntity entity) {
        if (xxxRepository.existsById(entity.getId())) {
            log.debug("already exists, id={}", entity.getId());
            return false;
        }
        xxxRepository.save(entity);
        log.info("saved new xxx, id={}", entity.getId());
        return true;
    }
}
```

### 5.2 字典表 upsert 模式
字典表（Stock、ClsSubject 等）使用 upsert 模式：
```java
@Slf4j
@Service
@RequiredArgsConstructor
public class DictService {
    private final DictRepository repository;

    @Transactional
    public void upsertIfNotExists(DictEntity entity) {
        if (repository.existsById(entity.getId())) {
            return;
        }
        repository.save(entity);
        log.debug("inserted new dict, id={}", entity.getId());
    }
}
```
- 返回 `void`，无重复不需要通知调用方
- 只插入不更新（字典数据首次入库后不变）

### 5.3 事务内组合写入模式
数据抓取场景中，需要在一个事务内完成：字典 upsert → 文章写入 → 关联写入：
```java
@Transactional
public boolean saveArticleWithRelations(ClsArticle article,
                                        List<ClsArticleSubject> subjectLinks,
                                        List<ClsArticleStock> stockLinks,
                                        List<Stock> stockDicts,
                                        List<ClsSubject> subjectDicts) {
    if (!saveIfNotExists(article)) {
        return false;  // 文章已存在，跳过整条
    }

    // 1. upsert 字典表（不存在才插入）
    if (stockDicts != null) {
        stockDicts.forEach(stockService::upsertIfNotExists);
    }
    if (subjectDicts != null) {
        subjectDicts.forEach(clsSubjectService::upsertIfNotExists);
    }

    // 2. 写入关联表
    if (subjectLinks != null && !subjectLinks.isEmpty()) {
        subjectRepository.saveAll(subjectLinks);
    }
    if (stockLinks != null && !stockLinks.isEmpty()) {
        stockRepository.saveAll(stockLinks);
    }

    log.info("saved article(id={}) with {} subjects, {} stocks",
            article.getId(), subjectLinks.size(), stockLinks.size());
    return true;
}
```

### 5.4 原则
- 所有写操作必须加 `@Transactional`
- 先 `existsById` 判重，再 `save`
- 父子表/字典表/关联表在同一个事务内写入
- 方法返回 `boolean` 表示是否新增，方便调用方计数

## 六、TaskService / 定时任务模式

定时任务放在所属领域的 `<domain>.task` 包：

### 6.1 标准模板

当 API 返回的数据包含字典表和关联表两层含义时，**必须分开解析**：
- 字典解析：抽取元数据（如股票名称、题材名称），用于写入字典表
- 关联解析：只取业务ID和快照数据，用于写入关联表

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskService {
    private final CommonHttpService commonHttpService;
    private final XxxService xxxService;

    @Scheduled(fixedDelay = 5000)  // 或 fixedRate, cron
    public void fixedDelayTask() {
        // 1. 构建请求参数
        Map<String, Object> params = buildParams();

        // 2. 发起 HTTP 请求，接收 Map 响应
        Map<String, Object> result = commonHttpService.get(
                "https://api.example.com/endpoint", Map.class, params, buildHeaders());

        // 3. 安全解析响应
        Object dataObj = result.get("data");
        if (!(dataObj instanceof Map<?, ?> dataMap)) return;
        Object listObj = dataMap.get("roll_data");
        if (!(listObj instanceof List<?> list)) return;

        // 4. 逐条处理
        int newCount = 0;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            try {
                if (processItem(coerceMap(raw))) {
                    newCount++;
                }
            } catch (Exception e) {
                log.warn("failed to process item, id={}", raw.get("id"), e);
            }
        }
        if (newCount > 0) {
            log.info("saved {} new items from this fetch", newCount);
        }
    }

    // 类型擦除转换
    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceMap(Map<?, ?> raw) {
        Map<String, Object> map = new HashMap<>();
        raw.forEach((k, v) -> map.put(String.valueOf(k), v));
        return map;
    }

    /**
     * 处理单条数据：同时解析字典和关联，传入 Service 统一事务写入
     */
    private boolean processItem(Map<String, Object> item) {
        // ... 解析主表

        // 解析子表：字典 + 关联 分开
        Object subjectRaw = item.get("subjects");
        List<ClsSubject> subjectDicts = parseSubjectDicts(subjectRaw);
        List<ClsArticleSubject> subjectLinks = parseSubjectLinks(subjectRaw, articleId);

        Object stockRaw = item.get("stock_list");
        List<Stock> stockDicts = parseStockDicts(stockRaw);
        List<ClsArticleStock> stockLinks = parseStockLinks(stockRaw, articleId);

        // 一次性事务写入
        return clsArticleService.saveArticleWithRelations(
                article, subjectLinks, stockLinks, stockDicts, subjectDicts);
    }

    // 字典解析：提取元数据
    private List<ClsSubject> parseSubjectDicts(Object raw) { ... }
    private List<Stock> parseStockDicts(Object raw) { ... }

    // 关联解析：只取业务ID + 快照
    private List<ClsArticleSubject> parseSubjectLinks(Object raw, long articleId) { ... }
    private List<ClsArticleStock> parseStockLinks(Object raw, long articleId) { ... }
}
```

### 6.2 安全类型转换工具集（直接复制使用）
```java
// ========== 从 Map 响应安全取值 ==========
private static String asStr(Object val) {
    return val == null ? null : String.valueOf(val);
}

private static int toInt(Object val, int defaultVal) {
    if (val instanceof Number n) return n.intValue();
    if (val instanceof String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
    }
    return defaultVal;
}

private static long toLong(Object val, long defaultVal) {
    if (val instanceof Number n) return n.longValue();
    if (val instanceof String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
    }
    return defaultVal;
}

private static BigDecimal toBigDecimal(Object val) {
    if (val instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
    if (val instanceof String s) {
        try { return new BigDecimal(s); } catch (NumberFormatException ignored) {}
    }
    return null;
}

private static boolean toBool(Object val, boolean defaultVal) {
    if (val instanceof Boolean b) return b;
    if (val instanceof String s) return "true".equalsIgnoreCase(s) || "1".equals(s);
    if (val instanceof Number n) return n.intValue() == 1;
    return defaultVal;
}

private static List<String> parseJsonStrList(Object raw) {
    if (raw instanceof List<?> list) {
        List<String> result = new ArrayList<>();
        for (Object o : list) {
            if (o != null) result.add(String.valueOf(o));
        }
        return result.isEmpty() ? null : result;
    }
    // JSON 字符串数组回退
    if (raw instanceof String str && !str.isBlank() && str.startsWith("[")) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(str, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("failed to parse JSON array: {}", str);
        }
    }
    return null;
}
```

### 6.3 原则
- 用 `instanceof` 模式匹配（Java 21 特性）安全拆箱，避免 `ClassCastException`
- 数值类型用 `Number` 统一接收，再转具体类型
- 每条 item 的解析用 try-catch 隔离，防止单条失败导致整个任务中断
- `coerceMap()` 解决 `Map<?, ?>` 到 `Map<String, Object>` 的类型擦除问题

## 七、Util 工具类模式

领域专属工具放 `<domain>.util`；跨域共享放顶层 `com.zzh.stock_calculator.util`（基包 = 开放 API）：

```java
@Component  // 有依赖注入时
public class XxxUtil {
    private final RestClient restClient;
}

// 或
public final class XxxUtil {  // 纯静态方法
    private XxxUtil() {}
    public static String doSomething(String input) { ... }
}
```

## 八、Config 配置类模式

领域专属配置（如 auth 的 WebConfig / AuthInterceptor）放 `<domain>.config`；全局共享放顶层 `com.zzh.stock_calculator.config`：

```java
@Configuration
public class XxxConfig {
    @Bean
    public RestClient xxxRestClient(RestClient.Builder builder) {
        return builder.baseUrl("https://...").build();
    }
}
```

## 九、HTTP 数据抓取规范

### 9.1 CommonHttpService 用法
```java
// GET 请求（带 query params + headers）
commonHttpService.get(url, Map.class, params, headers);

// GET 请求（query params 作为 URI 模板变量）
commonHttpService.get(url, ResponseType.class, uriVariables);

// POST JSON
commonHttpService.postJson(url, requestBody, ResponseType.class);
```

### 9.2 请求头标准
```java
Map<String, String> headers = new HashMap<>();
headers.put("User-Agent", "Mozilla/5.0 ...");
headers.put("Referer", "https://...");
headers.put("Pragma", "no-cache");
```

## 十、数据库设计规范（参考）

### 主表设计
| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 手动写入或自增 |
| `ctime` | BIGINT NOT NULL | 原始时间戳（秒），用于排序 |
| 状态字段 | VARCHAR(10) DEFAULT 'xxx' | 枚举类状态 |
| 自动时间 | TIMESTAMPTZ | `@CreationTimestamp` 自动填充 |
| JSONB 字段 | JSONB | 数组/对象，Java 用 `List<Xxx>` + `@JdbcTypeCode(SqlTypes.JSON)` |

### 关联子表设计
| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGSERIAL PK | 自增 |
| `父表_id` | BIGINT FK | 引用主表，Java 用 Long 平铺 |
| 业务字段 | 按需 | 关联的业务数据 |
| UNIQUE | `(父表_id, 业务字段)` | 防止重复关联 |

## 十一、环境限制（唯一事实源：stock-calculator-workflow）

终端命令禁 `${...}`、单次内容过长截断、分段写入等限制一律见 `stock-calculator-workflow`，本 skill 不复述（防双源漂移）。

## 十二、模式匹配说明

当遇到以下场景时，自动激活本 skill：

| 场景 | 参考章节 |
|------|----------|
| 新建数据库表对应的 Java 类 | 三、Entity / DTO 模式 |
| 新建主表实体（含完整业务字段） | 三.4.1 主表实体 |
| 新建字典表实体（元数据，upsert 写入） | 三.4.2 字典表实体 |
| 新建关联表实体（只含关系+快照） | 三.4.3 关联表实体 |
| 新建 Repository 接口 | 四、Repository 模式 |
| 新建 Service 业务逻辑 | 五、Service 模式 |
| 创建字典表 upsert Service | 五.2 字典表 upsert 模式 |
| 组合写入主表+字典+关联 | 五.3 事务内组合写入模式 |
| 新建定时抓取任务 | 六、TaskService / 定时任务模式 |
| 从 API 解析 Map 响应并拆分为字典+关联 | 六.1 标准模板（字典/关联分离） |
| 从 API 安全取值 | 六.2 安全类型转换工具集 |
| 创建工具类 | 七、Util 工具类模式 |
| 创建配置类 | 八、Config 配置类模式 |
| 发送 HTTP 请求 | 九、HTTP 数据抓取规范 |
| 设计新表结构 | 十、数据库设计规范 |
| 遇到终端命令报错 / 写入截断 | stock-calculator-workflow（环境限制唯一事实源） |
| 写代码前判断是否偏离框架标准用法 / 发现非标准实现 | 十三、标准实现优先原则 |
| 改动对外契约（API 端点/请求响应/错误码）或域核心流程后收尾 | 十四、文档联动提示 |
| 为边界条件写了非显然逻辑（复合计算/隐式前置/反直觉分支） | 二.6 边界推演防腐注释 |

## 十三、标准实现优先原则（用户约定 2026-09-01）

**一切以框架/官方标准实现为准，这是硬性约定：**

1. **只用标准实现**：优先使用框架官方 API 与标准装配方式（如 Spring AI 官方 API、Spring Boot starter/自动装配）。不发明私有方案、不绕过框架抽象层手写底层调用、不照搬其他版本线的 API 写法。
2. **版本以项目锁定版本为准**：同一框架跨大版本 API 差异极大（例：Spring AI 1.x → 2.x 删除了 `OpenAiApi` 类）。不确定某 API 在当前版本是否存在/签名如何时，先查本地 m2 仓库该精确版本的 sources jar（unzip + grep 源码实证）再动手，不凭记忆或旧示例编码。
3. **发现非标准实现必须打断上报**：新写代码前或阅读既有代码时，一旦发现偏离框架标准用法，先停下向用户说明三件事——① 现状是什么；② 标准做法是什么；③ 维持现状的后果（升级风险 / 维护成本 / 隐性 bug / 吃不到官方修复）。**由用户决定是否调整、如何调整**；未拍板前不擅自重构，也不闷头按错误写法继续。
4. **既有代码 ≠ 正确**：历史代码可能基于旧版本 API 编写，或本身就是错的；不因「一直这么写」而沿用。发现既有代码与标准冲突时，立即按第 3 条打断上报。

## 十四、文档联动提示（建议级）

改动代码触及对外契约（API 端点 / 请求响应结构 / 错误码）或域核心流程 / 架构时，回合末尾附注一行提示用户核对对应文档切片（`docs/<域>/api.md`，或 design / implementation）——建议级：仅提示，由用户确认后手动执行，不擅自改文档。完整规范见 `stock-calculator-docs` skill。