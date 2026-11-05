# 扩展指南

本文件回答一个问题：**想加/换一块能力，改哪里、按什么规矩改、怎么证明改成了。**

所有外部能力都收在接口后面，业务代码只依赖接口。当前一共 7 个扩展点，
每个都给出「接口 → 现有实现 → 配置键 → 扩展步骤 → 验证方式」。

---

## 0. 扩展点总览

| 能力 | 接口 | 现有实现 | 配置键 | 扩展方式 |
|---|---|---|---|---|
| 模型 | `spi/ai/LlmProvider` | `TemplateLlmProvider`、`OpenAiCompatibleLlmProvider` | `healthos.ai.provider` | 加实现类 + 由 `AiConfig` 按 provider 装配 |
| OCR | `spi/ocr/OcrProvider` | `HttpOcrProvider`（generic/paddle）、`DisabledOcrProvider` | `healthos.ocr.provider` | 加实现类 + `OcrConfig` 加分支 |
| 知识库 | `spi/retrieval/KnowledgeBase` | `LocalKnowledgeBase`、`RagflowKnowledgeBase`、`HybridKnowledgeBase` | `healthos.retrieval.provider` | 同上，`RetrievalConfig` 装配 |
| 图存储 | `spi/graph/GraphStore` | `JdbcGraphStore`、`Neo4jGraphStore`、`DualGraphStore` | `healthos.graph.store` | 同上，`GraphConfig` 装配 |
| 对象存储 | `spi/storage/ObjectStorage` | `LocalObjectStorage` | `healthos.storage.type` | 加实现类 + 开关 |
| 问答分支 | `spi/qa/AnswerBranch` | `EvidenceBranch`、`TcmBranch`、`ModernBranch` | `healthos.qa.mode` | **加 `@Component` 即自动生效**，无需改装配 |
| 流水线节点 | `model/classics/PipelineNodeSpec` | 五节点枚举 | — | 加枚举值 + 在 `PipelineOrchestrator` 加执行分支 |
| 观测写入器 | `spi/tracing/TraceWriter` | `AsyncTraceWriter`、`DirectTraceWriter` | `healthos.tracing.writer` | 加实现类 + `TracingConfig` 加分支 |
| 观测埋点 | `service/tracing/TracingService` | 流水线五节点、问答、图查询已埋 | `healthos.tracing.enabled` | 直接调 `span`/`event`，跨线程用 `callIn` |
| 患者状态机 | `service/patient/PatientLifecycleService#ALLOWED` | `DRAFT/ACTIVE/ARCHIVED/DELETED` | — | 改流转表 + 加 `TransitionRequest` 动作 |
| 持久化问答运行 | `service/qa/run/QaNodeSpec` | 由 `AnswerBranch` 自动派生 | `healthos.qa.run-timeout` | 加分支即多一个节点，无需改动编排 |
| 患者知识库 | `service/qa/KnowledgeBindingService` | 绑定/解绑/校验 + 检索合并 | — | 表已在，扩展点是"绑定哪种库"，见 §8.9 |
| 文档导出 | `service/care/CarePdfRenderer` | JSON + PDF（内嵌中文） | `healthos.care.pdf.font-path` | 换字体只改配置；换版式改渲染器，见 §8.95 |
| 模型能力探测 | `spi/routing/RouteProbe` | `HttpRouteProbe`（四类合成探测） | `healthos.model-routing.*` | 加实现类，契约自带类型，见 §8.97 |
| 智能体角色执行器 | `spi/agent/AgentRoleExecutor` | `TemplateAgentRoleExecutor`（确定性模板，不调模型） | — | 加 `@Component` 实现即接管该角色，见 §8.98 |

问答分支是唯一**不需要改配置类**的扩展点——`BranchOrchestrator` 注入的是 `List<AnswerBranch>`，
Spring 收集到几个就跑几路。

---

## 1. 加一路问答分支（最常用）

### 场景

想加「经络穴位」「现代药理」「古籍异文对勘」等视角。

### 步骤

1. 新建 `src/main/java/com/kangheng/healthos/spi/qa/XxxBranch.java`：

```java
@Component
public class MeridianBranch implements AnswerBranch {

    @Override
    public String key() { return "meridian"; }        // 写进响应与落库 JSON

    @Override
    public String label() { return "经络"; }           // 汇总时的段落标题

    @Override
    public int order() { return 25; }                  // 10 原文 / 20 图谱 / 30 指标

    @Override
    public BranchResult answer(BranchContext context) {
        long started = System.nanoTime();
        // 1) 没有依据就明确返回，不要编
        if (context.graph() == null) {
            return BranchResult.insufficient(key(), label(), "没有图谱", elapsed(started));
        }
        // 2) 有依据就返回答案 + 逐条引用（引文必须逐字来自原文）
        return BranchResult.succeeded(key(), label(),
                BranchResult.BASIS_RETRIEVED_EVIDENCE, "……", citations, elapsed(started));
    }
}
```

2. **不用改任何配置类、控制器、契约**。重启后：

```bash
curl -s -b cookie.txt http://127.0.0.1:18100/api/v1/qa/branches
# [{"key":"ragflow",...},{"key":"tcm",...},{"key":"meridian",...},{"key":"modern",...}]
curl -s http://127.0.0.1:18100/health/dependencies
# "qaBranches":["ragflow","tcm","meridian","modern"]
```

3. 单测放 `provider/qa/XxxBranchTest`，照 `TcmBranchTest` 写：
正常命中、无依据、前置缺失三种情况各一条。

### 硬约束（编排器不会替你兜底）

- **不许有副作用、不许写库**：落库统一由 `QaService` 做，分支写库会导致重试时重复落库。
- **不许吞异常后返回空字符串**：那是"假装成功"。要么返回 `INSUFFICIENT`，要么抛异常（转 `FAILED`）。
- **引用必须逐字可回溯**：`Citation.quote` 只能是原文/证据片段，禁止改写后挂出处。
- **耗时自己控**：`healthos.qa.branch-timeout`（默认 20s）是硬超时，超时即判该路失败。

### 预取共享，不要各查各的

`BranchContext` 里的 `hits` / `graph` / `metrics` 是 `QaService` 预取好的**同一份事实**。
新增分支先用这些字段；确实需要新数据源时，在 `QaService#askParallel` 的预取段加一次查询，
不要在每个分支里自己连库——三路并发各查一次会让单次问答的 IO 翻三倍，且三路可能看到不一致的数据。

---

## 1.5 加分支到持久化问答运行（qa run）

### 场景

按第 1 节加了 `MeridianBranch` 之后，它已经能进同步问答。
但**持久化 run**（`POST /api/v1/qa/runs`）是另一条路：它把问答拆成节点逐个落库、支持断点续跑。
新分支要不要也进这条链？

### 要点：节点是从分支派生的，不是手写枚举

`service/qa/run/QaNodeSpec` 只声明了两个固定节点：

```java
public final class QaNodeSpec {
    public static final String RETRIEVAL = "retrieval";  // 检索（冻结上下文时已完成）
    public static final String SUMMARY   = "summary";    // 汇总
    public static List<String> ordered(List<String> branchKeys) { ... }
    public static boolean isBranch(String key) { ... }
}
```

**分支节点是从 `AnswerBranch` 列表自动派生的**——`QaRunService` 提交时按 `branch.order()`
给每个分支建一个 `qa_run_node` 行。所以：

| 你做的事 | 需要改什么 |
|---|---|
| 加一个 `MeridianBranch`（`@Component`） | **什么都不用改**——run 里自动多一个 `meridian` 节点 |
| 想给某个分支特殊重试策略 | 改 `QaRunNodeExecutor`（目前是全局 `healthos.qa.node-max-attempts`） |
| 想加一个**非分支**的节点（比如"查证引用有效性"） | 在 `QaNodeSpec` 加常量，并在 `QaRunService#execute` 的节点循环里加分支 |

### 新节点必须遵守的三件事

1. **幂等**：节点可能被重跑（`attempt_count` 递增）。写库要么用 `ON CONFLICT DO NOTHING`，
   要么先查后写——不能靠"跑一次"来保证不重复。
2. **可重入**：节点读到的是**冻结上下文**（`context_snapshot`），
   不是当前状态。重跑时上下文不变，但外部依赖（模型、知识库）可能已经变了。
   所以节点的输出必须写进 `qa_run_node.result`，供后续节点与重试复用。
3. **失败要能被分类**：抛异常时带上 `errorCode`（如 `QA_BRANCHES_INCOMPLETE`），
   否则重试时无法区分"可重试的临时故障"与"数据问题"。

### 汇总节点是特例

`summary` 节点读的是**各分支节点的落库结果**，不是自己重跑一遍分支。
所以只要你新增或改动了分支，汇总规则也要跟着走——
两边共用 `service/qa/BranchAggregator`，**不要在 run 侧另写一份汇总逻辑**，
否则"同步问一次"和"跑一个 run"会给出两个不同的答案。

改完记得跑一遍断点续跑的实测（README §3.9 有一份现成的命令清单）。

---

## 2. 接真实大模型

```bash
HEALTHOS_AI_PROVIDER=openai \
HEALTHOS_AI_BASE_URL=https://api.deepseek.com/v1 \
HEALTHOS_AI_API_KEY=sk-xxx \
HEALTHOS_AI_MODEL=deepseek-chat
```

`OpenAiCompatibleLlmProvider` 已处理：JSON 结构校验、413（请求过大）转二分、空输出报
`LlmException` 而不是返回空串。想换非 OpenAI 协议（如某厂商私有 HTTP），加一个 `LlmProvider`
实现并在 `AiConfig` 加分支即可，业务侧零改动。

**验证**：`curl -s /health/dependencies | grep llmProvider` 应变成你的 provider 名；
再跑一次上传，看 `tcm_extract` 节点 detail 里的 `entities/relations` 是否合理。

---

## 3. 加向量检索（本地 embedding）

现状：向量检索与重排由 RAGFlow 承担（与原工程一致），本地模式是中文二元组打分。
若要在**不引入 RAGFlow** 的前提下做向量召回，按下面三步，且不要改动 `QaService`：

1. 建表（`schema.sql`，注意幂等）：

```sql
CREATE TABLE IF NOT EXISTS chunk_vector (
    chunk_id   VARCHAR(64) PRIMARY KEY,
    document_id VARCHAR(64) NOT NULL,
    embedding  REAL[] NOT NULL,
    model      VARCHAR(80) NOT NULL
);
```

2. 新增 `spi/retrieval/EmbeddingProvider` 接口（`embed(String) -> float[]`），
   实现放 `provider/retrieval/`：本地 hashing（无需模型，确定性、可离线）与 OpenAI 兼容 `/v1/embeddings`。
3. 在 `LocalKnowledgeBase` 内改打分来源：有向量则按余弦排序，没有则回落现在的二元组打分。
   `KnowledgeBase#retrieve` 签名不变，因此 `QaService`、`EvidenceBranch`、`HybridKnowledgeBase` 全都不用动。

装了 pgvector 扩展可以把 `REAL[]` 换成 `vector(1024)` 并建 `ivfflat` 索引；
SQL 里换成 `ORDER BY embedding <=> ?::vector` 即可，应用层代码只多一个 `CAST`。

**验证**：同一问题下，引用顺序应与词面模式不同（且人工核对结论仍成立）；
`EXPLAIN ANALYZE` 确认走的是向量索引而不是全表。

---

## 4. 换对象存储（MinIO / S3）

实现 `ObjectStorage`（四个方法：`put` / `get` / `delete` / `healthy`），在装配处按
`healthos.storage.type` 选择。注意两点：

- `healthy()` 必须是**廉价**探活（列一个固定前缀或 HEAD 一个固定 key），不要遍历桶；
- 读失败要抛异常而不是返回空字节数组，否则上游会把"存储坏了"当成"文件是空的"。

**验证**：`/health/dependencies` 的 `objectStorage` 为 `ok`；上传后能从新存储读回同一份字节。

---

## 5. 换图存储

实现 `GraphStore` 的五个方法。硬约束只有一条：**每条边必须挂到一条能定位到原文的证据**
（`PersistResult.droppedRelations` 会记录丢弃数）。这条不因换存储而放松。

若新存储支持原生多跳，`neighbors(documentId, entityId, depth)` 直接下推；
否则照 `JdbcGraphStore` 做双向 BFS。`depth` 必须在实现内部做上限校验
（Cypher 的跳数不能参数化，只能拼字符串，越界即注入风险）。

**验证**：`depth=1` 与 `depth=2` 的节点数应严格递增；`dropped_relations` 不应异常升高。

---

## 6. 换消息队列

`OutboxDispatcher` 现在是 `@Scheduled` + `FOR UPDATE SKIP LOCKED` 认领。
换成 Kafka/RabbitMQ 时**保留 outbox 表**：写入方仍在一个事务里写业务表 + outbox，
只把"投递"换成 MQ 生产，消费侧复用 `ClassicIngestHandler`。

不要改成"先发 MQ 再写库"——那是把事务一致性换成分布式一致性问题，本项目的取舍是反过来的。

**验证**：停掉 MQ，业务写入仍然成功（outbox 积压不丢）；恢复后积压事件被消费完。

---

## 7. 加业务域（照 classics 抄）

源工程有 patient / memory / agent / tracing 多个域，本切片只留 classics + patient。
本工程按**技术角色**分包（`{角色}/{业务域}`），所以加新域是"在每个角色目录下加一个域名子包"：

```
controller/<domain>/    控制器（只做参数校验 + 调 service）
contract/<domain>/      对外 DTO（record）
model/<domain>/         实体（MyBatis-Plus，@TableName 必须写死表名）
mapper/<domain>/        Mapper（复杂 SQL 放 XML，不写在 Java 里）
service/<domain>/       业务逻辑
worker/<domain>/        异步处理（可选）
```

三件事必须同时做，否则重启就会报错或行为不一致：

1. `schema.sql` 加建表语句，**必须幂等**（`IF NOT EXISTS`；加列用 `ADD COLUMN IF NOT EXISTS`）；
2. outbox 的事件类型注册到 `ClassicIngestHandler` 同类处理器，并确认 dispatcher 能路由；
3. `HealthController` 若有新的外部依赖，按「未启用记 disabled、声明了不可达记 degraded」加一项。

---

## 8. 加流水线节点

`PipelineNodeSpec` 的**枚举顺序就是执行顺序**，`PipelineOrchestrator` 里对应一个 `runXxx` 方法。
加节点时：

1. 枚举里插入（注意：插在中间会改变已有文档的节点序列，检查点按"节点 code + 区间"匹配，
   不受序号影响，但前端时间轴会变）；
2. 在 orchestrator 里加执行分支，**必须**写 `detail`（至少包含输入规模、产出规模、耗时）；
3. 节点失败要区分可重试/不可重试（走 `RetryPolicy` 三分类），不要一律 `catch` 掉；
4. 补一条单测，覆盖「成功」与「可重试失败」两条路径。

---

## 8.5 接观测（tracing）

### 埋一个 span

```java
// 有返回值：包一层即可，异常会自动把 span 置 FAILED 再抛出
List<Chunk> hits = tracing.span("knowledge.retrieve", "retrieval", "knowledge-base",
        Map.of("documentId", documentId, "topK", topK),
        () -> knowledgeBase.retrieve(documentId, question, topK));

// 无返回值
tracing.span("manifest", "business_stage", "pipeline", () -> { runManifest(...); return null; });

// 只有事实没有耗时：走事件，不要造一个 0ms 的假 span
tracing.event("pipeline_counts", Map.of("entities", 20, "relations", 12));
```

`kind` 是给聚合用的粗分类，建议只用这几个：`business`（根）、`business_stage`、`model`、
`retrieval`、`graph`、`storage`。乱造 `kind` 会让 `/overview` 的 `byService` 失去可比性。

### 跨线程必须搬 Frame

```java
// 错：线程池里的 span 会挂到空上下文上，直接消失
executor.submit(() -> doBranch());

// 对：显式搬运，call(frame, supplier) 会在子线程设置上下文并在 finally 还原
TraceContext.Frame frame = TraceContext.current();
executor.submit(() -> tracing.callIn(frame, () -> doBranch()));
```

`BranchOrchestrator` 就是标准写法：提交任务前抓一次 `Frame`，每个分支体内再包自己的 `span`。
这是目前**唯一**的跨线程方式，也是跨进程断链的根因（没有传 `traceparent` 头）——
拆服务时要换成从 HTTP 头解析并重建 `Frame`。

### 收尾只传业务状态

```java
tracing.finishTrace("OK");                        // 折算成 SUCCEEDED，outcome=OK
tracing.finishTrace("INSUFFICIENT_EVIDENCE");      // 折算成 SUCCEEDED（流程跑完了）
tracing.finishTrace("FAILED", code, message);      // 折算成 FAILED
```

**别自己往 `trace_run.status` 里写业务词**——那一列是健康度（`RUNNING/SUCCEEDED/FAILED/INTERRUPTED`），
要能被 `GROUP BY` 和算失败率。业务侧原生词由 `finishTrace` 落到 `outcome` 列。

### 换写入器

`TraceWriter` 只有三个方法（`submit`/`flush`/`stats`）。要发到 Kafka / OTLP gRPC：

1. 实现 `TraceWriter`，`submit` **必须快速返回且不抛异常**（观测失败不许影响业务）；
2. `stats()` 要能让 `/health/dependencies` 显示 `ok(dropped=N)`，否则丢了轨迹没人知道；
3. 在 `TracingConfig` 里加一个 `writer` 分支（现在只有 `async` / `direct`）。

不想改代码也能导出：`GET /api/v1/traces/{traceId}/export` 给的是 OTLP/JSON，
`startTimeUnixNano` 是**字符串**纳秒（数字会丢精度），`kind` 是整数枚举，可直接投喂 Collector。

---

## 8.6 扩患者域

### 加状态流转

`PatientLifecycleService` 顶部的 `ALLOWED` 是唯一事实来源，改它就改了状态机：

```java
private static final Map<String, Set<String>> ALLOWED = Map.of(
        "DRAFT",    Set.of("ACTIVE"),
        "ACTIVE",   Set.of("ARCHIVED", "DELETED"),
        "ARCHIVED", Set.of("ACTIVE", "DELETED"),
        "DELETED",  Set.of());          // 终态，不允许"复活"
```

再加一个 `POST /patients/{id}/xxx` 动作方法，**必须带 `expectedRevision`**——
乐观锁是并发修改的最后一道，去掉它等于允许两人同时改同一份病历互相覆盖。

### 写一个同步写接口

用 `LambdaUpdateWrapper` 把版本号写进 `WHERE`，靠"影响 0 行"判定冲突，别先查后写：

```java
int rows = patientMapper.update(null, new LambdaUpdateWrapper<Patient>()
        .eq(Patient::getId, patientId)
        .eq(Patient::getRevision, expectedRevision)   // ← 关键
        .set(Patient::getName, newName)
        .set(Patient::getRevision, expectedRevision + 1));
if (rows == 0) throw BizException.conflict("PATIENT_REVISION_CONFLICT", "版本冲突…");
```

> 单测里 `LambdaUpdateWrapper.set` 会抛 `can not find lambda cache for this entity`
> （没有 MyBatis 上下文），所以 `transition` 改用字符串列名的 `UpdateWrapper`。
> 生产路径仍是带 `revision` 条件的原子更新，别为了"统一风格"把条件去掉。

### 加一类可删除资源

删除预览要能算全资源，否则台账不完整。新增资源类型时：

1. 在 `PatientDeletionService.collect` 里加一类，写清 `kind` 与 `scope`（`scope` 是给人看的定位信息）；
2. 在 `process` 的擦除分支里加对应 `erase`，**逐条记录 `erased`**，不要整批一把梭；
3. 资源若在对象存储，`key` 要能被 `PatientOrphanService` 反查到引用，否则会被误判为孤儿。

台账落对象存储 + `sha256`，回读时重算比对（`verified=false` 即内容被改过）——
这是"删除确实发生过"的唯一证据，别绕过。

## 8.65 扩资料录入（patient intake）

资料录入的形状是：**上传 → OCR 归一化 → 分片抽取 → 两道人工核查 → 生成档案**。
要动它，先记住三条不可动摇的约束：

1. **抽取器不许猜值**。原文里没有的字段一律不出现在 `fields` 里——
   缺字段是"待人工补"，不是"模型猜一个"。
2. **两道核查都通过才能建档**。不设"AI 直接建档"的捷径。
3. **每片独立成败，但只要有一片失败，档案就不许确认**。残缺的档案比没有档案更危险。

### 换抽取实现

`PatientRecordExtractor` 是 SPI。默认实现是 `RuleBasedPatientRecordExtractor`
（离线、确定性、可逐条断言），CI 里跑的就是它。要换成模型实现：

```yaml
healthos:
  patient:
    intake:
      extractor: llm      # rule-based（默认） | llm
```

`LlmPatientRecordExtractor` 已经实现，它**只做"抄"**：找不到就留空，
解析后逐字段核对是否真能在原文里找到。即使开了它，人工核查两道仍然是硬要求。

### 加一个指标

指标规则集中在 `PatientIntakeFields.METRIC_RULES`，一条规则一个指标码：

```java
new MetricRule("CRP", "C反应蛋白", "mg/L",
        List.of(Pattern.compile("C\\s*反应蛋白\\s*[:：]?\\s*" + VALUE)),  // ← 复用 VALUE
        null, "8.0"),
```

> `VALUE` 里那个 `(?![\d.])` 边界断言**不能省**。少了它，
> "血糖 5.60" 会被截成 "5.6"、"身高 1700cm" 会被截成 "170"——读错数据比抽不到更危险。

**参考区间是"常用成人参考值"，不是诊断标准。**真正的诊断阈值要看指南、
要看实验室方法学、要看个体情况。所以这里只做"偏离常用区间的提示"，
异常不等于确诊——这是医疗软件的边界，改区间前先想清楚这句话。

### 标签有歧义时怎么办

同一个词在不同语境下含义不同（"餐后血糖" vs "空腹血糖"），
参考区间不同，混了就是误判。这种情况用 `rejectBefore`：

```java
new MetricRule("FBG", "空腹血糖", "mmol/L",
        List.of(Pattern.compile("血\\s*糖\\s*[:：]?\\s*" + VALUE)),
        "3.9", "6.1", List.of(REJECT_POSTPRANDIAL, REJECT_RANDOM)),
```

`rejectBefore` 是**紧邻标签的禁忌前缀**（用正则描述，按前缀尾部匹配）。
写成字符串列表穷举不现实——"餐后2小时"、"餐后两小时"、"餐后 2 h" 变体太多，
而且中间可能有空格。正则负向前查也覆盖不全，所以做成"在匹配位置上判定前缀尾部"。

前缀离标签远的时候不误伤：`"主诉：餐后不适，今日血糖 5.6"` 里的 "餐后" 不否决。

### 加分片

分片定义在 `PatientIntakeFields.PARTS`，每项是"键 + 中文名 + 标题词"：

```java
new Part("allergy_history", "过敏史", List.of("过敏史", "药物过敏")),
```

标题词用于在正文里定位分片。匹配容忍 markdown 标题符号、全角冒号、中文序号
（`一、`）、加粗（`**主诉**`）——这些在真实病历里都很常见。

**没被任何标题命中的内容归 `general` 分片**，保证不丢信息。

### 抽不到的字段怎么补

抽取器不许猜值，OCR 也确实会抽不到（实测把「王五」认成「王 :」）。
所以确认建档时允许补录：

```json
POST /api/v1/patient-intakes/{id}/confirm
{"expectedRevision": 1, "name": "王五"}
```

补录优先于抽取结果，且**来源标注为 `manual`** 写回 `aggregate.fieldSources`——
日后看到"姓名=王五"能直接分辨是人补的还是机器抄的。

## 8.7 加带归属的数据（owner 隔离）

患者域的每一张表都带 owner，查询必须带上它。Java 侧没有 SQLAlchemy 那种 Session 级
统一注入，**规则是"每个查询显式带 owner 条件"**，漏一处就是一次越权读。

### 加一张带归属的表

1. 建表时直接写 `owner VARCHAR(200)`，并在 `schema.sql` 里补一条
   `ALTER TABLE ... ADD COLUMN IF NOT EXISTS owner VARCHAR(200)`（老库兼容）；
2. 紧接着写回填：从父表继承 owner，父表没有归属人时退化成 `'system'`；
3. **索引放在补列与回填之后**——老库里 owner 列是 ALTER 加的，先建索引会报
   `column "owner" does not exist`；
4. 实体加 `private String owner;`。

### 读的时候

```java
// 用户态入口：带 owner 过滤，越权自然查不到
Patient patient = patientMapper.selectOne(new LambdaQueryWrapper<Patient>()
        .eq(Patient::getId, patientId)
        .eq(Patient::getCreatedBy, owner)
        .last("limit 1"));

// 后台 worker 入口：按 ID 派发任务，worker 并不知道调用者是谁，不做 owner 过滤
Patient patient = patientMapper.selectById(patientId);
```

**这两条路径要分开写**，别图省事只留一条：

- 用户态入口查不到 → 抛 `BizException.notFound(...)`，即 `404`。
  **不要抛 403**：403 等于告诉对方"这个 ID 确实存在"，那本身就是信息泄露。
- 后台入口不能带 owner —— outbox 任务按 ID 派发，worker 处理的是系统级动作。

### 单测怎么断言"确实带了 owner"

不写"调用没报错"这种断言——漏了条件它照样不报错。要检查**真正下发的 WHERE 子句**：

```java
@BeforeAll
static void initMybatisMeta() {
    // 单测里没有 MyBatis 上下文，lambda 条件渲染 SQL 时会抛
    // "can not find lambda cache for this entity"，先手工初始化实体元数据
    TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), Patient.class);
}

@SuppressWarnings("unchecked")
ArgumentCaptor<Wrapper<Patient>> captor = ArgumentCaptor.forClass(Wrapper.class);
verify(patientMapper).selectOne(captor.capture());
AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
assertThat(wrapper.getSqlSegment()).contains("created_by");
assertThat(wrapper.getParamNameValuePairs().values()).contains("bob");
```

### 权限白名单

两类显式例外，都在 `healthos.security.*` 里配：

| 白名单 | 用途 |
|---|---|
| `admin-users` | 运维接口：孤儿扫描/清理、观测保留策略清理 |
| `trace-global-users` | 轨迹全局读（轨迹里含问答原文与患者 ID） |

**没有"超级用户绕过一切"的隐式后门**。要加新角色就往白名单里加，别在业务代码里写 `if (user.equals("admin"))`。

---

## 8.8 改认证（口令、令牌、权限）

### 一套三段式，改动点各自独立

| 关心什么 | 改哪里 | 断言它的测试 |
|---|---|---|
| 怎么证明"我是谁" | `service/auth/AuthService` + `PasswordHash` | `AuthServiceTest` / `PasswordHashTest` |
| 令牌长什么样、能不能吊销 | `service/auth/SessionToken` + `SessionRevocationRegistry` | `SessionTokenTest` / `SessionRevocationRegistryTest` |
| 谁能干什么 | `common/AccessControl#rolesOf` | `AccessControlTest` |
| 失败太多次怎么办 | `service/auth/LoginThrottle` | `LoginThrottleTest` |
| Cookie 怎么下发 | `controller/auth/SessionCookie` | `SessionCookieTest` |

### 加一个角色

只改 `AccessControl`，不要碰令牌：

```java
// common/AccessControl.java
public List<String> rolesOf(String user) {
    ...
    if (isCareReviewer(user)) {
        roles.add(UserPrincipal.ROLE_CARE_REVIEWER);   // 常量也定义在 UserPrincipal
    }
    ...
}
```

**不要**把角色写进令牌载荷。写进去的那一刻，"撤销权限"就变成了"等令牌过期"——
默认 TTL 8 小时，这段时间权限已经撤了但请求仍然放行，而且不报任何错。

### 换口令存储（接 LDAP / OAuth）

`AuthService#lookup` 是唯一的凭据来源，把它换掉即可：

```java
private Optional<Credential> lookup(String username) {
    // 改成远程校验时，注意两点：
    // ① 远程调用要设超时，否则认证链路会把登录接口一起拖死；
    // ② 远程不可用时返回 empty（拒绝登录），不许"降级为放行"。
}
```

新写法仍需满足 `PasswordHash.matches` 的契约，或者干脆绕过它、在 `login` 里直接比对。

### 吊销名单换成共享存储（上多实例之前必做）

内存版在单实例下够用，但它有两个已知边界，别踩到了才发现：

1. **重启即清空**：重启前登出的令牌，重启后又能用到 `exp`；
2. **多实例不共享**：A 节点登出，Token 拿到 B 节点仍然有效——这等于登出失效。

换成表或 Redis 时，实现这五个方法就够（`revoke` / `isRevoked` / `sweep` / `size`），
调用方 `AuthService` 不需要改：

```sql
CREATE TABLE IF NOT EXISTS session_revocation (
    jti         VARCHAR(64) PRIMARY KEY,
    subject     VARCHAR(128) NOT NULL,
    expires_at  TIMESTAMP(6) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_session_revocation_expires ON session_revocation (expires_at);
```

清理按 `expires_at` 走保留策略，和 `trace_run` 那套保持一致。

### 令牌版本升级

`SessionToken.VERSION` 是硬开关：结构一改就加一，旧版本载荷自动失效。
这是有意的——**旧结构往往缺 `jti`，放行等于留一个"登出杀不掉"的口子**。
升级的代价是所有人重新登录一次，写进发布说明即可。

### 别做的事

| 别做 | 原因 |
|---|---|
| 用 `==` 或 `String.equals` 比口令/签名 | 短路比较会泄露前缀匹配长度；用 `MessageDigest.isEqual` |
| 把失败原因分成"账号不存在"和"口令错误" | 接口本身就成了账号枚举器 |
| 在 `/auth/me` 里硬编码 `roles=["ADMIN"]` | 角色不再随配置变化，撤销权限永远不生效（本工程曾这样，已改） |
| 只清 Cookie 就算登出 | 抄走的令牌还能用到过期 |

---

## 8.9 扩患者知识库绑定

### 场景

医生问的是"这位患者怎么样"，依据一半在典籍、一半在该患者自己的随访记录里。
患者资料通常已经作为一份文档进了知识库，要做的就是**把这份库和患者关联起来**，
让 `/qa/ask` 带 `patientId` 时一并检索。

### 步骤

1. 患者资料照常走典籍上传（`POST /api/v1/classics`），流水线会为它建一个数据集；
2. `GET /api/v1/qa/patients/{patientId}/knowledge-bases` 看 `available` 里的 `datasetId`；
3. `POST` 同路径带 `{"datasetId": "...", "note": "..."}` 完成绑定；
4. 以后 `POST /api/v1/qa/ask` 带 `patientId`，命中会自动合并。

`bind` 会先校验 `datasetId` 确实存在于 `knowledgeBase.datasets()`——**不存在的库不许绑**，
否则绑上去的是一条永远查不到东西的死链。

### 四条实现约束

| 约束 | 理由 |
|---|---|
| 合并按**正文内容哈希**去重 | 本地切片与 RAGFlow 切片的 `chunkId` 各成一套，同一段正文 ID 不同。按 ID 去重会让模型以为"多处提到"，实际只有一处 |
| **不做跨来源重排** | 两种相似度不是同一把尺子。硬排出来的序是编的，且没人能解释为什么第 3 条排在第 1 条前面 |
| 外部 `document_id` 映射回本系统 `documentId` | 否则引用只剩一个点不开的外部 ID。映射用 `knowledge_index.external_doc_id`，查不到留 `null` |
| 检索失败**只降级** | `augment` 抛异常会把整次问答打挂。写 `WARN` 日志 + 本次仅用文档依据，但**不能静默变成"这位患者没有资料"** |

### 单测怎么写

`KnowledgeBindingServiceTest` 已给出模式：mock `bindingMapper` 与 `KnowledgeBase`，
断言合并后的 `chunkId` 序列与 `documentId` 各自独立。
涉及跨库回填偏移的，看 `HybridKnowledgeBaseTest`——它专门钉住"每条按自己文档定位"这条，
因为原实现用固定文档去查，跨库时几乎全部补不上页码。

---

## 8.95 改照护文档导出

### 换字体

只改配置：`healthos.care.pdf.font-path` 指向一个 `.ttf` 或 `.ttc`。
不配则按候选列表探测（`msyh.ttc` / `wqy-microhei.ttc` / `NotoSansCJK-Regular.ttc` 等）。
启动时用空 `PDDocument` 试加载一次，结果在 `/health/dependencies` 的 `carePdf`
（`ok(font=...)` / `unavailable(reason=...)`）。

**两个坑**（都踩过）：

```java
// ❌ 不存在这个重载
PDType0Font.load(document, new File(path), true);

// ✅ 走 InputStream
try (InputStream in = new FileInputStream(file)) {
    PDType0Font.load(document, in, true);
}
```

`.ttc` 必须用 `TrueTypeCollection.processAllFonts` 取字体，且**不能在返回前关闭 collection**——
子集化要到 `save()` 才读字形，提前 close 会得到一片空白。

### 换版式

改 `CarePdfRenderer#renderReport` / `renderPlan`。现有实现只做"逐行文本 + 页脚页码"，
CJK 按字断行、西文在空格回退、控制字符与代理项替换。要做表格/富文本得换版面引擎，
**别在现有渲染器里硬加**——它现在的复杂度刚好够用，加排版逻辑会变成半个 Word。

### 加一种产物类型

`artifact.kind` 决定 `Content-Type` 与扩展名，映射在 `ClassicArtifactService#contentTypeOf`
与 `#extensionOf`。加 kind 时两处一起加，否则下载会退化成 `application/octet-stream`
（能下但打不开）。产物内容缺失要抛 `ARTIFACT_CONTENT_MISSING`，**不要**返回 0 字节 200——
空文件比报错更难排查。

---

## 8.96 扩患者长效记忆

### 场景

跨多次会话沉淀"这位患者的事实"：基线档案、已签发的报告决议、打卡进度、随访自述。
记忆**不生成新事实**——只把已经落库、已经签发的东西冻结成一份带出处的快照。

### 步骤

1. `POST /api/v1/patients/{patientId}/memory/initialize`（幂等）从冻结来源采样生成 v1；
2. 随访：`POST .../memory/followups`，`operationId` 幂等、`expectedMemoryId` 乐观并发，返回 `202` + `jobId`；
3. 刷新：`POST .../memory/refresh`，同样 `202` + `jobId`；Outbox 投递 `patient_memory_publish`，
   `PatientMemoryHandler` 调用 `PatientMemoryService.publish` 生成新版本并移动 `patient_memory_state` 指针；
4. 取用：`GET .../memory/versions/{memoryId}/export` 拿 Markdown（带 `X-Content-SHA256` 可校验）；
   `GET .../memory/jobs/{jobId}` 看异步任务状态。

### 四条实现约束

| 约束 | 理由 |
|---|---|
| **只从冻结来源取事实** | 档案修订快照、已签发照护修订（`status=SIGNED`）、签署记录、打卡采样、随访原文。"当前可变表"（`patient_metric`）只能标"采样"登记，不许冒充已核验历史 |
| 随访 **原文照搬**，不摘要不改写不过模型 | 记忆里"患者自述"和"医师结论"必须分得清；模型改写过的就不再是患者原话 |
| 照护 `sign`/`confirm` **必须让发布修订快照自洽** | 这两处在 `setStatus(SIGNED/ACTIVE)` 之后要回写聚合，否则"已发布修订"快照的 `status` 停留在 DRAFT，记忆采集按快照 `status` 判定时会误拒（已踩过一次，回归测试 `CareServiceTest.publishedRevisionSnapshotReflectsSignedStatus`） |
| 版本 **不可变**，刷新只新增不改写 | 历史引用快照指向某一版，改写历史会让旧回答的出处漂移；指针与版本链分两张表也是这个原因 |

### 单测怎么写

`MemorySourceService` 的采集纪律用 `PatientMemoryServiceTest` 钉住（版本链 / 幂等 / 并发冲突 / owner 隔离）；
`MemoryMarkdownRendererTest` 钉住哈希与"采样"标注；`PatientMemoryJobServiceTest` 钉住
`operationId` 幂等、`expectedMemoryId` 冲突、状态机流转。

---

## 8.97 扩模型路由（探测实现）

### 场景

模型配置中心已经能管七条主线的模型、参数与密钥；你要换一家供应商的协议，
或者给某条主线加一类能力探测（比如它需要"函数调用"以外的"工具调用"变体）。

### 步骤

1. 实现 `spi/routing/RouteProbe`。契约自带 `RouteTarget` / `ProbeOutcome` / `CapabilityOutcome`，
   **不依赖 `contract` 层**（扩展点契约不该知道 API 长什么样）。
2. `@Component` 注册。探测器按能力类型分工，一个实现可以只负责其中几类。
3. 主线要求的能力集合在 `ModelPipeline` 枚举里声明——整体状态只看**该主线要求的能力**是否全过。

### 四条实现约束

| 约束 | 理由 |
|---|---|
| 探测输入**必须是合成串**，不许带患者数据 | 探测问的是"这条线通不通"，不是"业务答得对不对"；把患者数据发给供应商去"试一下"是不能接受的 |
| 探测**不许执行工具**，只发固定请求 | 探测要有副作用就意味着它在改业务状态 |
| 失败**必须给错误码**，不许只回"失败" | `MODEL_CONNECTION_FAILED` 和 `MODEL_RESPONSE_INVALID` 排查方向完全不同 |
| 探测**要有超时**（`model-routing.provider-timeout-seconds`） | 供应商挂起时不能把请求线程占死 |

### 单测怎么写

`ModelRouteServiceTest` 钉住编排侧的纪律（版本化、乐观并发、回滚即新建版本、密钥不回显、
主密钥轮换后旧密文不可解、限流）。探测实现本身的协议细节，用假 HTTP 端点断言**请求体里没有患者数据**。

---

## 8.98 扩多智能体角色（执行器实现）

### 场景

四个角色的编排骨架已经跑通（依赖图、跳过、来源校验、交接完整性），默认执行器是确定性模板——
它只组织事实、不调模型。你要接真实模型来产出自然语言。

### 步骤

1. 实现 `spi/agent/AgentRoleExecutor`，`@Component` 注册即接管该角色。
2. `RoleRequest` 里已经带齐了：角色提示词、本次用户指令、允许使用的工具清单、
   **编排层已经读好的工具结果**、上游交接的来源。
3. 返回 `RoleOutcome`：四段结构化产出——结论、进展、局限、来源。**不写思维链**。

### 四条实现约束

| 约束 | 理由 |
|---|---|
| **不许自己调工具** | 工具数据由编排层读好再交给你；执行器自己发起调用，"来源校验"就变成执行器自证 |
| 只能引用**拿到的**来源 | 编排层会逐条比对，引用没读过的来源报 `UNOBSERVED_SOURCES` |
| 读到的每个工具产出**都要引用** | 不引用报 `TOOL_SOURCE_MISSING`——读了不用等于没读 |
| 产出**只有四段**，不许加思维链 | 思维链不可核查，还会把模型内部的中间内容固化进业务库 |

### 什么时候该跳过而不是硬失败

必需上游失败时，下游角色标 `skipped`；但**必需角色失败 ⇒ 整轮 `FAILED`**。
缺了角色的汇报不能被描述成成功。

### 读不到数据时怎么写

"没有"也是合法结论，但同样要有来源——编排层会给空结果补一条**采样回执**
（`sample:<tool>:<采样时刻>`），执行器把它引用进来源列表即可。
不要为了让来源不为空而编一条实体引用。

### 单测怎么写

`AgentCollaborationServiceTest` 钉住编排纪律（冻结计划、范围门禁、依赖图与跳过、五类来源校验、
交接完整性、整轮成败、幂等跨执行生效、空结果补采样回执）。
执行器自己的措辞不必测——但**"只引用拿到的来源"这条必须测**（故意多引一个来源，看校验是否拦下）。

---

## 8.99 加事件类型 / 加控制命令

### 场景

协同运行已经有 12 种事件和 `cancel` / `retry` 两条控制命令。你要加一种事件
（比如 `node.retried`），或者加一条控制命令（比如"暂停"/"改优先级"）。

### 加事件类型：三步

1. 在 `contract/agent/AgentRunContract.java` 加常量（`public static final String`）。
   **契约类是唯一词表来源**，服务里不许再写字符串字面量。
2. 想清楚"什么时候 emit"：事件必须挂在**已确定的状态变化**之后，不是之前。
   `recordNode` 是落库之后 emit，`finalizeRun` 是终态定死之后 emit——
   先 emit 再写库，一旦写库失败，事件就成了假消息。
3. 补一条测试断言 `type` 与 `seq` 严格递增（`AgentRunEventServiceTest` 已有骨架）。

### 加控制命令：四条硬规矩

| 规矩 | 原因 |
|---|---|
| **必须带 `expectedVersion`** | 控制面是"读—判—写"；没有版本号就是拿旧状态覆盖别人的新状态 |
| **终态必须幂等返回**，不许报错 | 客户端重发是常态；`cancel` 一个已取消的 run 应返回 `200` + 现状，不是 `409` |
| **不可逆的状态必须挡住**（成功/取消不可 retry） | 允许重跑已成功的 run，"重试"就变成了"重开"，审计上分不清 |
| **改状态一律显式列更新** | `updateById` 会把内存里的旧 `eventSeq` / `version` 写回去，下一次 emit 直接撞唯一约束 |

### 最容易踩的坑

`agent_run.event_seq` 与 `agent_run.version` 都标了
`@TableField(updateStrategy = FieldStrategy.NEVER)`——**它们只能由数据库改**。
新加的控制命令如果用 `updateById` 顺手把整个实体写回，就会复现"事件缺终态、
`eventSeq` 不涨、读节点正文 500"这个三合一故障。用 `Wrappers.<AgentRun>update().set(...)`。

---

## 8.100 加一个只读工具（Agent Lab）

### 场景

现在 6 个只读工具是固定清单（`AgentTool` 枚举 + `boundTools`）。你要加第 7 个。

### 步骤

1. `AgentTool` 枚举加值（名字即对外的工具名，**改了就是破坏性变更**）。
2. 在角色绑定表里声明谁能用（`boundTools`）。不声明 = 谁都不能用，不是"默认全给"。
3. 实现 `AgentLabToolService` 里对应的执行分支：**只读**、带 `patientId` 校验、
   输出必须符合声明的 schema。
4. 改 `AgentLabContract.CATALOG_VERSION`（如 `agent-lab-m6-v2`）。
5. 补测试：授权集内可调、授权集外 `MCP_TOOL_REJECTED`、输出不合 schema `MCP_OUTPUT_INVALID`。

### 四条实现约束

| 约束 | 理由 |
|---|---|
| **必须只读** | 工具在"校验节点"之前跑，写工具会让校验的对象变成"已经被改过的数据" |
| **必须带 `patientId` 并按 owner 过滤** | 工具是越权的常见入口；越权一律 404 |
| **`tools/list` 的发现集必须等于授权集** | 多给一个工具比少给一个更危险：多给意味着授权计算错了；不等就 `AGENT_DISCOVERY_MISMATCH` |
| **改了清单必须改 `CATALOG_VERSION`** | 不改版本号，调用方缓存会一直返回旧目录，比不改更糟 |

### 越权工具为什么返回 200

回执是证据。`MCP_TOOL_REJECTED` 落库 + `200` 返回，意味着"这次调用确实发生过并被拒"，
审计可查；抛 4xx 就等于把证据丢给会滚掉的日志。

---

## 8.101 加草稿 scope / 加诊断模式

### 加草稿 scope（患者资料暂存）

`patient_review_draft` 现在有 `basic` / `clinical` 两个 scope。加第三个（比如 `medication`）：

1. `contract` 里加 scope 常量，并加进"允许的 scope 集合"——**枚举校验，不是字符串透传**。
2. 定义该 scope 的 `payload` 白名单字段；多余字段**丢掉而不是报错**（前端多传一个字段不该让人丢草稿）。
3. `(owner, exam_id, scope)` 唯一约束已经在了，不用改。
4. 记住两条不变式：**草稿永不改正式修订、永不改确认状态**；`baseRevision` 与
   `expectedVersion` 两把锁都要判，少一把就有一类覆盖丢失。

### 加诊断模式

诊断现在只有 `ragflow_diagnostic`（只跑检索一路）。加新模式：

1. `mode` 常量 + `operation_key` 唯一已就位（幂等靠它）。
2. **想清楚这个模式失败算什么**：检索"没命中"是有效结论，记 `SUCCEEDED` + `resultKind=degraded/no_hit`，
   不要抛给 Outbox 无限重试；只有"诊断本身没跑成"才 `FAILED`，并且**保留 draft**。
3. 对外只暴露 `evidenceIds` / `citationStatus`，不回吐正文片段。

---

## 8.102 换向量模型 / 上 pgvector

### 换一个 embedding 模型（当前是 Ollama `bge-m3`）

1. Ollama 拉模型：`ollama pull <model>`；
2. 改配置三件套：`healthos.embedding.model`、`.dimension`、必要时 `.base-url`。
   **维度必须跟着改**——不改的话每次响应都报 `EMBEDDING_DIM_MISMATCH`，
   这比"能跑但分数全是噪声"好查得多；
3. 重启（装配是启动期定型，运行期不热切换）；
4. 重跑一次索引流水线，或让首次检索自动补建（旧模型的向量留在表里、按 `model` 隔离，
   不会污染新模型的结果；回滚只需把配置改回去）。

换实现（不是换模型）：在 `config/EmbeddingConfig` 里加一个分支返回自己的
`EmbeddingModel` 实现，`enabled()/dimension()/embed/embedBatch/healthy` 五个方法。

### 四条实现约束

| 约束 | 理由 |
|---|---|
| **返回向量必须已 L2 归一化** | 归一化后余弦 = 点积，存储与计算都省一半；由实现方保证，调用方不重复归一化 |
| **`embedBatch` 不许重排** | 顺序错了向量就贴到别人正文上，检索照样出结果、只是全错 |
| **维度不符必须报错**，不许截断/补零 | 补零会得到"看起来能算"的相似度，是最难查的一类错 |
| **失败要区分 `retryable`** | 连不上/5xx 值得 60s 后再试；4xx/维度不符再试也没用，别浪费冷却窗口 |

### 上 pgvector

1. 换镜像或装扩展：`pgvector/pgvector:pg16`，或 `CREATE EXTENSION vector`；
2. 列改成 `vector(1024)`，`ChunkVectorEntity.vector` 换成 `float[]` + TypeHandler；
3. 写入改 `INSERT ... ::vector`，查询改 `ORDER BY vector <=> ? LIMIT ?`；
4. **只动 `ChunkVectorService`**：`KnowledgeBase` 契约、`HybridScorer`、上层问答都不该改。
   如果改动溢出到这个文件之外，说明抽象漏了。

### 单测怎么写

- `HybridScorerTest`：给定词面分与向量分，顺序与分数必须确定（含"两路都为 0 要丢弃"、
  "权重 0 退化为纯词面"、"同分按偏移排序"）。
- `OllamaEmbeddingModelTest`：用 JDK `HttpServer` 起桩，钉住归一化、批量顺序、
  数量/维度不符、4xx 与 5xx 的可重试语义。**不要连真 Ollama 跑单测**。

## 8.103 换 MCP Server / 加一个传输方式

`AgentLabService` 只认 `spi/agent/McpServerBridge`，所以换语言、换框架、换传输方式都不动业务代码。

### 换 Server 实现（比如用 Python 的 `mcp` SDK）

1. 写脚本，实现四个方法：`initialize` / `notifications/initialized`（通知，无响应）/
   `tools/list` / `tools/call`；
2. 从环境变量读四件事：`MCP_TOOL_ENDPOINT`、`MCP_INTERNAL_TOKEN`、`MCP_GRANT`、`MCP_SCOPES`；
3. `tools/call` 时把 `{grant, arguments}` POST 到 `MCP_TOOL_ENDPOINT/{tool}`，
   要求响应里带 `structuredContent`，原样转成 `CallToolResult`；
4. 改配置：`healthos.agent-lab.server-script`（`node-command` 换成解释器路径）。

### 加一种新传输（如 WebSocket / HTTP SSE）

1. 实现 `McpServerBridge`：`transport()`、`enabled()`、`handshake(scopeWires, owner, patientId)`、
   `callTool(scopeWires, tool, owner, patientId, args)`；
2. 在 `config/AgentLabConfig` 的装配分支里加一个取值；
3. **不要**绕开 `AgentLabService` 直接调工具——那样回执生命周期、discovery 比对、错误码收敛全丢了。

### 五条实现约束

| 约束 | 理由 |
|---|---|
| 分帧按**一行一条 JSON**，不是 LSP 的 `Content-Length` | 用错分帧会得到"能启动、永远读不到响应"的服务，且不报错 |
| `initialize` 之后**必须发** `notifications/initialized`（无 id、无响应） | 严格实现的 server 会拒绝未发通知的后续请求 |
| 只递**凭证**，不递 `owner/patientId` | 递身份等于把"服务端锁定"降级成"客户端声明"，这条链路最容易破的地方 |
| 声明**必须独立**（范围→工具的映射写在服务端） | 照抄父进程的工具清单，"发现集等于授权集"就成了自己跟自己比 |
| 进程起不来/超时/提前退出**必须收敛成 `McpBridgeException` 并带 `stage`** | 回执里 `failedAt` 是唯一能定位"哪一步挂了"的信息 |

### 单测怎么写

`StdioMcpBridgeTest` 是模板：真 `ProcessBuilder` 起 node、JDK `HttpServer` 当内部端点，
断言"范围裁剪""请求体里没有身份""越权工具被服务端拒绝""node 不存在时报 UNAVAILABLE + stage=initialize"。
本机没装 node 时用 `assumeTrue` 跳过——**跳过不是通过**。

---

## 8.104 加一个模型的单价 / 改配额口径

1. 单价（三档，`healthos.model-usage.prices.<模型名>`）：
   `cache-hit-input-per-million` / `cache-miss-input-per-million` / `output-per-million`，
   单位「货币 / 百万 token」，币种由 `healthos.model-usage.currency` 定（默认 `USD`）。
   键与上游回报的 `model` 一致即可，大小写不敏感匹配；
   **填非高峰价**，峰时由 `peak-multiplier`（默认 `2.0`）+ `peak-windows-utc` 放大；
2. 配额：`healthos.model-usage.daily-token-quota` / `.daily-cost-quota-micros`（`0` = 不限）；
3. **没配单价的模型照样记账，但 `cost_micros` 留 NULL** —— 不要为了"数字好看"给它编个价；
4. `cache-hit-input-per-million` 留空时退回未命中价（宁可按贵的估，也不凭空少算）；
   但上游回报了 `prompt_cache_hit_tokens` 而配置里没开缓存价，等于默认高估，要看一眼看板
   的 `cacheHitRate` 再决定。

换计费后端（比如接公司财务的用量服务）：实现 `spi/routing/ModelUsageMeter`
（`assertWithinQuota` + `record`），注册成 `@Bean` 覆盖默认实现。两条实现约束：

- `assertWithinQuota` **必须在发请求之前**被调用（顺序反了，测试看不出来，生产上就是超支）；
- `record` **不许把异常抛出去**：钱已经花了，写不进统计不能连累这一次调用。

扩展点连带：`TokenUsage` 若要加字段（比如推理模型的 reasoning tokens），
必须同步三处——`model_usage` 加列（幂等 `ADD COLUMN IF NOT EXISTS`）、
`ModelUsageRecord` 加字段、`ModelUsageService.computeMicros` 明确它算不算钱。
只加实体不加列，PG 会在 `insert` 时报出所有字段名，看起来像 mapper 写错，实际是漏了建表。

---

## 9. 红线（改代码前先读）

| 红线 | 原因 |
|---|---|
| 外部能力不可用时**降级要留痕**（写 `note` / `degraded`），不许静默成功 | 否则"看起来跑通了"会掩盖真实故障 |
| 抽取/检索不到依据时**返回无依据**，不许让模型自由发挥 | 医疗场景，无出处的结论等于错误结论 |
| 引用偏移**补不上就留 null**，不许猜 | 猜出来的页码比没有页码更危险 |
| 配置类里配了 provider 却缺地址/密码 → **启动失败**，不许默认值兜底 | 原工程用 `change_me` 默认值导致漏配静默运行 |
| 新增外部依赖必须能在 `/health/dependencies` 看见 | 发布脚本据此判断能否上线 |
| 观测写入**不许反压业务**（队列满就丢并计数） | 观测拖慢业务是本末倒置 |
| 删除/清理类接口**默认预演、必须点名** | 不可逆动作不该一个手滑就执行 |
| 患者域查询**必须带 owner 条件** | 漏一处就是越权读；且越权返回 404 不返回 403 |
| 新表带归属时，**索引放在补列与回填之后** | 否则老库启动直接失败（`column "owner" does not exist`） |
| 资料抽取**不许猜值**，原文没有的字段不许出现 | 医疗场景，猜出来的字段等于伪造病历；缺字段由人工补录 |
| 指标数值后**必须带边界断言** `(?![\d.])` | 否则 "血糖 5.60" 被截成 "5.6"、"身高 1700" 被截成 "170"，读错数据比抽不到更危险 |
| 参考区间相近的指标**必须分开**（空腹 vs 餐后血糖） | 混用参考区间会造出假阳性异常，是会造成临床误判的错误 |
| 资料录入**必须两道人工核查都通过才能建档** | AI 只负责"抄"，签字的是人；不设"AI 直接建档"的捷径 |
| 角色**不许写进令牌**，只能每次请求现算 | 写进去那一刻，"撤销权限"就变成"等令牌过期"，且不报错 |
| 令牌**必须带 `jti`**，登出必须进吊销名单 | 无标识的令牌无法吊销，"登出"只是让浏览器删了个 Cookie |
| 续期**必须沿用原 `jti`** | 换了标识，登出后刚续出来的令牌又能用 |
| PDF 导出**必须内嵌真实字体**，探不到就 `503` | PDFBox 无内置 CJK 字体；靠字体名引用会在客户机上看方块，而方块文件看起来是"成功导出"的 |
| 跨库检索合并**必须按正文内容去重** | 本地切片与外部引擎的 chunkId 各成一套，按 ID 去重会把同一段正文喂给模型两遍 |
| 外部文档 ID **必须映射回本系统 ID**，映射不到就留 `null` | 否则引用只剩一个点不开的外部 ID，"看得到文字、查不到出处" |
| 引用里的 `documentId` 在跨库场景**必须逐条取自带值** | 用固定文档去回填偏移，跨库时几乎全部补不上，引用集体丢页码 |
| 记忆**只从冻结来源取事实**，指标必须标"采样" | 把当前可变表当已核验历史，等于伪造一份没人签过的记录；"采样即冻结"但不可冒充 |
| 随访**原文照搬**，不许摘要/改写/过模型 | 记忆里患者自述与医师结论必须可分；模型加工过的就不再是患者原话 |
| 照护发布修订**必须自洽**（`sign`/`confirm` 后回写聚合） | 快照 `status` 停在 DRAFT 会让下游按快照判定的逻辑误拒，且本身就是份矛盾的留痕 |
| 发布基线**必须随草稿一起入库** | 基线记的是"这份草稿基于哪一版起草"；放在 insert 之后赋值，库里是 null，发布守卫每次都判"基线已变"，第二次签发必然被拒 |
| 模型密钥**必须永不回显**（只给"是否已配置"） | 配置页面的请求体里带密钥，响应再回显一次就等于把它写进浏览器缓存与日志 |
| 模型配置权限**必须与运维权限分开** | 改路由是全系统生效的动作，不该和"清理孤儿文件"共用一把钥匙 |
| 回滚**必须创建新版本**，不许拨指针 | 拨指针会让"历史版本"变成假的——那一版的内容其实从没被生效过 |
| 协同角色**只许引用拿到的来源** | 引用没读过的来源就是幻觉；编排层会逐条比对 |
| 协同执行器**不许自己调工具** | 自己调就变成"自称读过"，来源校验失去意义 |
| 协同产出**不许写思维链** | 思维链不可核查，还会把模型内部的中间内容固化进业务库 |
| 事件**不许打断业务**：`emit` 失败只记 warn | 事件是旁路；反过来掐死主链路是本末倒置 |
| 事件 `seq` **必须由数据库原子分配**（`UPDATE ... RETURNING` + 唯一约束） | 应用侧"查 max 再 +1"并发就撞 `duplicate key` |
| `event_seq` / `version` **不许被 `updateById` 回写** | 内存旧值写回去，下一次 emit 撞唯一约束，整轮执行被打断（真踩过） |
| 事件 `detail` **不许出接口** | 可能含模型输入片段；要看内容走 `/nodes/{nodeId}`，那条路有脱敏、审计、保留期 |
| `cancel` **必须幂等**、终态原样返回 | 客户端重发是常态；报 `409` 等于把"已经停了"变成"操作失败" |
| 成功/取消的 run **不许 retry** | 允许就是"重开"，审计上分不清"重试失败"与"重跑成功" |
| 断点续跑**只复用 `role` 且 `succeeded` 的节点** | 复用校验节点=跳过校验，拿"来源可能已变"换时间 |
| Agent Lab **发现集 ≠ 授权集必须拒** | 多给一个工具说明授权算错了，放过去就再也发现不了 |
| 工具越权**返回 200 + 回执**，不抛 4xx/5xx | 回执是证据；抛错后证据只能落进会滚掉的日志 |
| 改工具清单**必须改 `CATALOG_VERSION`** | 不改版本号，调用方缓存一直返回旧目录，比不改更糟 |
| 书签**必须冻结引用快照** | 只存指针，等于存了个会变的引用；引用变空，可核验性就没了 |
| 诊断"没命中"**不算失败**，记 `SUCCEEDED` + `degraded/no_hit` | 抛给 Outbox 会无限重试一个永远不会变的结果 |
| 草稿**永不改正式修订与确认状态** | 暂存是"我还没想好"，确认是"我签了字"；混在一起审计分不清谁签的 |
| 翻页/列表**不许裸 `OFFSET` 翻深页**（用 `afterSeq` / 游标） | 深翻页越翻越慢且会漏行；事件流本来就有单调 seq，用它 |
| 向量**必须 L2 归一化后再入库** | 归一化后余弦 = 点积；两侧不统一会得到"能算但没意义"的相似度 |
| `embedBatch` **不许重排**，数量不符必须报错 | 顺序错了向量就贴到别人正文上，检索照样出结果、只是全错 |
| 维度不符**必须报错**，不许截断/补零 | 补零能得到"看起来正常"的相似度，是最难查的一类错 |
| 向量召回**必须设准入门槛**（`vector-min-score`） | 没有门槛时任何问题都能从任何文档挑出 5 条，"总有话说"比"没话说"更危险 |
| 向量**不可用时不许让问答失败**，只许降级并留痕 | 向量是增强，不是主链路；降级不留痕会让"检索变差"被误判成模型不行 |
| 换模型**必须同步改 `dimension`** | 不报错的错配只在结果质量上体现，几乎无法归因 |
| MCP 子进程**只能拿凭证，不许拿身份** | 递 `owner/patientId` 等于让客户端声明自己是谁，越权只需改一个环境变量 |
| 内部工具端点**必须三查**：回环、令牌、凭证 | 它免登录就能读患者数据，少任何一查都是开着的口子 |
| 子进程的 `tools/list` **必须由它自己声明** | 父进程传清单过去，"发现集等于授权集"就变成自己跟自己比，校验失效 |
| 配额闸门**必须在发请求之前** | 顺序反了在测试里看不出来，在生产上是"每月超支一次" |
| 没单价**不许猜**，`cost_micros` 留 NULL | 编出来的成本会让总额看起来是准的，比留空危险得多 |
| 记账失败**不许阻塞业务** | 钱已经花了，统计写不进去不能把答案丢掉 |
| 命中缓存的输入 token 数**必须落库**（`cached_prompt_tokens`） | 命中价与未命中价差 50 倍；不落这一列，`cost_micros` 就没人能复算，数字会被当成真的 |
| 峰谷、缓存价**不许"取中间值"**凑一个平均单价 | 平均价对上一个月、对不上任何一天；账单按天出，口径就要按天 |
| OCR 探活**不许只认 2xx**：PaddleOCR 的 hubserving 没有 `/health`，`GET /predict/ocr_system` 回 **405** 才是在线 | 按"2xx 才在线"写会把一个跑得好好的服务报成 `unavailable`，然后把降级路径当成常态（真踩过） |
| `provider` 与 `protocol` **不许各说各话**，不一致必须启动失败 | 两个开关各管一半，结果就是静默调错接口——比启动不起来难查十倍 |
| OCR `base-url` **不许带路径**，只许 scheme+host+port | 带路径拼接会得到 `http://h//ocr`，看起来像服务端 404，实际是本地配置错 |
| OCR 服务**只吃图片**，PDF 逐页渲染归应用侧（`PdfPageRenderer`） | 让 OCR 服务装 PDF 依赖等于把渲染逻辑实现两遍，还多一份 GPL/字体问题 |
| 换 OCR 引擎**必须留两个变体可切**，不许直接替换 | 识别质量与体积是两条反向曲线（Paddle 更准但重 20 倍），一刀切会让"只想验证链路"的人付出 8 GB 代价 |
| 会话吊销与登录失败计数**不许只存进程内存** | 两件事都是安全缺口而不是可用性取舍：重启后登出失效（令牌"复活"）、多实例各算各的（阈值变 N 倍）。默认 `jdbc`，`memory` 只留给单实例调试并打 warn |
| 认证状态落库后，**库不可用必须降级且留痕** | 直接抛错会让库抖一下就把全站登出判断打成 500；静默放行则等于吊销与限流同时失效。降级到本地名单 + warn + `/health/dependencies` 显示 `store=jdbc:DEGRADED` |
| 数据修改型 CTE 与外层 UPDATE **不许改同一行** | PostgreSQL 不报错、第二次修改**静默不生效**。真踩过：`fail_count` 一路涨到 8，而 `locked_until` 始终为空，锁定根本不会触发。要么压成一条 `INSERT ... ON CONFLICT ... RETURNING`，要么分两条语句 |
| 「写一次读多次」的状态，**读路径不许依赖写入端的进程内缓存** | 多实例下另一个节点看不到你的缓存。库是权威，本地那份只能是降级兜底，且必须能被判定为"降级中" |

---

## 10. 扩展后的验收清单

1. `mvn -B test` 全绿（当前 526 个用例）；
2. `python tools/check-layering.py` 通过——加实现/加域之后，依赖方向别倒（见 README §5.3）；
3. `/health/dependencies` 的 `status=ready`，新依赖出现在返回体里；
4. 走一遍真实链路（上传 → 五节点 → 问答），节点 detail 里的 `engine/provider/store` 是新实现的标识；
5. 停掉新依赖，确认降级路径仍然返回可用结果且 `status=degraded`；
6. 加了埋点就去 `GET /api/v1/traces` 确认轨迹里真能看到新 span（**不是"应该能看到"**）；
7. 加了带归属的表，用**两个账号**各查一遍：另一个账号应当 `404`；
8. 改了抽取规则，用**边界样本**验证（"餐后血糖"、"身高 1700cm"、"体重减轻5kg"这类会读错数据的输入）；
9. 改了认证/授权，**实测三条**：登出后用原 Cookie 访问必须 `401`（不是靠前端删 Cookie）、连续失败达阈值必须 `429`、`/auth/me` 的 `roles` 随配置变（改白名单后不重启也应生效）；
10. 改了导出/PDF，**打开文件看**而不是看字节数：中文是不是真出字（不是方块）、页码对不对、未签发的「草稿」标记在不在、签发后是不是消失了；
11. 改了知识库绑定，做**对照实验**：同一问题不带 `patientId` 与带上各问一次，确认引用条数增加且两条的 `documentId` 各自独立、都能用 `GET /classics/{id}/chunks` 反查到原文；
12. README 第 9 节「差异清单」同步更新——**差异不写清楚，就等于假装没有差异**。
13. 改了模型路由：**响应体 grep 一遍密钥明文**（应为 0 命中），再确认 `Cache-Control: no-store`；
    用**另一个账号**调一次列表，必须 `403`。
14. 改了多智能体：用**少授权范围**跑一轮（如只给 `memory+pipeline`），确认未授权角色是 `skipped`
    而不是 `failed`，且整轮仍能 `SUCCEEDED`；再故意让一个必需角色失败，确认整轮是 `FAILED`。
15. 改了照护发布：**同一个患者连续签发两份报告**，第二份必须成功——
    第一次成功只说明基线为空时能过，第二次才验证基线真的落库了。
16. 改了事件流：跑一轮协同，`GET /collaborations/{id}/events` 拿到的
    `seq` 必须从 1 连续到 `eventSeq`、**没有断号**，最后一条必须是 `run.succeeded|failed|cancelled`；
    再带 `afterSeq` 重放一次，返回的第一条 `seq` 必须正好是 `afterSeq+1`。
17. 改了控制面：**先 cancel 再 cancel**（第二次必须 `200` 且 `status` 不变），
    **对已成功的 run retry**（必须 `409`），**故意传旧 `expectedVersion`**（必须 `409`）；
    最后 retry 一个失败 run，确认 `agent_node` 里 `kind='role'` 的成功节点**没有新增行**
    （断点续跑生效，不是整轮重放）。
18. 改了 Agent Lab：先 `probe` 一次确认握手成功，再 `call` 一个**授权集之外**的工具，
    必须 `200` 且回执 `status=MCP_TOOL_REJECTED`（不是 4xx）；`agent_mcp_receipt` 里能查到这两条回执。
19. 改了草稿/摘录：草稿用**两个不同 payload** 连提两次（第二次带旧 `expectedVersion`）必须 `409`；
    摘录用**被改过一个字**的 quote 提交，必须被逐字校验拦下；发布后再发布一次，必须幂等返回同一条。
20. 改了向量检索：先问一句**同义改写**（正文写"补益肝肾"、问"补肾"），确认改前
    `INSUFFICIENT_EVIDENCE`、改后能召回；再把 `healthos.embedding.base-url` 指到死端口，
    确认 `embedding: unavailable` 但问答照常返回、索引节点 detail 里能看到 `embedding_degraded`
    （**降级要看得见**）；最后查一次 `chunk_vector` 的 `dimension` 与配置是否一致。
21. 改了 MCP 传输层：用 `--healthos.agent-lab.transport=stdio` 启动，`probe` 必须
    `succeeded` 且 `detail.protocolVersion=2024-11-05`（**来自子进程**，不是常量）、
    `declaredTools` 随 scopes 变化；把 `--healthos.agent-lab.node-command` 指向不存在的命令，
    必须**目录接口仍 200**、probe 在几百毫秒内 `failed + MCP_UNAVAILABLE + failedAt=initialize`；
    最后直连 `/internal/agent-lab/tools/*`（不带令牌）必须 `403 INTERNAL_TOKEN_INVALID`。
22. 改了成本/配额：跑一次真实调用，`GET /model-usage/summary` 的 `totalTokens` 必须等于上游
    `usage` 的回报值，`costMicros` 必须能用该行的 `prompt/cached/completion × 三档单价`
    （峰时再乘倍数）手算复现；**同一问题连打两次**，第二次 `cachedPromptTokens > 0`
    才说明缓存价那条分支真的走到了；再把 `daily-token-quota` 设成小于当日已用值重启，
    下一次调用必须 `429 MODEL_QUOTA_TOKENS_EXCEEDED`，`daily-cost-quota-micros` 同理给出
    `MODEL_QUOTA_COST_EXCEEDED`，且**看板里的 `calls` 没有增加**（请求没发出去，钱没花）。
    脚本：`python tools/smoke/verify_model_usage.py`（真实上游，13 项断言）。
23. 改了 OCR：**两条协议都要跑**，别只跑一条就宣布"OCR 好了"：
    ① `provider=paddle` 指向 `tools/ocr-service/paddle_stub.py`（纯标准库、**故意没有 `/health`**），
    `/health/dependencies` 必须报 `ocr: ready` —— 这条专门验"405 也算在线"，用真服务验不出来，
    因为服务若不回 405 就说明路由都没了；
    ② 再指向真 PaddleOCR 服务，上传 `samples/scan-page.png`，`POST /predict/ocr_system` 必须 `200`
    且 `results[0].text` 非空，随后库里的 chunk 正文要与识别结果**逐字一致**（不是"看起来有字"）；
    ③ `--healthos.ocr.protocol` 与 `provider` 填不一致的值，必须**启动失败**；
    ④ `base-url` 带路径（如 `http://127.0.0.1:8868/ocr`）必须**启动失败**。
24. 改了认证状态存储：**必须真重启一次进程**，只在同一个进程里测等于什么都没验。
    脚本 `python tools/smoke/verify_auth_store.py`，四段按顺序跑，中间手动重启：
    ① `before-restart` —— 登录 → 登出 → 同一张 Cookie 立即 `401`，并把 Cookie 存到 `.local/`；
    ② 重启应用 → `after-restart` —— 旧 Cookie **仍 `401`**（内存实现到这一步会返回 200），
       同时**对照组**要用新登录的会话确认仍是 `200` —— 否则"拒绝"可能只是"令牌全失效"的假象；
    ③ `throttle-before` —— 用探针账号（默认 `lockout-probe`，故意不用 admin，
       否则验证完会把真账号锁 15 分钟）连续错口令到 `429`；
    ④ 再重启 → `throttle-after` —— 仍 `429`（失败计数跨重启）。
    另外看 `/health/dependencies` 的 `auth` 字段：`store=jdbc` 才算走了库，
    出现 `jdbc:DEGRADED` 说明已经降级过、跨节点会漏判。
