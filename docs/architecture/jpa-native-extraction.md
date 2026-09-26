---
status: draft
updated: 2026-09-26
---

# 抽取 spring-data-jpa 为共享 Native 组件 · 改造方案

> 版本：v1.0（2026-09-26，初版方案，待评审）
> 动机：① JPA native 元数据当前散落于 4 个模块的 `gen-logger-config.py`（main 24KB /
> mcp 27KB / mcp-notify 26KB / orchestration 27KB），已漂移，Hibernate 升版要改 4 处，
> 这是"JPA 问题很容易过不去 native 编译"的根因；② 每次改动都重跑 native-image（8–15 分钟）
> 迭代成本高。本方案把"静态可固化层"收口为共享库、把"动态 classpath 扫描层"收口为单一脚本，
> 并以 JVM 优先验证把 native 降为终闸。
> 范围：仅涉及 native 元数据组织与构建脚本，不改变 JPA 业务语义；DTO 反射仍走
> `ContractRuntimeHints`（contract 模块），不进本组件。
> 先决：GraalVM 25 / Boot 4 / Hibernate 7.3–7.4 已验证；`third_party/graalvm-reachability-metadata`
> 官方仓库（HikariCP 7.0.2 + hibernate-core 7.3.0）维持现状。

## 一、核心约束（决定方案形态）

1. **GraalVM native 元数据分两类**：
   - 静态可固化：框架类（Hibernate 命名/策略/Dialect/id 优化器/BytecodeProvider none/JsonFormatMapper、
     hikari 数组、EntityBatchLoader 数组类、JPA 注解内部类构造器）——版本相关但与应用 classpath 无关，可落共享库。
   - 动态依赖消费应用 classpath：jboss-logging `XxxLogger_$logger`、openai `com.openai.core.**`、
     实际出现的 `XxxJpaAnnotation`——必须在各应用 image 构建期扫 classpath，**挪不进库**。
2. **GraalVM 25 新格式语义**：`queryAllDeclared*` 旧键被静默忽略，显式 `methods` 才让"调用"生效；
   `resources` 段 `pattern`/`glob` 在 consolidated 文件里静默忽略，资源 include 必须走经典
   `resource-config.json`；service 排除走 `resource-config.json` 的 `excludes`（见 native-runtime-metadata §四）。
3. **RuntimeHints 必须挂无条件配置类**：挂条件类且条件 false 时 AOT 不处理，注册丢失（native-build §三踩坑）。
4. **构建耗时结构**：maven compile+process-aot（分钟）≪ native-image 分析（8–15 分钟，无跨构建增量）。
   治本靠"验证左移 JVM、native 只当终闸"，不是脚本能压缩。

## 二、目标架构

```
stock-calculator-jpa/                ← 新增普通 Maven 库模块（非 native 变体）
  ├─ 通用持久化装配（可选，按是否统一 DataSource/EMF/事务/JPA 属性决定）
  ├─ JpaRuntimeHints implements RuntimeHintsRegistrar
  │     · 注册 EXTRA_CLASSES（带 classpath 存在性过滤，等价 extra_on_classpath）
  │     · 注册 hibernate DTD/XSD 资源 include（RuntimeHints.resource）
  │     · 挂 @ImportRuntimeHints 于无条件配置类
  └─ src/main/resources/META-INF/native-image/com.zzh/jpa-config/
        · reachability-metadata.json   （JPA 注解内部类显式构造器，补官方仓库 query* 被忽略的缺口）
        · resource-config.json         （hibernate DTD/XSD includes + BytecodeProvider service excludes）

scripts/agent-tools/gen-native-metadata  ← 单一共享脚本（登记 toolbox，收口 4 份副本）
  · 扫消费应用 target/cp.txt：jboss-logging logger + openai any-setter/core 方法 + JpaAnnotation 补齐
  · 合并 agent-config（运行期缺口保险）
  （openai 段可独立为 gen-openai-metadata，data 已有雏形，与 JPA 解耦）

各消费模块（main / mcp / mcp-notify / orchestration）：
  · 删本地 gen-logger-config.py 副本，依赖 stock-calculator-jpa
  · build-native.sh：调共享 gen-native-metadata + 保留 -H:ConfigurationFileDirectories 官方仓库
  · data 零 DB，不依赖本组件
```

## 三、分阶段改造（断点）

### 断点 P0 — 共享生成器抽取（去重 4 份，不动 native 行为）
- 把 4 份 `gen-logger-config.py` 合并为 **一份** `scripts/agent-tools/gen-native-metadata`（Python，逻辑等同当前生成器：
  jboss-logger 扫描 + openai any-setter/core + JpaAnnotation 补齐 + 合并 agent-config + DTD 资源 +
  BytecodeProvider 排除）。
- openai 段独立为 `gen-openai-metadata.py`（沿用 data 现有 `gen-openai-metadata.py` 思路），与 JPA 解耦。
- 4 个模块 `build-native.sh` 改 `python3 gen-logger-config.py` → 调共享脚本（绝对/相对 `scripts/agent-tools/` 路径）。
- 登记为 toolbox 工具（`toolbox new gen-native-metadata` → `toolbox propose`），符合 AGENTS.md 工具池规则。
- **验收**：4 模块各自 `bash build-native.sh` 产物与抽取前字节级一致（元数据条目数相同）。

### 断点 P1 — 新建 stock-calculator-jpa 共享库（静态层收口）
- 新建 Maven 模块 `stock-calculator-jpa`（父 POM `modules` 追加），`packaging: jar`。
- 落入：`JpaRuntimeHints`（RuntimeHintsRegistrar）+ 提交静态 `reachability-metadata.json` + `resource-config.json`
  （内容平移自当前各 `gen-logger-config.py` 的 EXTRA_CLASSES / DTD pattern / BytecodeProvider excludes；
  保留 classpath 存在性过滤——注册前 `ClassUtils.isPresent` 判存在，Hibernate 改名不致崩）。
- 无条件配置类 `@ImportRuntimeHints(JpaRuntimeHints.class)`，供消费模块 `@Import` 或经 `META-INF/spring/aot.factories`。
- **验收**：本模块 `mvn test`（如有）编译过；JSON 经 javap/字节级抽查关键类（PhysicalNamingStrategyStd、BytecodeProviderImpl、UUID[]）齐全。

### 断点 P2 — 消费模块接入（试点先行）
- 顺序：**orchestration（6 repo，最小 JPA 面）先做 spike** → main（≈40 repo，全量回归）→ mcp → mcp-notify。
- 每模块：加 `stock-calculator-jpa` 依赖；删本地 `gen-logger-config.py`；`build-native.sh` 改调共享脚本 +
  保留官方仓库 `ConfigurationFileDirectories`；如采用库内通用持久化装配则迁移 DataSource/EMF/事务/JPA 属性。
- **验收（每模块）**：见 §四。

### 断点 P3 — CI 收口与文档联动
- CI job 统一调 `toolbox run gen-native-metadata`；`third_party/graalvm-reachability-metadata` 引用收口为单一变量。
- 更新 native-build / native-runtime-metadata 两个 SKILL：JPA 元数据统一来源改为 `stock-calculator-jpa` +
  共享脚本；删"每模块各持生成器"旧描述。
- README / docs/architecture 补"JPA native 组件"一节；本方案 `status` 转 active。

## 四、验证策略（JVM 优先，native 终闸）

**原则：每处改动默认不跑 native-image；只在阶段收尾跑一次确认。**

1. **装配/wiring 验证（分钟级，无需 native）**：
   ```sh
   ./mvnw -q -pl <模块> -am test          # contextLoads 复现 BeanCreationException 链
   ```
   连不上库报 `Unable to determine Dialect` 属 DB-less 假象，非代码 bug（native-build §三）。
   真验 JPA 上下文：给 test 挂 Postgres（Testcontainers 或嵌入式），比起动一次 native 便宜一个数量级。
2. **仅改元数据/生成器**：`bash build-native.sh --no-pkg`（跳过 maven，只重跑 native-image；仍 8–15 分钟，
   但省 maven 那几分钟）。
3. **改了 Java**：必须 clean 全量（增量混编炸 AOT，native-runtime-metadata §八.5）；该阶段只跑一次。
4. **native 终闸**：`bash build-native.sh` 全量 + 8s 冒烟 + 90s 加长（`timeout 90 ./target/<bin> --server.port=19997`，
   标准：AOT 亚秒启动、Hikari 连库、EMF 无 WARN、补录三段日志、零 ERROR）。
5. **P0 去重回归**：抽取前后元数据条目数一致（生成器末行打印的 reflection entries 数对比）。

## 五、风险与回滚

| 风险 | 缓解 / 回滚 |
|---|---|
| Hibernate 跨大版本类名变，EXTRA_CLASSES 失效 | 注册保留 classpath 存在性过滤；升版只改 `stock-calculator-jpa` 一处；回滚 = 模块删依赖、恢复本地生成器 |
| 共享脚本路径在各子目录调用失败 | 用仓库相对路径 `scripts/agent-tools/`，CI 与本地同基线；先 P0 字节级比对兜底 |
| `JpaRuntimeHints` 误挂条件类致注册丢失 | 坚持无条件配置类（native-build §三）；process-aot 产物校验 `target/spring-aot/main/classes` 含 initializer |
| native-image 无跨构建增量，单轮仍 8–15 分钟 | 靠 §四 JVM 优先把轮次降到最少；不为省时牺牲 `--no-fallback` |
| agent 录制产物随应用而异，塞库失效 | agent-config 维持各应用本地/提交，不进共享库 |

## 六、验收标准（整体）

- [ ] `gen-logger-config.py` 4 份副本删除，统一为 `scripts/agent-tools/gen-native-metadata`（+ openai 独立）；
- [ ] `stock-calculator-jpa` 库含 `JpaRuntimeHints` + 提交静态元数据，4 个 JPA 模块均依赖；
- [ ] 4 模块 native 构建全绿（8s 冒烟 + 90s 加长，零 ERROR），且 Hibernate 升版只需改 1 处；
- [ ] 单处 JPA/wiring 改动可在 JVM（contextLoads + Postgres test）分钟级验证，无需每改必 native；
- [ ] SKILL 文档与 README 同步"JPA native 组件"统一来源。

## 七、待评审决策点

- D1：`stock-calculator-jpa` 是否顺带统一通用持久化装配（DataSource/EMF/事务/JPA 属性），还是只承载 native 元数据？
  （建议：先只承载元数据，通用装配另立 epic，降低本次改动面。）
- D2：共享脚本以 toolbox 工具还是 `scripts/agent-tools/` 普通脚本收口？（建议：toolbox 工具，符合 AGENTS.md。）
- D3：`JpaRuntimeHints` 与提交 JSON 是否双轨并存？（建议：RuntimeHints 为主、JSON 补资源 exclude 与注解内部类，
  二者并集由 native-image 自动合并。）
