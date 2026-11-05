# health-os-java

康衡 HealthOS 的 **Java 复刻版（核心垂直切片）**，用于把源工程
`health-os-simple`（Python / FastAPI）里最关键的工程骨架用 Java 重新实现一遍，
验证「同一套设计在 Spring Boot 技术栈上能否同样成立」。

不是等价重写。源工程有 645 个文件、152 个接口、100 张表；
本工程保留其中**一条完整可跑通的业务链路**和它背后的全部关键机制，
其余业务域留作扩展点（见文末）。

三项原先缺失的外部能力 **扫描件 OCR / RAGFlow 知识库 / Neo4j 图数据库** 现已接入，
并各自提供「不启用 → 单机 → 外部服务」三态装配与显式降级（见第 2 节）。

问答这条链路也补上了 **持久化运行**：一次问答拆成节点逐个落库，
失败节点可单独重试、事件流可断点续传、SSE 可实时订阅（见 §3.9）。

---

## 1. 到手后 3 分钟跑起来

```bash
# 1) 起一个 PostgreSQL（表结构由应用启动时自动建）
docker compose up -d postgres

# 2) 编译 + 测试 + 打包
mvn clean verify

# 3) 运行（默认零外部依赖：OCR 不开、检索走本地、图谱落 PostgreSQL）
java -jar target/health-os-java-0.1.0.jar --server.port=18100
```

```bash
# 4) 冒烟：登录 → 上传 → 查流水线 → 问答
curl -c /tmp/ck.txt -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' http://127.0.0.1:18100/api/v1/auth/login

# 看当前身份与角色（角色是服务端按白名单现算的）
curl -b /tmp/ck.txt http://127.0.0.1:18100/api/v1/auth/me

curl -b /tmp/ck.txt -H 'Idempotency-Key: demo-1' \
  -F 'file=@samples/sample-tcm.md' -F 'title=四君子汤与六味地黄丸' \
  http://127.0.0.1:18100/api/v1/classics

# 登出：不只是清 Cookie，令牌会进吊销名单，抄走的 Cookie 也立刻失效
curl -b /tmp/ck.txt -c /tmp/ck.txt -X POST http://127.0.0.1:18100/api/v1/auth/logout
```

默认走 `template` 模型提供者（规则抽取，**不需要任何 API Key**），
所以上面的命令开箱即能跑出实体、关系、证据和带引用的问答。

需要 Node.js（≥18，实测 v22）**只有**一种情况：想验证「真子进程 MCP」形态，
启动时加 `--healthos.agent-lab.transport=stdio`（默认 `in-process`，不依赖 node）。
见 §3.18。

接口文档：`http://127.0.0.1:18100/swagger-ui.html`

**Web 控制台（前端）**：应用启动后直接访问 `http://127.0.0.1:18100/` 即可打开内置控制台
（纯静态前端，由 Spring 托管在 `src/main/resources/static`，同源调用 API，登录后可用）。
默认演示账号 `admin / admin123`。

> 注意：本机若设置了 `HTTP_PROXY`，curl 访问 localhost 会被代理拦截，请加 `--noproxy '*'`。
> 端口被占用时用 `--server.port=` 显式指定。

---

## 2. 接外部依赖（OCR / RAGFlow / Neo4j / Ollama）

四个开关彼此独立，可任意组合。**默认全关**，关掉时不引入任何外部进程
（唯一例外是向量化：默认指向本机 Ollama，连不上就自动降级为纯词面检索，不影响启动与问答）。

```bash
# 一键起全部外部依赖（compose profiles）
docker compose --profile graph --profile ocr --profile ragflow up -d

# 带全套外部依赖运行
HEALTHOS_OCR_PROVIDER=generic   HEALTHOS_OCR_BASE_URL=http://127.0.0.1:8868 \
HEALTHOS_RETRIEVAL_PROVIDER=hybrid HEALTHOS_RAGFLOW_API_KEY=ragflow-local-test-key \
HEALTHOS_GRAPH_STORE=dual        HEALTHOS_NEO4J_PASSWORD=healthos123 \
java -jar target/health-os-java-0.1.0.jar --server.port=18100

# 本地向量检索（可选）：Ollama 拉 bge-m3 后开箱可用
ollama pull bge-m3
# 不想用向量：--healthos.embedding.provider=none
```

### 2.1 扫描件 OCR（`healthos.ocr.provider`）

| 值 | 行为 |
|---|---|
| `none`（默认） | 遇到扫描件**明确报错**（`DisabledOcrProvider`），不静默产出空文本 |
| `generic` | HTTP `POST /ocr`，multipart 上传 PNG，返回 `{lines:[{text,confidence,x1,y1,x2,y2}]}` |
| `paddle` | HTTP `POST /predict/ocr_system`，base64 入参，按 PaddleOCR 四点四边形解析（取外接矩形） |

**契约由本工程定义**，所以两种协议都能接到自建服务上。`healthos.ocr.provider` 是**唯一**决定
走哪个接口的开关，`healthos.ocr.protocol` 只是历史别名：留空就跟随 provider；若两者都填且不一致，
**启动直接失败**（各说各话会静默调错接口，比启动不起来更难查）。

`healthos.ocr.base-url` 必须只写 scheme + host + port，**不许带路径**，否则启动失败——
base-url 带路径时拼接会产生 `http://h//ocr` 这种看着像服务端 404 的问题。

仓库内 `tools/ocr-service` 有**两个真能跑**的实现，同占 8868、二选一：

| 变体 | 应用侧配置 | 镜像 | 说明 |
|---|---|---|---|
| **Tesseract**（默认建议） | `--healthos.ocr.provider=generic` | `healthos-ocr:local`，**358 MB** / 常驻 ~90 MB | Flask + Tesseract 5.3.4 + `chi_sim`，`POST /ocr` |
| **PaddleOCR** | `--healthos.ocr.provider=paddle` | `healthos-ocr-paddle:slim`，**2.97 GB** / 常驻 484 MB；全量版 `Dockerfile.paddle` 8.38 GB / 662 MB | PP-OCRv3，`POST /predict/ocr_system`，热态 400–550 ms/页 |

```bash
# Tesseract
docker compose --profile ocr up -d --build ocr
curl -s --noproxy '*' -F 'file=@samples/scan-page.png;type=image/png' http://127.0.0.1:8868/ocr

# PaddleOCR（与上面二选一，同占 8868）
docker compose --profile ocr-paddle up -d --build ocr-paddle
curl -s --noproxy '*' -H 'Content-Type: application/json' \
     -d "{\"images\":[\"$(base64 -w0 samples/scan-page.png)\"]}" \
     http://127.0.0.1:8868/predict/ocr_system
```

**该选哪个**：只验证链路就 Tesseract，体积小一个量级。论识别质量实测同一张 `samples/scan-page.png`：

| 行 | 原文 | Tesseract | PaddleOCR（PP-OCRv3） |
|---|---|---|---|
| 1 | 四君子汤 | 四君子**注** | 四君子汤 ✓ |
| 3 | 组成：人参、白术、… | 组成：人参、**和白术**、… | 组成：人参、白术、… ✓ |
| 6 | 四肢乏力，食少便**溏**，舌淡**苔**白，脉虚弱。 | 四肢乏力，食少便**沪**，舌淡**苦**白，脉虚弱。 | 四肢乏力，食少便，舌淡苔白，脉虚弱。（漏「溏」） |

Paddle 6 行里 5 行逐字正确，第 6 行漏一个生僻字；Tesseract 4 处错、其中「溏→沪」「苔→苦」两处是中医术语错。
**所以：处方/病历类扫描件建议上 Paddle**，但别指望它零错——抽取侧仍有 `isProbableTitle` 与人工核查兜底。

slim 与全量镜像**输出逐字一致**（v3 检测/识别模型的 md5 相同，已核对），差异只在体积与常驻内存，
因此 `docker-compose.yml` 默认用 slim，全量版只在 slim 构建不出来时兜底。

Paddle 的两个镜像都有三条已知坑要钉死（numpy/opencv 版本互斥、`paddleocr==2.7.5` 的
`predict_system` 漏 import、PP-OCRv4 检测模型在 paddle 2.6.2 上 SIGILL），修法已写进
`tools/ocr-service/Dockerfile.paddle` 与 `Dockerfile.paddle-slim`，细节见 `tools/README.md`。

**探活语义**：PaddleOCR 的 hubserving **没有 `/health` 路由**，对 `GET /predict/ocr_system`
返回 **405** 才是「在线」信号（路由在、方法不对）。把这些码当失败会让
`/health/dependencies` 误报 `ocr: unavailable`。
想在不装 paddle 的情况下验证这点，用纯标准库的契约桩 `tools/ocr-service/paddle_stub.py`
（它刻意只实现 `/predict/ocr_system`、不实现 `/health`）。

`samples/scanned-classic.pdf` 是 `tools/ocr-service/make_scanned_pdf.py` 生成的
**无文本层**两页扫描件（A4 / 200 DPI 渲染正文，用来复现真实扫描质量），
`samples/scan-page.png` 是它的首页位图，用于直接 curl 自检。重新生成：

```bash
# Git Bash（MSYS）下必须加 MSYS2_ARG_CONV_EXCL，否则 /app/... 会被改写成 Windows 路径
MSYS2_ARG_CONV_EXCL='*' docker compose --profile ocr run --rm -v "$PWD/samples:/out" ocr \
  python3 /app/make_scanned_pdf.py /out/scanned-classic.pdf /out/scan-page.png
```

判定逻辑不是「有没有文本」，而是「**有效字符数下限**」：
PDFBox 抽到的文本去掉空白与分页符后少于 `text-layer-min-chars`（默认 20）才判为扫描件。
很多扫描 PDF 带一层几乎无用的文本层（页码、水印），按「有无」判会漏掉整页正文。

### 2.2 RAGFlow 知识库（`healthos.retrieval.provider`）

| 值 | 行为 |
|---|---|
| `local`（默认） | 本地 `document_chunk` 表 + 中文二元组打分，零外部依赖 |
| `ragflow` | 切片全部落 RAGFlow，检索走 `/api/v1/retrieval` |
| `hybrid` | 双写（本地 + RAGFlow）；检索优先 RAGFlow，空结果或异常回落本地 |

对接的是 **RAGFlow HTTP API v1**，调用顺序：

```
POST /api/v1/datasets                    建数据集（parse_type=1 + parser_id）
POST /api/v1/datasets/{id}/documents     multipart 上传归一化后的 .md
POST /api/v1/datasets/{id}/chunks        触发解析
GET  /api/v1/datasets/{id}/documents?id= 轮询 run / chunk_count 到 DONE
POST /api/v1/retrieval                   page_size + similarity_threshold
```

两个踩过的点：
- 用的是 `parse_type` + `parser_id`，老的 `chunk_method` 在 v1 已废弃；
- 信封是 `{code, message, data}`，**HTTP 200 也可能是业务错误**，必须校验 `code == 0`，
  所以 `RagflowKnowledgeBase` 里所有响应都过一遍 `unwrap()`。

仓库内 `tools/ragflow-stub` 按上述契约实现了一份可跑的桩服务（端口 9380，含 1.2s 异步解析延迟），
用于在没有真实 RAGFlow 实例时验证完整调用链。**生产请把 `HEALTHOS_RAGFLOW_BASE_URL` 指向真实实例。**

### 2.3 Neo4j 图存储（`healthos.graph.store`）

| 值 | 行为 |
|---|---|
| `jdbc`（默认） | 节点/边/证据落 PostgreSQL 三张表，邻域用双向 BFS |
| `neo4j` | 全部走 Cypher |
| `dual` | **PG 权威 + Neo4j 图查询**：写入双写，Neo4j 写失败只记 `note` 不抛；查询优先 Neo4j，不可用回落 PG |

实体与关系批量写入用 `UNWIND $rows ... MERGE`，一条语句一个批次，避免 N 次网络往返。
边会冗余存 `evidenceQuote` / `pageFrom`，因此从图里查出来的边**自带出处**，不用回表。

### 2.4 本地向量检索（`healthos.embedding.provider`）

| 值 | 行为 |
|---|---|
| `ollama`（默认） | 用本机 Ollama 的 `bge-m3`（1024 维）把切片向量化，检索走「词面 + 向量」融合；实现见 §3.21 |
| `none` | 退回纯词面（中文二元组）打分，行为与加向量之前完全一致 |

连不上 Ollama **不影响启动**：索引节点会记 `embedding_degraded`，问答退回词面打分，
`/health/dependencies` 里能看到 `embedding: unavailable`。换模型必须同步改 `dimension`。

### 2.5 降级语义（重要）


| 外部依赖 | 未启用时 | 已启用但不可达时 | `/health/dependencies` |
|---|---|---|---|
| OCR | 扫描件报错，纯文本照常 | 扫描件报错（不伪造成功） | `disabled`（不影响 ready） |
| RAGFlow | 走本地检索 | `hybrid` 自动回落本地，问答仍 `OK` | `hybrid:unavailable` → `degraded` |
| Neo4j | 走 PG | 熔断 30s，走内存 BFS，结果一致 | `dual:unavailable` → `degraded` |
| Ollama 向量 | 走纯词面打分 | 走词面打分 + 索引节点记 `degraded` | `embedding: unavailable`（不影响 ready） |

**未启用**算正常（不拉低 ready），**声明了却不可达**才算 `degraded`——
这两件事在运维上是完全不同的信号，混在一起会让面板永远黄着没人看。

---

## 3. 跑通了什么（实测结果，非声称）

`mvn clean verify`：**526 个单元测试全部通过，BUILD SUCCESS**（66 个测试类）；分层依赖校验 0 违规（87 包 / 326 类 / 932 条跨包引用）。

覆盖：分段 / 二分 / 合并 / 重试策略 / 证据定位 / 令牌 / 检索 / 指标判定 /
OCR 行重建（6）/ OCR 响应解析（6）/ 扫描件判定与归一化（6）/ 规则抽取（9）/
问答分支编排（6）/ 图谱分支（4）/ 指标分支（3）/
轨迹上下文与跨线程搬运（6）/ OTLP 导出（3）/ 轨迹写入与截断（9）/ 保留策略清理（3）/
CSV 解析（5）/ 患者生命周期与乐观锁（12）/ 批次导入（4）/ 孤儿判定（4）/ 删除四段式（6）/
**owner 隔离（15）** / **资料录入规则抽取（22）** / **资料录入流程关卡（24）** /
**认证与授权（59）**：令牌 v2 与吊销（8）/ 口令哈希（5）/ 登录限流（7）/ 吊销名单（5）/
认证服务（10）/ Cookie 属性（3）/ 角色现算（6）/ 解析器与滑动续期（5）/
**认证状态落库（10）**：本地无记录但库里有仍判吊销（重启/换节点）/ 重复吊销幂等 / 过期不算吊销 /
库异常降级并置位 degraded / memory 模式不碰库 / 失败计数传参口径 / 库内锁定直接 429 /
锁定到期清理 / 成功清零 / 库不可用时限流降级 /
**照护文档导出（7）**：PDF 真实字体渲染与分页（4）/ JSON 导出与未完成拦截（3）/
**典籍产物（6）** / **患者知识库绑定（9）**：含跨库去重与文档归属（2）/
**检索结果带文档归属（4）**：本地 provider 填 ID（2）/ 跨库逐条定位（2）/
**模型路由配置中心（29）**：版本化与只追加 / 乐观并发 / 回滚即新建版本 / 加密凭据不回显 /
四类合成探测 / 限流 / 权限隔离 / 主密钥轮换后旧密文不可解 /
**多智能体协同（18）**：冻结计划与范围门禁 / 依赖图与跳过 / 五类来源校验 /
交接完整性 / 整轮成败判定 / 幂等跨执行生效 / 空结果补采样回执 /
**照护流程（19）**：含发布基线随草稿入库的回归。

### 3.1 基础链路（只起 PostgreSQL，2026-11-03 实测）

| # | 场景 | 结果 |
|---|---|---|
| 1 | 未登录访问受保护接口 | `401` |
| 2 | 登录 / `me` | 签发 HttpOnly Cookie，返回用户名与角色 |
| 3 | 上传 Markdown | 202 + `documentId`/`jobId` |
| 4 | 五个流水线节点 | 全部 `SUCCEEDED`，20 实体 / 12 关系 / 9 证据 |
| 5 | 图谱查询 | 20 节点 / 12 边，边均带原文引用与页码，如 `四君子汤 --主治--> 脾胃气虚`（0.85） |
| 6 | 带引用问答 | `status=OK`，返回命中切片、页码区间与打分 |
| 7 | 无证据问题（"量子纠缠的退相干时间"） | `INSUFFICIENT_EVIDENCE`，明确拒绝作答而非胡编 |
| 8 | 幂等重放（同 Key 同内容） | 返回同一 `documentId`，不重复建单 |
| 9 | 同 Key 不同内容 | `409 CONFLICT` |
| 10 | 患者建档 + 3 项指标 | `riskLevel=HIGH`，2 项偏高 1 项偏低，边界判定正确 |
| 11 | 重试 | 新建 job，全部节点命中检查点，秒级完成 |

### 3.2 三项外部依赖全开（真实 PostgreSQL 16 + Neo4j 5.26 + Tesseract 5.3.4 + RAGFlow 契约服务）

依赖面板：

```json
{"database":"ok","objectStorage":"ok","llmProvider":"template-rule-based",
 "ocr":"ok","knowledgeBase":"hybrid:ok","graphStore":"dual:ok","status":"ready"}
```

上传**两页无文本层扫描 PDF**，五节点明细：

| 节点 | 明细 |
|---|---|
| `normalize_or_ocr` | `source_kind=pdf-ocr, engine=generic, ocr_pages=2, chars=197` |
| `tcm_extract` | `entities=19, relations=18, provider=template-rule-based` |
| `ragflow_index` | `engine=hybrid, external_ref=<datasetId>, chunks=1, latency_ms=2067` |
| `graph_build` | `entities=19, relations=18, evidences=6, dropped_relations=0, store=dual` |
| `pipeline_manifest` | `chunk_count=1` |

图谱边自带页码（`六味地黄丸 --组成--> 熟地黄 p2`、`四君子注 --功效--> 益气健脾 p1`）。

Neo4j 多跳（以「茯苓」为根，`GET /graph/neighbors`）：

| depth | 结果 |
|---|---|
| 1 | 3 节点 / 2 边（茯苓、四君子注、六味地黄丸） |
| 2 | 全图 19 节点 / 18 边 |

`cypher-shell` 直查确认累计 `nodes=52, rels=40`（含多次测试上传的文档）。

RAGFlow 问答：`status=OK`，引用回填 `page=1, chars=0~197`；无证据问题仍 `INSUFFICIENT_EVIDENCE`。

### 3.3 故障降级（同样是实测）

| 动作 | 结果 |
|---|---|
| 停 RAGFlow | `/health/dependencies` → `knowledgeBase: hybrid:unavailable, status: degraded`；问答仍 `OK`，引用偏移照旧回填，耗时 0.6s |
| 重启 RAGFlow | 立即恢复 `hybrid:ok` / `ready`（外部检索不做冷却，可用就立刻回来） |
| 停 Neo4j | `graphStore: dual:unavailable, status: degraded`；邻域查询熔断后 13ms 返回，仍是 19 节点 / 18 边（走 PG BFS） |
| 重启 Neo4j | 30s 冷却期满自动回到 Cypher 图查询，首次 1.6s（建连）、之后正常 |

### 3.4 三路并行问答（2026-11-03 实测）

原工程 `qa_prompts.BRANCHES = ('ragflow','modern','tcm')` + `summary`，本工程按同样语义实现：
**三路并行、互不阻塞、按完成情况汇总**。开关是 `HEALTHOS_QA_MODE=parallel`（默认 `single`，行为与旧版一致）。

对扫描件提「四君子汤的功效是什么？」并带上一位录了指标的患者：

| 分支 | 状态 | 凭据类型 | 引用 | 耗时 |
|---|---|---|---|---|
| `ragflow` 古籍原文 | `SUCCEEDED` | `retrieved_evidence` | 1 | 3ms |
| `tcm` 中医图谱 | `SUCCEEDED` | `retrieved_evidence` | 8 | <1ms |
| `modern` 现代医学 | `SUCCEEDED` | `patient_record` | 2 | <1ms |

返回 `status=OK`，`citations` 去重后 11 条，`answer` 是三段带标题的拼装：

```
【古籍原文】……功效 : 益气健脾。主治 : 脾胃气虚证……
【中医图谱】· 四君子注 —功效→ 益气健脾（第 1 页）：功效 : 益气健脾。
           · 四君子注 —组成→ 人参（第 1 页）：组成 : 人参、和白术、茯苓、甘草各等分。
【现代医学】· 空腹血糖 7.4 mmol/L（参考 3.9–6.1）→ HIGH
           · 收缩压 152 mmHg（参考 90–140）→ HIGH
```

**部分完成语义**（把检索切成 `ragflow` 单模式并停掉 RAGFlow）：原文路 `FAILED / QA_RETRIEVAL_FAILED`，
图谱与指标两路照常出结果，整体 `status=BRANCHES_INCOMPLETE`、`errorCode=QA_BRANCHES_INCOMPLETE`
（与原工程同名），已完成的结论**不会因为一路失败被丢掉**。

其他已验证的点：
- 分支明细随消息落库（`qa_message.branches`），回读历史仍是当时三路的结果；
- `GET /api/v1/qa/branches` 自描述当前注册的分支，`/health/dependencies` 里也有 `qaMode` / `qaBranches`；
- `mode=single` 与旧版逐字节一致（`branches` 为空数组，答案与引用不变）。

### 3.5 观测（2026-11-03 实测）

上传一次样本文档后，`GET /api/v1/traces` 立刻能查到这次执行：

```
classic_document  SUCCEEDED  outcome=SUCCEEDED  2976ms  spans=7  failed=0
qa_ask            SUCCEEDED  outcome=OK          203ms  spans=6  failed=0
```

span 树（`GET /api/v1/traces/{traceId}`）：

```
root [business/classic_document] 2976ms OK
  normalize_or_ocr [business_stage/pipeline]  23ms
  tcm_extract      [business_stage/pipeline]  41ms
    llm.extract    [model/llm]                 5ms  {"provider":"template-rule-based","chars":227,"attempt":1,"depth":0}
  chunk_index      [retrieval/knowledge-base] 2084ms
  graph_build      [graph/graph-store]        787ms
  manifest         [business_stage/pipeline]  20ms
events: pipeline_started / pipeline_counts{entities:20, relations:12, evidences:9, chunks:2, graphStore:"dual"}
```

耗时能看出瓶颈在哪：`chunk_index` 2.08s 与 `graph_build` 787ms 占了九成，规则抽取只有 41ms。

问答轨迹验证的是**跨线程搬运**这一环——三路分支跑在各自的线程池里，
`TraceContext.Frame` 若没搬过去，分支 span 就会丢：

```
root [business/qa_ask] 203ms
  knowledge.retrieve [retrieval/knowledge-base] 17ms
  graph.read         [graph/graph-store]       169ms
  branch.modern      [business_stage/qa-branch]  0ms  {"status":"SKIPPED","basis":"model_knowledge"}
  branch.ragflow     [business_stage/qa-branch]  2ms  {"status":"SUCCEEDED","citations":1}
  branch.tcm         [business_stage/qa-branch]  1ms  {"status":"SUCCEEDED","citations":4}
```

**失败也有轨迹**（上传一个损坏 PDF，重试 3 次即 3 条轨迹，`executionNo` 递增）：

```
trace FAILED  outcome=FAILED  err=BAD_REQUEST
- root FAILED 24ms err=BAD_REQUEST msg='PDF 解析失败: Error: End-of-File, expected line at offset 21'
  - normalize_or_ocr FAILED 11ms  same message
```

其他已实测：
- `GET /api/v1/traces/overview` → `traces=3 failed=0 running=0 spans=19`，`byStatus={"SUCCEEDED":3}`，
  `byService` 给出 `knowledge-base p95=2084ms` / `graph-store p95=787ms` / `llm`；
- `GET /api/v1/traces/{traceId}/export` → 合法 OTLP/JSON（`startTimeUnixNano` 为**字符串**纳秒，`kind:1`，`status.code:1`），可直接投喂 Collector；
- `POST /api/v1/traces/maintenance/sweep?dryRun=true` → `{"dryRun":true,"traces":0,"cutoff":"2026-10-20T…"}`（默认预演，不写库）；
- 业务状态词（`OK` / `INSUFFICIENT_EVIDENCE`）落 `outcome` 列，`status` 只存健康度四值，
  老库启动时自动回填，`byStatus` 不再被拆成两半。

### 3.6 患者域（2026-11-03 实测）

| 场景 | 结果 |
|---|---|
| 建档（带 `externalRef`） | `pat_75fe…` `status=ACTIVE` `revision=0` |
| 版本冲突（`expectedRevision=5`） | `409 PATIENT_REVISION_CONFLICT`，message 里直接给当前版本号 |
| 正确修改（`expectedRevision=0`） | `name=张三丰` `revision=1` |
| 修订快照 | `1 纠正姓名 84d8de0b…` / `0 创建档案 20ea63c7…` |
| 批次导入 3 行（1 行生日写成 `1958/02/03`） | `PARTIAL total=3 ok=2 fail=1`，坏行 `FAILED PATIENT_INVALID_BIRTH_DATE`，**好行照常入库** |
| 批次幂等（同文件再传） | `reused=true`，不重复建单 |
| 重试 | 只重跑失败行；坏数据仍失败（符合预期，不掩盖问题） |
| 附件上传 | `pob_4ca4…` `kind=REPORT` `44 bytes`，落 `patients/pat_…/pob_….txt` |
| 孤儿扫描 | `total=2 ref=1 orphans=1` → `['patients/orphan-demo/left-over.txt']` |
| 孤儿清理（点名） | `{"requested":2,"deleted":1,"skipped":["patients/../etc/passwd"]}`（路径穿越被拦） |
| 删除预览 | `resourceCount=3` `byKind={REVISION:2, OBJECT:1}` + 一次性 `previewToken` |
| 错误令牌确认 | `403 DELETION_TOKEN_INVALID` |
| 正确确认 | `PENDING` → `COMPLETED`，3 个资源全部 `erased=true` |
| 台账回读 | `verified=true`（重算 `sha256` 与登记值一致） |
| 删除后可见性 | `GET /patients/{id}` → `404`，`/objects` → `NOT_FOUND` |

### 3.7 owner 隔离（2026-11-03 双账号实测）

单账号时"按人隔离"是验证不出来的——第二个账号一出现，漏掉 owner 条件就立刻变成越权读。
所以先给认证加了可配置多账号（`healthos.auth.users`），再用两个真实账号打一遍：

| 场景 | 预期 | 实测 |
|---|---|---|
| doctor 读 admin 的患者 | 不可见 | `404`（不是 403——403 等于承认它存在） |
| doctor 改 admin 的患者 | 不可改 | `404`，档案内容未变 |
| doctor 对 admin 的患者发起删除预览 | 不可发起 | `404` |
| doctor 的患者列表 | 只看自己的 | `total=0`（admin 侧 `total=3`） |
| doctor 传过的批次文件，admin 再传一次 | 不复用 | 两个不同 `batchId`，`reused=false` |
| doctor 读 admin 的批次 / admin 读 doctor 的批次 | 互相不可见 | 双向 `404` |
| doctor 显式查 `?owner=admin` 的轨迹 | 拒绝 | `403 TRACE_SCOPE_FORBIDDEN` |
| doctor 读 admin 的轨迹详情 | 不可见 | `404` |
| doctor 调孤儿扫描 / 观测清理 | 拒绝 | `403`（运维白名单只有 admin） |
| admin 调同样两个运维接口 | 放行 | `200` |
| doctor 上传典籍后的轨迹归属 | 是上传者 | `owner=doctor`（此前写死 `system`，上传者反而看不到自己的轨迹） |

单元测试层面新增 `OwnerIsolationTest`（15 例），断言方式不是"调用没报错"，
而是**检查真正下发的 WHERE 子句里有没有 owner**——用
`TableInfoHelper.initTableInfo(...)` 在单测里初始化 MyBatis 元数据，
才能看到 lambda 条件渲染出来的真实 SQL。

### 3.8 资料录入链路（2026-11-03 实测）

原始体检/病历资料 → OCR 归一化 → 分片抽取 → 两道人工核查 → 生成正式档案。
对应原工程 `patient_intake_*` 那套。**核心主张：AI 只负责"抄"，签字的是人。**

用一份真实结构的体检报告（10 个分片、11 项实验室指标）走完整链路：

| 环节 | 实测结果 |
|---|---|
| 上传 | `202` + `examId`，落对象存储 + 写 outbox 事件，**同步不跑 OCR** |
| OCR 归一化（文本） | `sourceKind=text`，正文按标题切分 |
| OCR 归一化（扫描件） | `sourceKind=image-ocr`（`generic` 走 `/ocr`，`paddle` 走 `/predict/ocr_system`），OCR 噪声不影响数值抽取 |
| 分片 | 8 片全部 `SUCCEEDED`，`failedParts=0`；标题识别容忍 `###`、`**加粗**`、`一、`、`：`、全角冒号 |
| 抽取字段 | `name=李建国 gender=男 birthDate=1962-03-15 phone=13912345678 heightCm=170 weightKg=72.5` |
| 抽取指标 | 13 项 + BMI 派生 = 14 项，`FBG/PPG/SBP/DBP/HbA1c/TC/TG/HDL/LDL/ALT/AST/Cr/UA` 全部命中，异常判定与参考区间一致 |
| 只过基础核查就确认 | `409 PATIENT_INTAKE_REVIEW_REQUIRED`（临床内容核查尚未通过） |
| 驳回不带意见 | `400`（"驳回临床内容核查必须写明原因"） |
| 两道核查都过后确认 | `patientId=pat_35ea…` `metricCount=14` `status=CONFIRMED` |
| 生成档案 | `李建国 / MALE / 1962-03-15`，`riskLevel=HIGH`，异常统计 `{HIGH:12, LOW:1, NORMAL:1}` |
| 重复上传同一份 | `reused=true`，复用同一 `examId`，不重跑 OCR |
| 换人传同一份 | `reused=false`，**不跨用户复用**（否则同一份资料能建出两个患者） |

**抽取规则上真正修掉的错**（这几条都会读错临床数据，不是格式问题）：

| 问题 | 修法 |
|---|---|
| 「餐后2小时血糖 7.8」被记进**空腹**血糖 → 拿空腹参考区间判 → 假阳性 | 新增 `PPG`（餐后血糖）独立指标码；`FBG` 加"紧邻禁忌前缀"判定（`餐后`/`随机` 变体），标签离得远不误伤 |
| 「血糖 5.60」被截成「5.6」、「身高 1700cm」被截成「170」 | 数值后统一加边界断言 `(?![\d.])` |
| 「糖化血红蛋白(HbA1c) 7.2%」「尿酸 380」「三酰甘油 2.1」抽不到 | 补齐括号写法、无「血」字写法、指标别名 |
| 「体重减轻5kg」被当成体重 | 体重规则要求数值紧跟标签与单位 |
| 「身高 170」（无单位）抽不到 | 补无单位写法，带单位优先 |
| OCR 把「出生日期」认成「生日期」→ 生日抽不到 | 标签写成 `出?生日期`，容忍缺字 |
| 「检查检验」标题掉进 general 分片 | 补 cue |

**人工补录**：抽取器不许猜值，OCR 也确实会抽不到（实测把「王五」认成「王 :」）。
所以 `POST /patient-intakes/{id}/confirm` 支持带 `name`/`gender`/`birthDate`/`phone`，
补录优先于抽取结果，且**来源标注为 `manual`** 写回汇总——
日后看到"姓名=王五"能直接分辨是人补的还是机器抄的。

**失败可重试**：上一轮 OCR 挂了不该让这份资料永远卡死。
失败后重新上传同一份 → 清掉旧分片、状态回 `UPLOADED`、重排抽取事件（不新建单）。

---

### 3.9 问答持久化运行：断点续跑与事件流（2026-11-03 实测）

`POST /api/v1/qa/ask` 是一次性同步问答——请求返回，答案就在手里。
但问答可能要跑十几秒，途中进程重启、客户端断线、网络抖一下，前面的算力就白扔了。
所以另开一条**持久化运行（run）**的路子：把一次问答拆成节点逐个落库，
每个节点都能单独重跑，跑完还能订阅事件流。

对应源工程 `qa_worker.py` 那套「节点级租约 / 心跳 / 断点续跑」。

#### 生命周期

```
QUEUED → RUNNING → SUCCEEDED
                  → INSUFFICIENT_EVIDENCE   （跑完了，但三路都没有可用依据）
                  → FAILED
                  → CANCELLED
```

节点粒度：`retrieval` → `ragflow` / `tcm` / `modern` → `summary`。
节点状态：`PENDING / RUNNING / SUCCEEDED / FAILED / SKIPPED / CANCELLED`。

四张表：`qa_run`（运行主体）、`qa_run_node`（节点）、`qa_event`（事件流）、`qa_attempt`（尝试留痕）。

#### 关键机制

| 机制 | 做法 | 为什么 |
|---|---|---|
| **幂等提交** | `(owner, idempotency_key)` 唯一；同键同问题返回同一个 run，异问题返回 `409 QA_IDEMPOTENCY_CONFLICT` | 会话是可选的，按会话查重会让"不带会话的重复提交"绕过幂等 |
| **冻结上下文** | 提交那一刻的检索命中、图谱、患者指标冻结进 `contextSnapshot` | 续跑必须用**冻结值**，不能重跑一遍检索——否则依据变了，答案不可比 |
| **节点级续跑** | 已 `SUCCEEDED` 的节点直接跳过并记 `node.skipped`，只重跑 `FAILED` 的 | 成功的节点其依据已冻结，重跑既浪费也可能产出不一致的答案 |
| **汇总节点跟着重跑** | 重试失败节点时，`summary` 一并重置为 `PENDING` | 汇总读的是各分支的落库结果；分支换了新结果而汇总不重算，最终答案就还是上一轮的 |
| **事件流断点续传** | `qa_event.seq` 在 run 内单调递增；SSE 带 `afterSeq` 只补之后的事件 | 客户端记住最后收到的 `id`（就是 seq），重连即续 |
| **owner 隔离** | 查询 / 重试 / 取消一律按 owner 过滤，越权返回 **404**（不是 403） | 403 会泄露"这个 id 存在" |

#### 实测结果（真实 PostgreSQL 16，非模拟）

| 环节 | 实测输出 |
|---|---|
| 提交 | `202` + `runId` / `version=1` / `statusUrl` |
| 五节点执行 | `retrieval` / `ragflow` / `tcm` / `modern` / `summary` 全部 `SUCCEEDED` |
| 事件流 | seq 1→12 **连续无空洞**：`run.accepted` → `run.started` → `node.skipped`(retrieval) → 各分支 `node.started`/`node.succeeded` → `summary` → `run.finished` |
| 增量拉取 | `afterSeq=8` 返回 `[9,10,11,12]`，不重不漏 |
| SSE 端点 | `id:` / `event:` / `data:` 三段格式正确，终态主动断开 |
| 幂等重放 | 同键同问题 → 返回同一个 `runId`，`version` 保持不变 |
| 同键换问题 | `409` + `{"code":"QA_IDEMPOTENCY_CONFLICT"}`；`qa_run` 表仍只有 1 条 |
| 人工置 `tcm` 失败后重试 | 只重跑 `tcm` + `summary`（`attempt=2`），`ragflow`/`modern`/`retrieval` 记 `node.skipped` |
| 事件里的重跑清单 | `run.retry` 载荷为 `{"nodes":["tcm"],"rerun":["tcm","summary"]}`——`nodes` 是用户点的，`rerun` 是实际跑的 |
| 尝试留痕 | `qa_attempt` 里 `tcm` 有 attempt 1/2 两条、`summary` 有 1/2 两条 |

> `retrieval` 节点在提交时就跑完了（冻结上下文要用它的结果），
> 所以执行阶段它是 `node.skipped` 而不是重新检索——这是设计如此，不是 bug。

---

### 3.10 认证与授权（2026-11-04 实测）

**一条主张：身份与授权分开。** 令牌只证明"你是谁"，"你能干什么"每次请求按当前配置现算。

这样做不是洁癖——角色如果固化在令牌里，把某人从运维名单移走得等令牌过期（默认 8 小时），
这段时间它仍是管理员。这是真实漏洞，不报错、不留痕。

| 环节 | 实测输出 |
|---|---|
| `/health/dependencies` 的认证姿态 | `auth=session:v2(ttl=24s,secure=false,passwords=plaintext)` |
| 未登录访问 `/auth/me` | `401 UNAUTHORIZED` |
| 登录 | `200`，`roles=["ADMIN","TRACE_READER","USER"]`（按白名单现算，不是写死的 `ADMIN`） |
| Cookie 属性 | `Path=/; HttpOnly; SameSite=Lax; Max-Age=24`；`Secure` 由配置决定（本地 http 开了等于让浏览器丢弃 Cookie） |
| 刚登录就访问 `/auth/me` | `200`，**不续期**（剩余有效期还在 TTL 的 1/3 以上） |
| 剩余有效期不足 1/3 时访问 | `200` + 新的 `Set-Cookie`，**`jti` 与原令牌相同**（续期必须沿用，否则登出后新令牌又能用）；响应体里的 `expiresAt` 与新令牌完全一致（前端按它算刷新时刻，差一秒都会提前把自己踢下线） |
| 登出 | `204` + `Max-Age=0` 清 Cookie，**同时把 `jti` 记入吊销名单** |
| 登出后用原令牌访问 | `401`——被抄走的令牌在到期前也失效了 |
| 错误口令 / 不存在的账号 | 两者都是 `401` + 同一句「用户名或口令不正确」（区分了就等于提供账号枚举器） |
| 连续失败达阈值 | 第 6 次起 `429 TOO_MANY_REQUESTS`，口令正确也进不来 |
| 令牌版本 | 载荷 `{"v":2,"sub","jti","iat","exp"}`；无 `jti` 的旧令牌一律拒绝（不可吊销的令牌不能放行） |
| `environment=prod` + 明文口令 | **进程拒绝启动，exit=1**，报错里直接给出生成哈希的命令（实测） |

**三个刻意的取舍**（都写进了 `docs/extending/EXTENDING.md` 与差异清单）：

| 取舍 | 原因 | 代价 |
|---|---|---|
| 吊销名单与限流计数**落库**（`auth_session_revocation` / `auth_login_attempt`） | 留在内存有两个安全缺口：重启后吊销名单清空（登出过的令牌又活了）、多实例各存一份（在 A 登出后打 B 照样进得去）。两张表的读写都走主键（约 0.1ms），换掉一个安全缺口是划算的 | 每次请求多一次主键查询；库不可用时降级到本地名单（会打 warn 且 `/health/dependencies` 显示 `store=jdbc:DEGRADED`）。`auth.session-store=memory` 仍可切回纯内存，仅供单实例调试 |
| 登录限流**按账号**而不是按 IP | 换 IP 成本极低；按账号才真正保护目标账号 | 攻击者可用别人的账号名把自己锁在门外，所以阈值 5 次 / 15 分钟压得较短 |
| 口令**自己实现 PBKDF2**，不引 spring-security-crypto | 只需要"校验一个口令"，引整个 security 会顺带激活默认过滤器链，要写更多配置去关掉 | 没有提供多算法协商、密码策略等能力（本工程也用不到） |

---

### 3.11 补三块缺口：导出、产物、知识库绑定（2026-11-04 实测）

对账发现三处「数量上看不出来」的缺口——不是少个字段，是整条链路缺失。逐项补完后实测：

**一、照护文档导出（JSON + PDF）**

| 环节 | 实测输出 |
|---|---|
| 依赖自检 | `/health/dependencies` → `"carePdf":"ok(font=msyh.ttc)"`（探测不到字体时是 `unavailable(reason=...)`，不是假装可用） |
| JSON 导出 | `200`，`Content-Type: application/json`，`status=revision=1`，6 个分节完整 |
| PDF 导出 | `200`，`application/pdf`，**约 200 KB / 4 页**（`%PDF-1.6`，尾部有 `%%EOF`）；中文正常、页码正确、右上角有「草稿，未签发」 |
| 签发后重导 | `status=SIGNED` + `signedRevision=2` + `signature` 落 JSON；PDF **不再有草稿标记** |
| 未完成文档导出 | `409 CARE_DOCUMENT_INCOMPLETE`（半成品不该被当成成品发出去） |
| 中文文件名 | `Content-Disposition: attachment; filename="..."; filename*=UTF-8''...`（RFC 5987 双写）+ `Cache-Control: no-store` |

PDF 这块踩了两个硬约束：PDFBox **没有内置 CJK 字体**（reportlab 的 `STSong-Light` 是字体名引用、不是嵌入，照搬会在客户机上丢字），
且 `.ttc` 集合**不能在 `save()` 前关闭**（子集化到写盘时才读字形）。无可用字体时返回 `503`，不输出「豆腐块」。

**二、典籍产物查询（chunks / evidence / artifacts）**

| 环节 | 实测输出 |
|---|---|
| 切片 | `total=2`，`charStart` 0→194 / 174→226，`pageFrom=1`（与原文逐字对得上） |
| 单条切片 | 按 `chunkId` 取回；跨文档取 → `404` |
| 证据 | `total=9` |
| 产物清单 | 3 个：`MARKDOWN` / `EXTRACTION` / `MANIFEST` |
| 产物下载 | `200`，`text/markdown;charset=utf-8`，`filename*=UTF-8''`，661 字节 |

**三、患者知识库绑定参与检索**

| 环节 | 实测输出 |
|---|---|
| 可用数据集 | `available` 2 个（`bound` 标记区分是否已绑） |
| 绑定 | `qkb_...` `ACTIVE`；重复绑定返回同一个 `id`（幂等） |
| `reconcile` | 仍指向存在的数据集 → 保持 `ACTIVE`；失效则标 `STALE` 并**保留记录**（失败要看得见） |
| **对照实验** | 同一问题「四君子汤主治什么？李建国的血压如何？」<br>**不带** `patientId` → 1 条引用（典籍 `score=0.354`）<br>**带** `patientId` → 2 条引用（典籍 + 随访记录 `score=0.212`），两条 `documentId` 各自独立 |

这一步抓出一个真 bug 和一个真缺口：

- **去重键错了**：原按 `chunkId` 去重，但本地切片与 RAGFlow 切片是两份独立产生的 ID，
  同一段正文 ID 不同 → 喂给模型两遍，`citations` 里出现两条一模一样的引用。
  改为**按正文内容哈希**去重（`content.hashCode() + ":" + content.length()`）。
- **引用不可反查**：RAGFlow 的检索响应本来就带 `document_id`，客户端却把它丢了，
  引用里只剩一个点不开的外部 ID。补了 `Citation.documentId`，并在 provider 层用
  `knowledge_index.external_doc_id` **映射回本系统典籍 ID**；跨库命中改为**逐条按自带 `documentId`**
  去本地切片表回填页码偏移（原先用固定文档去查，跨库时几乎全部补不上）。
  实测引用 `documentId=doc_8c6d97...`、`page=1`、`charStart=0`，
  与 `/classics/doc_8c6d97.../chunks` 里第一条完全一致——闭环成立。

### 3.12 患者长效记忆与随访（2026-11-04 实测）

对齐原工程 `memory_*` 一族，保留它最关键的语义、砍掉跨进程调度层（见 §9）。实测全链路：

| 环节 | 实测输出 |
|---|---|
| 初始化 | `POST .../memory/initialize` → v1，`sources=4`（基线 / 指标采样 / 已签发报告 / 签署记录） |
| Markdown 导出 | `200`，**约 10 KB**，`X-Content-SHA256` + `Cache-Control: private,no-store`；文件名 `patient-memory-v1.md`（UTF-8 RFC 5987） |
| 随访提交 | `202`，`operationId=op_follow_001` → `jobId`；带 `expectedMemoryId` 做乐观并发 |
| 随访异步执行 | Outbox worker 拾起 `patient_memory_publish` 事件 → 发布 v2，`sources=5`（新增 `patient_followup`） |
| 显式刷新 | `202` → 发布 v3；`versions` 列表 3 个版本，各 `contentHash` 不同 |
| 乐观并发 | 用陈旧 `expectedMemoryId` 提交 → `409 MEMORY_VERSION_CONFLICT`（提示当前版本 ID） |
| 幂等 | 同 `operationId` + 同内容重提 → 返回**同一个** job（不新建） |
| 校验拒绝 | 未知分类 / 重复分类 / 含控制字符 → `400` |
| 任务控制 | 取消挂起任务 → `CANCELLED`；取消已成功任务 → `409`（历史不可撤销） |

Markdown 的关键纪律：**只从已冻结来源取事实**——档案修订快照、已签发的照护修订、签署记录、打卡采样、随访原文；
指标明确标注「当前状态采样，非已发布修订」；随访内容**原文照搬**（「饮食打卡反馈 / 躯体症状感受」小节逐字落地，不做任何摘要）。

这一步修了一个被新功能暴露的真 bug：照护**签发 / 确认**流程里 `snapshotRevision` 在 `setStatus(SIGNED/ACTIVE)` 之后**没有回写聚合**，
导致"已发布修订"快照的 `status` 停留在签发前的 `DRAFT`，下游按快照 `status` 判定时误拒。已在 `CareService.snapshotRevision` 内
每次从当前文档状态重算聚合并回写文档列，并补了回归测试（`CareServiceTest.publishedRevisionSnapshotReflectsSignedStatus`）。

---

### 3.13 Web 控制台（前端，2026-11-04 新增 + 实测）

纯静态前端，落在 `src/main/resources/static`，由 Spring 托管，**同源调用 API**，无需单独构建步骤（Maven 直接打进 jar）。
用 Cookie 会话鉴权（`fetch(credentials:'same-origin')`），登录态失效后端自动弹回登录页。实测（18100 端口，标准账号登录后）：

| 验证项 | 结果 |
|---|---|
| 静态首页 / JS | `200`，`text/html` / `text/javascript` |
| 未登录 `/auth/me` | `401`（前端据此弹登录） |
| 登录 → 带 Cookie 调 `/patients` | `200`，返回真实患者（如 `pat_24d97…` 李建国） |
| `/health/dependencies` | `ready`（数据库 / 存储 / 图谱 / PDF 字体均 ok） |
| `care/catalog`、`patients/{id}`、`patients/{id}/memory`、`patient-intakes`、`classics`、`qa/branches` | 响应结构与前端假设逐一吻合 |
| 记忆导出 | `200`，`X-Content-SHA256` 与版本 `contentHash` 一致，`private,no-store`，`patient-memory-v4.md` |

覆盖的页面：系统概览、患者档案（建档/详情/状态流转/指标/附件）、资料录入（上传/两道核查/确认建档）、
健康管理（报告·计划列表/分节编辑/签发/确认/打卡/JSON·PDF 导出/修订·审计）、典籍处理（上传/五节点流水线/切片·证据·产物下载/重试）、
智能问答（同步问答/异步运行轮询+SSE/会话回读/患者知识库绑定）、患者记忆（初始化/版本链/随访原文/刷新/异步任务）。
所有 JS 通过 `node --check` 语法校验，前端零外部 CDN 依赖，可离线运行。

---

### 3.14 模型路由配置中心（2026-11-04 新增 + 实测）

对齐原工程 `model_route_*`：每条业务主线各配各的模型、参数与密钥，配置**版本化 + 只追加**。
七条主线：`REPORT`（报告生成）、`QA`（问答）、`AGENT`（多智能体）、`MEMORY`（记忆摘录）、
`EXTRACTION`（典籍抽取）、`GRAPH`（图谱构建）、`EMBEDDING`（向量化）。

实测（18100 端口 + PostgreSQL 55433，`model-routing.enabled=true` + `master-key`）：

| 环节 | 实测输出 |
|---|---|
| 读取当前配置 | `200`，`Cache-Control: no-store`；响应里**只有** `credentialConfigured=true` / `credentialVersion=1`，**无明文密钥**（全响应体 grep `sk-` 命中 0） |
| 保存配置 | `200`，版本 `1 → 2`；响应同样不含密钥明文 |
| 空操作识别 | 同参数重复保存 → **版本不变**（不产生垃圾版本） |
| 乐观并发 | 用陈旧 `expectedVersion` → `409 MODEL_CONFIG_CONFLICT`「配置已被更新，请重新读取后修改」 |
| **回滚** | `POST .../rollback`（`targetVersion=1`）→ **创建 v3**（不是把指针拨回 v1），内容回到 `qwen-max`，`changeReason=回滚至 v1（创建新版本）`；版本序列 `v3/v2/v1` 单调递增 |
| 能力探测 | 四类（`connectivity`/`structured`/`streaming`/`tool_calling`）全部 `failed MODEL_CONNECTION_FAILED`——本机无外网且无有效密钥，**符合预期**；探测输入是合成串 `Return {"ok":true}`，**不碰患者数据、不执行工具** |
| 探测限流 | 10 秒内重复探测 → `429` |
| 权限隔离 | 非模型管理员（`clinician`，角色仅 `USER`）读列表 → `403 FORBIDDEN`「此账号没有模型配置管理权限」；`/permissions` 公开且返回 `canManageModelRoutes=false` |

密钥安全的三条硬约束：**AAD 绑定**（`healthos:model-credential:v1:{pipeline}:{credentialId}:{version}`，
密文搬到别的行上解不开）、**主密钥标识**（`key_id` 记主密钥指纹，主密钥轮换后旧密文报
`MODEL_CREDENTIAL_UNREADABLE` 而不是静默降级）、**配置指纹**（HMAC-SHA256，跨版本稳定）。

模型配置管理员名单与运维名单**分开**（`security.model-admin-users` → 角色 `MODEL_ADMIN`）——
改路由是全系统生效的动作，不该和"清理孤儿文件"共用一把钥匙。

---

### 3.15 多智能体协同（2026-11-04 新增 + 实测）

对齐原工程 M6 冻结契约：**4 角色**（`patient_memory_manager` / `pipeline_inspector` /
`lifestyle_advisor` / `progress_synthesizer`）、**3 场景**（`progress` / `execution` / `statistics`）、
**6 只读工具**。编排大脑在 Java 侧（`AgentCollaborationService`），事实由编排层读、执行器只做组织。

实测：

| 环节 | 实测输出 |
|---|---|
| 提交协同 | `POST /api/v1/patients/{id}/collaborations` → `202` + `runId`；Outbox 异步执行，轮询即得结果 |
| 跑通一轮 | `progress` + 全范围 → 四角色 `succeeded`，最终答案带 **3 个来源**（记忆 v1 / 计划 v2 / 报告 v2），每条带版本号 |
| 少授权范围 | `scopes=[memory,pipeline]` → `LIFESTYLE: skipped`（未授权 report/plan），其余三角色 `succeeded` |
| 统计场景 | `statistics` → `MEMORY`/`SYNTHESIS` `succeeded`，`PIPELINE`/`LIFESTYLE` `skipped`；来源是两条采样回执（`sample:get_patient_statistics:…`） |
| 范围门禁 | 请求许可范围外的 scope、或少了场景必需范围 → `422 AGENT_SCENARIO_SCOPE_INVALID` |
| 幂等 | 同 `operationId` 同输入重提 → `reused=true`，返回同一个 `runId`（执行完成后仍然成立） |
| 换输入 | 同 `operationId` 换指令 → `409 AGENT_OPERATION_MISMATCH` |
| 越权读取 | 另一账号读别人的 `runId` → `404`（不是 403，不承认这个运行存在） |
| 整轮成败 | 必需角色失败 → 整轮 `FAILED`（`AGENT_REQUIRED_ROLE_FAILED`），缺了角色的汇报**不会**被描述成成功 |

四条纪律落在代码里，不是注释里：

1. **依赖图**：`MEMORY ‖ PIPELINE → LIFESTYLE → SYNTHESIS`；必需上游失败则下游标 `skipped`，不硬失败。
2. **来源校验五类问题**：`EMPTY_SOURCES` / `UNOBSERVED_SOURCES`（引用没读过的 = 幻觉）/
   `TOOL_SOURCE_MISSING`（读了工具却没引用）/ `HANDOFF_SOURCE_MISSING`（吞了交接）/ `ROLE_SCHEMA_INVALID`。
3. **交接完整性**：下游只接受同轮、`kind=role`、状态成功、`sha256(output)==outputHash` 的上游节点。
4. **事实由编排层读**：`readTools` 在 service 里从库里读出数据再交给执行器；否则"来源校验"变成执行器自证。

「读到空集合」也是合法结论——**空结果补一条采样回执来源**（`sample:<tool>:<采样时刻>`），
所以"该患者当前没有任务"能作为结论交上来，而不是被 `EMPTY_SOURCES` 判死。

---

### 3.16 检查报告草稿：已落地并端到端复核（2026-11-04 实测）

经逐层对照原工程 `care_service.py`，**报告草稿这条线在 Java 侧本就已完整落地**（不是缺口），
本轮做的是端到端复核，并修掉复核中暴露的一个真 bug。实测：

| 环节 | 实测输出 |
|---|---|
| 创建草稿 | `POST /api/v1/care/reports`（基于**已确认**体检）→ `200`，`status=DRAFT`，`revision=1`，来源快照当场冻结 |
| 异步生成 | Outbox worker 拾起 `care.generate` → **6 个分节**全部生成（综合画像 / 现代医学评估 / 中医分析 …），每节带 `metricRefs` |
| 签发（人工签发位） | `POST .../sign` → `200`，`status=SIGNED`，`revision=2`，`signedRevision=2` |
| 审计留痕 | `audits` 返回 `report_signed`，操作人 `admin` |
| 同步落计划 | 签发同时生成计划草稿：`kind=plan`，`status=DRAFT`，5 个任务（框架 / 第 1–4 周） |
| 确认生效 | `POST .../confirm` → `status=ACTIVE` |
| 打卡 | `PUT .../checkins/task_week_1` → `{total:5, completed:1, pending:4}` |
| 导出 | JSON `200`（6 323 B）、PDF `200`（197 853 B，内嵌中文字体） |

复核中修掉的真 bug：**发布基线没落库**。`createReport` 原先在 `insert` **之后**才给
`parent_document_id` 赋值（发布基线 = 起草时当前生效的报告），于是库里存的是 `null`，
发布守卫每次都判定"基线已变"——**任何患者第二次签发必然 `409 CARE_PUBLICATION_BASE_CHANGED`**。
现已把基线移到入库前定死，并统一 `parent_document_id` 的语义为「发布基线」（修订来源改用
`source_report_id`），同时在 `schema.sql` 里加幂等回填把存量草稿补齐。

### 3.17 Agent Run 事件流与控制面（2026-11-05 新增 + 实测）

协同运行原先只有「提交 + 查一次详情」，看不到过程、也不能取消/重试。本轮补齐
**事件表 + 增量轮询 + SSE + 控制面**，全部围绕同一个 `seq` 与同一份 `agent_run_event`：

| 环节 | 实测输出 |
|---|---|
| 提交协同 | `POST /api/v1/patients/{id}/collaborations` → `202` + `runId`，同一事务写入 `run.queued` 事件 |
| 执行结束 | 四角色全部 `succeeded`，整轮 `SUCCEEDED` |
| 增量事件 | `GET /collaborations/{runId}/events?afterSeq=0` → 11 条：`run.queued` → `run.started` → 8 条 `node.completed`（4 角色 × 角色节点+校验节点）→ `run.succeeded`；`nextSeq=11`、`eventSeq=11` |
| 断点补齐 | `afterSeq=下一页起点-1&limit=1` → 精确返回那 1 条，不重不漏 |
| 事件内容 | 只含 `seq/type/nodeId/status/createdAt`，**不含 detail**；正文走节点端点 |
| SSE | `GET /collaborations/{runId}/stream?afterSeq=0` → `id:1 / event:run.queued / data:{...}`，终态后主动断开 |
| 节点正文 | `?mode=redacted` → 只给字段名 + 「正文按权限隐藏」；`?mode=full` → 全局读权限 + 7 天保留期内，落一条 `node.payload_read` 审计事件 |
| 取消幂等 | 终态再取消 → `idempotent=true`，不重复写事件 |
| 版本冲突 | `cancel {expectedVersion:99}` → `409 AGENT_VERSION_CONFLICT` |
| 不可重试 | `retry` 成功态运行 → `409 AGENT_RUN_STATE` |
| 运行列表 | `GET /collaborations?limit=2` → 键集分页（`created_at,id` 双列游标） |

**实测查出的真 bug**：`finalizeRun` 用 `updateById(run)` 落状态时，把内存里执行开始时的
`event_seq=0` 一起写回，覆盖了已经递增到 10 的计数器；下一次 `emit` 分配的 `seq` 与历史事件
撞车，`(run_id, seq)` 唯一约束把整轮执行打断（表现为「事件里没有 run.succeeded」）。
修法：`AgentRun.eventSeq/version` 标 `@TableField(updateStrategy = NEVER)`，只允许
`UPDATE ... event_seq+1 RETURNING` 与 cancel/retry 的显式 `UPDATE` 推进；
`cancel` 也改用显式列更新，避免 updateById 再回写版本号。

### 3.18 Agent Lab：工具目录与协议校验（2026-11-05 新增 + 实测）

把「四个角色能用哪些只读工具」做成可查询的目录，并提供**三步协议校验**
（`initialize → tools/list → tools/call`）。三步走的是 `McpServerBridge` 这一个端口，
两种实现对应两种现实，上层编排代码一行不改：

| 传输层 | 怎么接 | 证明什么 |
|---|---|---|
| `in-process`（默认） | 同进程枚举目录、直接查库 | 授权逻辑与回执语义对得上；零依赖、不会因子进程挂掉拖垮目录接口 |
| `stdio` | `ProcessBuilder` 拉起 `tools/mcp-server/patient-business.js`，走一行一条 JSON 的 JSON-RPC | **外部客户端真能按协议接进来**：握手、发现、调用都由另一个进程完成 |

| 环节 | 实测输出 |
|---|---|
| 目录 | `GET /agent-lab/catalog` → `catalogVersion=agent-lab-m6-v1`、6 个只读工具、4 个 Agent；`transport` 如实显示 `IN_PROCESS` 或 `STDIO` |
| probe（进程内） | 回执 `succeeded`、`output=null`（只握手 + 发现，不调工具） |
| probe（`stdio`） | `succeeded`，`protocolVersion=2024-11-05`（**来自子进程的 initialize 响应，不是父进程常量**）、`handshakeMs=136`（initialize 135 + tools/list 1，node 启动就占掉 135ms） |
| 声明裁剪 | 只授权 `memory,report` → `declaredTools=[read_patient_memory, read_patient_report]`；只授权 `memory` → 仅 1 个 |
| call（`stdio`） | `status=succeeded`，`events=[initialize ok, tools/list ok, tools/call ok]`（131ms），输出 `{memoryId, version:4}` 是**真库里的值**（Java → node → 内部端点 → DB → 回原路） |
| 越权工具 | scopes 只给 `memory` 却调 `get_pipeline_status` → 回执 `failed`、`detail.error=MCP_TOOL_REJECTED`（**不抛异常**，回执仍返回） |
| 内部端点鉴权 | 无令牌 / 错令牌直连 `/internal/agent-lab/tools/*` → `403 INTERNAL_TOKEN_INVALID` |
| node 缺失降级 | `--healthos.agent-lab.node-command=node-missing` → 目录接口照常 `200`、`/health/dependencies` 仍 `ready`；probe 在 **44ms** 内如实失败 `failed + MCP_UNAVAILABLE + failedAt=initialize`（不等满 20s 超时） |
| 未知服务 | `POST .../services/not-exist/probe` → `404 MCP_SERVICE_NOT_FOUND` |
| 缓存 | 全端点 `Cache-Control: no-store`（成功与错误响应都带） |

回执 `agent_mcp_receipt` **先以 `running` 独立提交再执行**：进程中途崩溃时前端会看到
`interrupted`，至少能确认「这一步开始过」，而不是一个永远转圈的请求。

**`stdio` 形态的三条设计取舍**（细节见 `docs/architecture/ARCHITECTURE.md` §24）：

1. **每次操作拉一个进程**，握手完就退出。常驻进程要复用就得把身份塞进请求，
   等于把「服务端锁定」降级成「客户端声明」；省下的几十毫秒不值这个代价。
2. **只递凭证，不递身份**。父进程按登录态签发一张随机 ID 的短命凭证（`mgrant_…`，
   默认 60s），子进程回调时带它；端点查表才还原 `owner/patientId`。
   请求体里<b>没有</b> `owner/patientId` 这两个键，子进程因此没有能力指定读谁的数据。
3. **声明必须独立**。父进程只把「授权范围」传给子进程，`范围 → 工具` 的映射写在
   `patient-business.js` 里（与 Java 侧 `AgentLabContract.AUTHORIZED_TOOLS` 是两份代码）。
   两边漂移才会在握手期被 `AGENT_DISCOVERY_MISMATCH` 抓住。

### 3.19 QA 书签与单路 RAG 诊断（2026-11-05 新增 + 实测）

| 环节 | 实测输出 |
|---|---|
| 显式收藏 | `POST /qa/messages/{messageId}/memo` → `200` + `memoId`；冻结 question、正文哈希、引用快照 |
| 重复点击 | 同一条消息再点 → 返回**同一个** `memoId`，不新建 |
| 列表 / 详情 | `GET /qa/patients/{id}/memos` 键集分页；详情把 `evidenceSnapshot` 还原成 JSON 数组 |
| 提交诊断 | `POST /qa/bindings/{id}/diagnostics` + `Idempotency-Key` → `202` + `Location`/`statusUrl` |
| 缺幂等键 | 不传 Header → `422 IDEMPOTENCY_KEY_REQUIRED`（不是 Spring 的裸 400） |
| 执行 | 单路检索无命中 → 运行 `SUCCEEDED`，`result.retrievalStatus=no_hit`、`executionStatus=NO_EVIDENCE`、`resultKind=no_evidence`，**不抛给 Outbox 无限重试** |
| 降级 | 检索依赖不可用 → 仍 `SUCCEEDED`，`executionStatus=DEGRADED`、`resultKind=model_unknown_retrieval`，错误写进 `result.retrievalError` |
| 证据 | 命中时 `evidence[]` 带 `evidenceId/contentHash/snippet`（正文截断，不整篇导出） |

诊断与书签都按 owner 隔离：越权一律 `404`，不承认资源存在。

### 3.20 患者资料草稿与记忆 Agent 摘录（2026-11-05 新增 + 实测）

| 环节 | 实测输出 |
|---|---|
| 草稿初始 | `GET /patient-intakes/exams/{examId}/drafts/basic` → `draft=null` |
| 首次 PUT | `expectedVersion=0` → 隐式创建，`version=1`，`baseRevision` 冻结 |
| 陈旧版本 | 用不同 payload 再来一次 `expectedVersion=0` → `409 DRAFT_VERSION_CONFLICT` |
| 修订已变 | `baseRevision` 落后于录入单 → `409 DRAFT_REVISION_CONFLICT`，**草稿保留不清空** |
| 丢响应重试 | 同 payload + `version==expected+1` → 直接返回既有（幂等），不报冲突 |
| 删除 | `DELETE` → `draft=null`，幂等 |
| 摘录候选 | `POST /patients/{id}/memory/agent-followups` → `202` + `noteId`，2 条随访 → 2 条 observation（覆盖全部分类） |
| 发布 | `POST .../agent-notes/{noteId}/publish` + `expectedCandidateHash` → `PUBLISHED`；重复发布幂等 |
| 校验 | 发布时重算逐字摘录、`candidateHash`、`sourceHash`；任一漂移 → `409` |

草稿是**私人暂存区**：不改临床修订、不改确认状态、没有提交/确认端点，正式写入仍走
`/exams/{examId}/confirm`。摘录是**候选**：`PENDING` 不自动写记忆版本，显式发布才落凭据，
并按 noteId 去重追加 `agent_notes`。

### 3.21 本地向量检索（Ollama `bge-m3`，2026-11-05 新增 + 实测）

此前本地检索只有中文二元组打分：问「补肾的药材有哪些」，而正文写的是「补益肝肾」，
一个词都对不上就**一条都召回不到**。本轮补上向量一路，模型用本机 Ollama 的 `bge-m3`（1024 维）。

| 环节 | 实测输出 |
|---|---|
| 装配 | `healthos.embedding.provider=ollama`；`/health/dependencies` → `embedding: ollama:bge-m3:ok(dim=1024)` |
| 索引期 | `POST /classics/{id}/retry` → `ragflow_index` 节点 `chunks=6`、`latency_ms=1916`（含模型加载），`chunk_vector` 落 6 行、`dimension=1024` |
| 懒补建 | 历史文档首次被检索时自动补建（`document_chunk` 2 条 → `chunk_vector` 2 行），不用手工回填 |
| **同义查询（关键对照）** | 「补肾的药材有哪些？」→ **向量开：`OK` + 2 条引用**（top `score=0.3865`，命中「熟地黄…补益肝肾」）；**向量关：`INSUFFICIENT_EVIDENCE` + 0 条** |
| 关键词查询 | 「四君子汤主治什么？」→ 两条路都能答，词面仍是主力 |
| 降级 | 把 `base-url` 指向死端口：`embedding: ollama:bge-m3:unavailable`、`status: ready`，问答照常返回（走词面），耗时 0.78s |
| 冷却 | 可重试失败后安静 60s 再试；不可重试（4xx/维度不符）不冷却，因为再试也一样 |

**融合排序是加权和，不是两段式**：`score = 0.6×向量 + 0.4×词面覆盖率`
（`healthos.retrieval.vector-weight`）。两段式（向量召回→词面重排）会引入两个不可解释的门槛，
且重排看不到被召回丢掉的候选；加权和的分数能解释成「0.62 = 0.6×0.8 + 0.4×0.35」。
词面完全没命中时，向量分要过 `vector-min-score=0.45` 才准入——否则任何问题都能从任何文档里
挑出 5 条。

**三处刻意的取舍**：

1. **不装 pgvector**：官方 `postgres:16` 镜像不带该扩展，向量存 JSON 数组、余弦在 Java 侧算。
   本地候选集只有几十到几百条（`candidate-limit=200`），够用且 `psql` 里能直接读懂。
   上 pgvector 时把列换成 `vector(1024)` 加 HNSW 索引，只动 `ChunkVectorService` 两句 SQL。
2. **查询时会写库**：历史文档没有向量，首查顺手补建（带 in-flight 去重）。
   让运维「记得跑一次回填」是不可靠的承诺。
3. **索引期失败不阻断流水线**：`ragflow_index` 节点仍 `SUCCEEDED`，但 detail 里带
   `degraded: embedding_degraded:EMBEDDING_UNAVAILABLE（检索退回词面打分）`——
   降级必须留痕，否则「检索变差了」会被误判成模型不行。

### 3.22 模型成本与配额（2026-11-05 新增 + 真实上游实测）

每调一次模型记一行 `model_usage`，调用前先过当日配额闸门（按主线 `pipelineKey` 统计）。
下面全部来自**真实 DeepSeek 上游**（`deepseek-v4-pro`，2026-11-05 11:01 CST / UTC 03:01 属峰时）：

| 环节 | 实测输出 |
|---|---|
| 记账 | 一次问答 → 1 行：`prompt=551 / cached=512 / completion=2281 / total=2832`（上游 `usage` 原样，不按字符数估） |
| 成本 | `cost_micros = (512×0.022 + 39×0.66 + 2281×1.98) × 2 = 9107` = **$0.009107**（单价 USD/百万 token，基准价即官方非高峰价；微元与"每百万"正好抵消，全程整数，无浮点中间值） |
| 缓存价生效 | 同一问题连打 3 次：第 1 次 `cached=0`（冷启动），后两次 `cached=512/551`（**92.9% 前缀命中**），成本从同一量级的 `p×0.66` 掉到 `p×0.022`——命中价与未命中价差 50 倍，混算一个价会把成本高估几十倍。累计 6 次调用后聚合命中率 61.9%（`2048/3306`）：冷启动那几次把均值拉下来了，这正是"要落 `cached_prompt_tokens` 而不能只看均价"的原因 |
| 峰谷价生效 | UTC 03:01 落在 `01:00-04:00` 峰窗内，同一笔用量比谷时贵 1 倍；看板返回 `peakMultiplier=2.0`、`peakWindowsUtc`、`peakNow=true` |
| 看板 | `GET /model-usage/summary?days=1` → `totalTokens=11239`、`totalCostAmount=0.032513`、`currency=USD`、`cachedPromptTokens=2048`、`models[0].{priced:true,cacheHitRate:0.619}`、`unpricedCalls=0`（6 次调用合计，与逐行相加一致） |
| token 闸门 | `--healthos.model-usage.daily-token-quota=1000` 重启后再问 → **`429 MODEL_QUOTA_TOKENS_EXCEEDED`**，「主线 PATIENT_QA 当日 token 已达上限 1000（已用 9560）」 |
| 成本闸门 | `--healthos.model-usage.daily-cost-quota-micros=20000` 重启后再问 → **`429 MODEL_QUOTA_COST_EXCEEDED`**，「已达上限 0.020000 USD（已用 0.027972 USD）」 |
| 闸门位置 | 被拒前后看板不变：`totalTokens=9560`、`calls=5`、`cost=0.027972` —— **请求没发出去，钱没花** |

官方价目（`api-docs.deepseek.com/quick_start/pricing`，USD/百万 token；**配置里填的是非高峰价**，
峰时由 `peak-multiplier: 2.0` 整体放大——官方三档在峰时都正好翻倍，所以乘总额与逐档乘等价）：

| 模型 | 缓存命中输入 | 未命中输入 | 输出 |
|---|---|---|---|
| `deepseek-flash`（V4.1-Flash） | 0.003（峰 0.006） | 0.15（峰 0.3） | 0.6（峰 1.2） |
| `deepseek-v4-pro` | 0.022（峰 0.044） | 0.66（峰 1.32） | 1.98（峰 3.96） |

**四条刻意的取舍**：

1. **没单价就不算钱**：`cost_micros` 允许 `NULL`，看板单列 `unpricedCalls`。
   编一个价格会让总额看起来是准的，比留空危险得多。
2. **命中缓存的 token 必须落库**（`cached_prompt_tokens`）：只留 prompt/completion 的话，
   成本就是个黑盒结果——缓存价与未命中价差 50 倍，事后没人能复算 9107 是怎么来的。
3. **记账失败不阻塞业务**：写库异常只留日志。钱已经花了，不能因为统计表写不进去把答案丢掉。
4. **配额按主线不按账号**：成本是主线在烧（抽取/问答/记忆发布各一条），
   按账号限流会把「某个人今天问得多」和「某条主线失控重试」混为一谈，而后者才会烧穿预算。

> 已知限制：① 峰谷只按 UTC 工作日建模，**中国法定节假日不建模**（那天按峰价估，属保守高估）；
> ② 只覆盖 chat 调用，embedding 不计费；③ `usage` 由上游回报，网关若省略该字段则本次不记账
> （不写零行，零行的唯一作用是把调用次数算多）。

---

## 4. 系统流程图

两张可交互图（双击打开，纯离线自包含 HTML，含聚焦、上下游可达、导出 PNG/SVG）：

| 图 | 看什么 |
|---|---|
| [`docs/architecture/diagrams/system-architecture.html`](docs/architecture/diagrams/system-architecture.html) | 系统组成：使用者 → HTTP API → Outbox → 五节点流水线 / **资料录入编排 → 抽取器 SPI**；PostgreSQL / 对象存储；四个可选外部依赖落在哪一段 |
| [`docs/architecture/diagrams/pipeline-workflow.html`](docs/architecture/diagrams/pipeline-workflow.html) | 流水线内部：内容分流（文本层 / 扫描件）、分段与检查点、RetryPolicy 三条异常分支、二分回退 |

阅读要点：

- 架构图里**虚线 = 可选依赖**（默认全关，靠 `healthos.ocr/retrieval/graph` 三个开关打开），
  实线是主链路。图中带边框的区域是 Spring Boot 进程边界，PG 与对象存储画在框外——它们是独立进程。
- **资料录入与典籍流水线是两条独立链路**：前者走 `patient.intake.extract` 事件 →
  资料录入编排 → 抽取器 SPI → 两道人工核查 → 建档；后者走五节点。
  两者共用 Outbox、对象存储与 PostgreSQL，但状态机与关卡互不干扰。
- 流程图里「异常与重试」是一条单独泳道：**只有异常才会走到 `RetryPolicy`**，
  正常路径是 `extract → index → graph → manifest`，别把它读成串行必过。
- 两张图由 `archify` 技能生成，源描述在 `.archify/<type>-<slug>-<时间戳>/candidate.json`，
  改图改这份 JSON 重跑即可；[`docs/architecture/ARCHITECTURE.md`](docs/architecture/ARCHITECTURE.md) 是同样内容的 Mermaid 文本版，
  便于在 diff 里看结构变化。

> 生成物通过 validate / deliver / strict check / 真实浏览器检查四道自动化门（零错误、零交叉），
> 并按 1600×1100 视口截图做过目视核对（无遮挡、无穿框、无裁切）。

---

## 5. 项目结构与源工程模块对应

### 5.1 目录分层

工程按**技术角色**切分，角色内再按**业务域**分包。顶层目录就是一张"这一层干什么"的清单：

- `controller/` HTTP 入口，`service/` 业务编排，`mapper/` 数据访问，`model/` 实体与值对象，
  `contract/` 对外契约，`spi/` 扩展点接口，`provider/` 扩展点实现，`worker/` 异步任务处理器，
  `text/` 纯文本算法，`common/` 公共内核，`config/` 配置装配。

同一个扩展点的接口一律放 `spi/`、实现一律放 `provider/`——这条规则全项目一致，
新加扩展点照着放就行。

```
health-os-java/
├── README.md                  本文件
├── docs/                      文档（按用途分类，见 docs/README.md）
│   ├── architecture/          架构说明 + 可交互图表
│   └── extending/             扩展点手册
├── samples/                   可直接喂给接口的样例数据（文本 / 扫描件 / 图片）
├── tools/                     本地可跑的配套桩服务（OCR、RAGFlow 契约）+ 分层校验与真实链路冒烟脚本
└── src/
    ├── main/
    │   ├── java/com/kangheng/healthos/
    │   │   ├── controller/    HTTP 入口：auth/ care/ classics/ graph/ health/ patient/ qa/ tracing/ + GlobalExceptionHandler
    │   │   ├── service/       业务编排：auth/ care/ classics/ graph/ ocr/ outbox/ patient/ qa/(含 run/) tracing/
    │   │   ├── mapper/        MyBatis 映射：care/ classics/ graph/ outbox/ patient/ qa/ retrieval/ tracing/
    │   │   ├── model/         实体与值对象：care/ classics/ graph/ ocr/ outbox/ patient/ qa/ retrieval/ tracing/
    │   │   ├── contract/      对外契约：care/ classics/ patient/ qa/ tracing/
    │   │   ├── spi/           扩展点接口：ai/ care/ graph/ ocr/ outbox/ patient/ qa/ retrieval/ storage/ tracing/
    │   │   ├── provider/      扩展点实现：ai/ care/ graph/ ocr/ patient/ qa/ retrieval/ storage/ tracing/
    │   │   ├── worker/        异步任务处理器：care/ classics/ patient/ qa/
    │   │   ├── text/          纯文本算法：切片、引用定位（无状态、无依赖）
    │   │   ├── common/        公共内核：异常、JSON、ID、分页、访问控制、CSV
    │   │   └── config/        配置装配
    │   └── resources/         application.yml + schema.sql（建表幂等）
    └── test/java/…            与主源码**同构**（一比一镜像，改包名不漏改）
```

包内命名的三条补充规则：

- **跨域的东西不放在别人的域里**。本地召回打分器原叫 `RetrievalService`（放在 `service/retrieval/`），
  但它只是 `LocalKnowledgeBase` 的实现细节（换向量检索就整体替换），现为
  `provider/retrieval/LocalScorer`；同样，本地切片表 `ChunkIndexService` 只有检索适配器在用
  （classics 流水线自己从不调它），也归到 `provider/retrieval/`——**判断标准是"谁在用它"，
  不是"它碰不碰 mapper"**。
- **两个角色共用的纯算法放 `text/`**。`TextSegmenter`（切片）与 `EvidenceLocator`（引用 → 字符区间 + 页码）
  同时被 `service/classics`、检索适配器与三个图存储实现调用；放在任何一方都会让另一方反向依赖，
  所以单独成包。这个包是**有意的例外**：无状态、无 Spring 依赖、纯函数，因此可以被任何层引用。
- **通用工具放 `common/`**（如 `common/CsvLite`），不混在某个 `service/` 里。

依赖方向是**可机械校验**的，不是口头约定（见 §5.3）：`service` 不许 import `provider`，
反过来 `provider` 也不许 import `service`，`spi` 不许 import `service` / `contract`，
`common` 不许 import `config`。

### 5.2 与源工程的模块对应

| 源工程（Python） | 本工程（Java） |
|---|---|
| `pipeline.py`（1920 行） | `service/classics/PipelineOrchestrator` + `text/TextSegmenter` + `service/classics/ExtractionMerger` + `service/classics/RetryPolicy` |
| `models.py`（典籍域） | `model/classics/*` + `mapper/classics/*` |
| Outbox + `dispatcher.py` | `service/outbox/OutboxService` + `service/outbox/OutboxDispatcher`（`FOR UPDATE SKIP LOCKED`）+ `spi/outbox/OutboxEventHandler` |
| `providers.py` / `patient_provider.py` | `spi/ai/LlmProvider` + `provider/ai/TemplateLlmProvider` + `OpenAiCompatibleLlmProvider` |
| Paddle-VL OCR | `spi/ocr/OcrProvider` + `provider/ocr/HttpOcrProvider` + `service/ocr/PdfPageRenderer` + `service/ocr/OcrTextAssembler` + `service/classics/DocumentNormalizer` |
| RAGFlow 客户端 | `spi/retrieval/KnowledgeBase` + `provider/retrieval/LocalKnowledgeBase` / `RagflowKnowledgeBase` / `HybridKnowledgeBase` + `provider/retrieval/ChunkIndexService`（本地切片表）+ `ChunkVectorService`（`chunk_vector` 向量）+ `HybridScorer`（词面/向量融合）+ `spi/ai/EmbeddingModel`（旧实现：`LocalScorer` 纯词面打分，向量关掉时仍走它） |
| `graph.py` + Neo4j | `spi/graph/GraphStore` + `provider/graph/JdbcGraphStore` / `Neo4jGraphStore` / `DualGraphStore` |
| `evidence.py` | `text/EvidenceLocator`（字符区间 + 页码回溯） |
| `qa_worker.py`（三路并行） | `service/qa/QaService`（模式路由 + 落库）+ `spi/qa/AnswerBranch` ×3 + `service/qa/BranchOrchestrator`（并行、超时、汇总）+ `service/qa/BranchAggregator` |
| `qa_prompts.py`（分支枚举与凭据分类） | `BranchResult` 的 `status`/`basis` 常量 + `QaContract.BranchView`（`BRANCHES` 由 `@Component` 自动注册取代） |
| **节点级租约 / 心跳 / 断点续跑** | **已实现**：`service/qa/run/QaRunService` + `QaRunNodeExecutor` + `qa_run` / `qa_run_node` / `qa_event` / `qa_attempt` 四表（见 §3.9） |
| `patient_worker.py` | `service/patient/PatientService` + `model/patient/MetricEvaluator`（越界判定是值对象逻辑，不是编排） |
| `patient_*` 十余模块（生命周期/批次/删除台账/对象） | `service/patient/PatientLifecycleService`（状态机 + 乐观锁）+ `PatientBatchService`（逐行事务 + 幂等）+ `PatientDeletionService`（四段式 + 台账）+ `PatientObjectService` + `PatientOrphanService` |
| `tracing_*`（业务投影/导出/保留策略） | `service/tracing/TracingService` + `TraceContext` + `spi/tracing/TraceWriter`（async/direct）+ `TraceQueryService` + `service/tracing/TraceExporter`（OTLP）+ `TraceRetentionService` |
| `auth.py` | `service/auth/`：`SessionToken`（v2 载荷）+ `AuthService`（认证）+ `PasswordHash`（PBKDF2）+ `LoginThrottle`（失败锁定）+ `SessionRevocationRegistry`（登出吊销）；`controller/auth/`：`AuthController` + `CurrentUserArgumentResolver`（含滑动续期）+ `SessionCookie`（唯一写 Cookie 的地方）；角色由 `common/AccessControl#rolesOf` 现算 |

分层命名统一为 `{角色}/{业务域}`：`controller` / `service` / `mapper` / `model` / `contract` /
`spi` / `provider` / `worker` 是角色，`classics` / `patient` / `qa` / `tracing` 等是业务域。
源工程是 `{域}/{contract|model|mapper|service|api}`，本工程把主轴翻了过来——
先看"这一层干什么"，再看"哪个域的"。
十个扩展点（模型 / 向量化 / OCR / 知识库 / 图存储 / 对象存储 / 问答分支 / 流水线节点 / 观测写入器 / 患者状态机）
都收在**接口 + 多实现 + 装配点**后面，业务代码只依赖接口；完整的扩展方法见
[`docs/extending/EXTENDING.md`](docs/extending/EXTENDING.md)。

### 5.3 依赖方向规则（可机械校验）

包分得整齐不代表依赖方向对。一次"顺手 import 一下具体实现"编译器完全不报，
等到要换实现那天才以"改不动"的形式暴露。所以这几条被写成**可执行检查**：

```bash
python tools/check-layering.py            # 违规即退出码 1
python tools/check-layering.py --verbose  # 附带包规模与依赖矩阵

python tools/smoke/verify_all.py          # 真实链路 45 项断言（需应用已起）
python tools/smoke/verify_ocr.py          # OCR 契约 + 探活 + 端到端 23 项（需 OCR 服务已起）
python tools/smoke/verify_auth_store.py   # 认证状态「重启不丢」：分四段跑，中间要手动重启应用
```

| 禁止方向 | 为什么 |
|---|---|
| `service` → `provider` | 业务层依赖具体实现，接口形同虚设 |
| `provider` → `service` | 适配器反向调应用层：换实现时会连带改业务，端口失去意义 |
| `spi` → `service` / `provider` | 扩展点契约依赖实现，换实现就得改契约 |
| `spi` → `contract` | 接口层不该知道对外的 API 长什么样 |
| `common` → `config` / `service` | 公共内核依赖装配层或业务层，内核会被拖着走 |
| `model` / `mapper` → `service` / `provider` | 分层倒置 |

**没有例外。** 曾经留过一条"`provider` → `service` 允许"的口子，理由是"适配器复用领域逻辑"；
2026-11 第二轮归位时把它关掉了——真正需要共用的其实是**纯算法**，那类东西抽到 `text/`
（`TextSegmenter`、`EvidenceLocator`）就够了，剩下的反向调用全是错位。
脚本同时检查 `import` 和 javadoc 里的 `{@link ...}`：后者不产生编译依赖，
但同样会把上层写进下层的注释里，换实现时就是新的负担。

---

## 6. 关键设计（这几条才是复刻的意义）

**① 节点级检查点，不是事务级回滚**

`PipelineService` 故意不把整条流程包进一个事务：每个节点完成即提交。
中途失败时前面节点的成果仍然有效，重试直接从断点继续。
实测第 11 项就是靠这个做到秒级重跑。

**② 分段抽取 + 体量敏感二分**

`RetryPolicy` 把模型错误分成三类：
体量敏感 → 直接二分（不浪费一次重试）；可重试 → 指数退避加抖动；不可重试 → 立即失败。
二分受 `maxSplitDepth` 限制，避免把 20 字的段落切成单字。

**③ 检查点键与分段序号解耦**

`Segment.checkpointKey()` 用「区间 + 内容哈希」，不用 index。
二分后序号会变，用 index 会让缓存全部失效——这是源工程踩过并修正的地方。

**④ 每条边必须能溯源**

`GraphStore` 里引用定位失败的关系**整条丢弃**（记录 `dropped_relations`），
不留一条没有出处的边。医疗场景下"看起来有依据"比"少一条关系"更危险。

**⑤ 幂等与"不伪造成功"**

同 Key 不同内容直接 409；模型返回空内容抛异常而不是写一条空回复；
文档未 `READY` 时问答直接拒绝；OCR 未启用时扫描件报错而不是产出空正文。
这四条都来自源工程 `AGENTS.md` 的硬约束。

**⑥ 三个外部依赖都是「三态 + 启动期定型」**

`OcrConfig` / `RetrievalConfig` / `GraphConfig` 在启动期就把 Bean 装配好：
配了 `provider` 却缺地址或密码 → **`IllegalStateException` 拒绝启动**，不做运行期猜。
这样"配错了"在启动日志里就能看见，而不是等到第一个用户上传扫描件才炸。

**⑦ 扫描件判定看「有效字符数」，OCR 行重建看坐标**

- 判定：文本层去空白后 `< textLayerMinChars` 才算扫描件（原因见 2.1）。
- 重建：`OcrTextAssembler` 按 `centerY` 聚类成段（`LINE_GAP_RATIO=0.7`、`MIN_GAP_PX=6`），
  段内按 `x1` 排序；中文相邻不加空格、含 ASCII 才补空格——否则会得到「四 君 子 汤」。
- 单页 OCR 失败重试一次，仍失败则**整篇失败**：宁可让用户重传，也不交一份"看起来完整其实缺页"的正文。

**⑧ RAGFlow：HTTP 200 也可能是业务错误**

所有响应统一过 `unwrap()` 校验 `code == 0`；`hybrid` 模式下建数据集到解析完成
有超时与轮询上限，失败会清掉半成品数据集，避免在知识库里堆垃圾数据集。

**⑨ Neo4j 熔断：别把降级做成"更慢的失败"**

默认驱动事务重试时间是 30s，Neo4j 挂掉时 `/graph` 会挂 60 秒才超时——
比直接报错还糟。所以：`withMaxTransactionRetryTime(3s)` +
`unavailableUntil` 原子时间戳熔断（冷却 30s），且**只对连接类异常熔断**，
Cypher 语法错必须原样抛出（那是代码 bug，不能被降级掩盖）。
实测 60s → 首次 3.4s、后续 0.6s，恢复后自动回切。

**⑩ 外部检索缺偏移时回填，不猜**

RAGFlow 返回的切片没有页码与字符区间，`HybridKnowledgeBase.enrich()` 用本地切片
`squash`（去全部空白）比对回填 `page/charStart/charEnd`；
比对不上就保持 `null`——引用宁可"没有精确定位"，也不给一个编出来的位置。

**⑪ 并行问答：三路隔离、失败不连坐、汇总不编**

`BranchOrchestrator` 里每一路都在独立任务里跑，异常只让该路变 `FAILED`；
编排器按「全部成功 / 部分成功 / 全无依据」三档给结果，部分成功时**已完成的结论不会丢**，
只把没纳入的原因写进答案末尾（"未纳入汇总：中医图谱（无依据）"）。
预取（检索/取图/取指标）只做一遍，三路共享同一份事实，避免各查各的导致结论不一致。

还有一条更重要的取舍：**汇总用规则拼装，不叫模型"综合"三路意见**。
模型综合看起来更聪明，但会在这最后一步引入没有出处的新说法——那是本工程最不能接受的事。

---

## 7. 配置

全部可用环境变量覆盖（见 `application.yml`）。

**基础**

| 变量 | 默认 | 说明 |
|---|---|---|
| `HEALTHOS_DB_URL` | `jdbc:postgresql://127.0.0.1:55433/healthos` | 数据库 |
| `HEALTHOS_AUTH_USER` / `_PASSWORD` | `admin` / `admin123` | 登录账号（本地明文；非 local 必须是 `{pbkdf2}` 哈希） |
| `HEALTHOS_SESSION_SECRET` | `local-dev-only-change-me` | 会话签名密钥；非 local 要求 ≥32 字节，否则拒绝启动 |
| `HEALTHOS_COOKIE_SECURE` | `false` | Cookie 的 `Secure` 标记；**HTTPS 部署必须置 true**，本地 http 必须 false |
| `HEALTHOS_AUTH_SESSION_STORE` | `jdbc` | 吊销名单与失败计数的存储：`jdbc`（落库、重启不丢、多实例共享）或 `memory`（仅单实例调试；重启即清空、多实例各算各的） |
| `HEALTHOS_AI_PROVIDER` | `template` | `template` 或 `openai` |
| `HEALTHOS_AI_BASE_URL` / `_API_KEY` / `_MODEL` | DeepSeek 默认 | 真实模型接入 |

**OCR**

| 变量 | 默认 | 说明 |
|---|---|---|
| `HEALTHOS_OCR_PROVIDER` | `none` | `none` / `generic` / `paddle`。**只有它决定走哪个接口**：`paddle` → `POST /predict/ocr_system`，`generic` → `POST /ocr` |
| `HEALTHOS_OCR_PROTOCOL` | 空（跟随 `provider`） | 历史别名，留空即可。显式写成与 `provider` 不一致 → 拒绝启动（防止"配了 paddle 却在打 /ocr"） |
| `HEALTHOS_OCR_BASE_URL` | 空 | 配了 `provider` 却留空 → 拒绝启动。**只给到主机和端口**（`http://127.0.0.1:8868`），带 `/predict/...` 之类路径 → 拒绝启动 |
| `HEALTHOS_OCR_API_KEY` | 空 | 可选鉴权头 |
| `HEALTHOS_OCR_LANGUAGE` | `chi_sim` | 传给 OCR 服务 |
| `healthos.ocr.dpi` / `max-pages` / `min-confidence` | `200` / `200` / `0.45` | 渲染与过滤 |
| `healthos.ocr.text-layer-min-chars` | `20` | 低于此值判为扫描件 |

**检索**

| 变量 | 默认 | 说明 |
|---|---|---|
| `HEALTHOS_RETRIEVAL_PROVIDER` | `local` | `local` / `ragflow` / `hybrid` |
| `HEALTHOS_EMBEDDING_PROVIDER` | `ollama` | `ollama`（本地向量）/ `none`（退回纯词面）；向量化是可选增强，连不上不影响启动 |
| `HEALTHOS_EMBEDDING_BASE_URL` | `http://127.0.0.1:11434` | Ollama 地址 |
| `HEALTHOS_EMBEDDING_MODEL` | `bge-m3` | 换模型必须同步改 `_DIM` |
| `HEALTHOS_EMBEDDING_DIM` | `1024` | 维度不符会在每次响应上报错，而不是悄悄入库 |
| `HEALTHOS_RETRIEVAL_VECTOR_WEIGHT` | `0.6` | 融合排序里向量的权重（0~1） |
| `HEALTHOS_RETRIEVAL_VECTOR_MIN_SCORE` | `0.45` | 词面完全没命中时，向量分的准入门槛 |
| `HEALTHOS_RAGFLOW_BASE_URL` | `http://127.0.0.1:9380` | RAGFlow 实例 |
| `HEALTHOS_RAGFLOW_API_KEY` | 空 | 启用 `ragflow`/`hybrid` 时必填 |
| `HEALTHOS_RAGFLOW_SIMILARITY_THRESHOLD` | `0.2` | 对齐原工程 preset；低于该分的切片不参与作答 |
| `HEALTHOS_RAGFLOW_KEYWORDS_SIMILARITY_WEIGHT` | `0.3` | 关键词权重，向量权重取 `1 - 该值` |
| `HEALTHOS_RAGFLOW_RERANK_ID` | 空 | 重排模型 ID，空表示不重排（同原工程 preset） |
| `HEALTHOS_QA_MODE` | `single` | `single` / `parallel`（三路并行 + 汇总） |
| `HEALTHOS_QA_BRANCH_TIMEOUT` | `20s` | 单路分支硬超时，超时判该路失败 |
| `HEALTHOS_RAGFLOW_DATASET_PREFIX` | `healthos-` | 数据集名前缀 |
| `HEALTHOS_AGENT_LAB_TRANSPORT` | `in-process` | `in-process` / `stdio`（真子进程 MCP，需 Node ≥18）；未知值启动失败 |
| `HEALTHOS_AGENT_LAB_NODE` | `node` | `stdio` 形态的解释器命令，可给绝对路径 |
| `HEALTHOS_AGENT_LAB_SCRIPT` | `tools/mcp-server/patient-business.js` | MCP Server 脚本路径 |
| `HEALTHOS_AGENT_LAB_TOOL_ENDPOINT` | `http://127.0.0.1:18100/internal/agent-lab/tools` | 子进程回调的内部端点，**只许回环** |
| `HEALTHOS_AGENT_LAB_INTERNAL_TOKEN` | 空（启动时随机生成） | 父子进程互认令牌；留空即每进程一个随机值，不落配置文件 |
| `HEALTHOS_MODEL_USAGE_ENABLED` | `true` | 关闭后既不记账也不设闸门 |
| `HEALTHOS_MODEL_USAGE_DAILY_TOKENS` | `0` | 单条主线当日 token 上限，`0` = 不限 |
| `HEALTHOS_MODEL_USAGE_DAILY_COST_MICROS` | `0` | 单条主线当日成本上限（微元，1 单位货币 = 1000000），`0` = 不限 |
| `HEALTHOS_MODEL_USAGE_PEAK_MULTIPLIER` | `2.0` | 峰时倍数；`1.0` 即不分峰谷。峰窗 `healthos.model-usage.peak-windows-utc`（UTC 工作日），币种 `currency`（默认 `USD`）与三档单价在 YAML 里配 |
| `HEALTHOS_RAGFLOW_PARSER_ID` | `naive` | 解析器 |
| `HEALTHOS_RAGFLOW_EMBEDDING_MODEL` | 空 | 留空用实例默认 |

**图存储**

| 变量 | 默认 | 说明 |
|---|---|---|
| `HEALTHOS_GRAPH_STORE` | `jdbc` | `jdbc` / `neo4j` / `dual` |
| `HEALTHOS_NEO4J_URI` | `bolt://127.0.0.1:7687` | |
| `HEALTHOS_NEO4J_USER` | `neo4j` | |
| `HEALTHOS_NEO4J_PASSWORD` | 空 | 启用 `neo4j`/`dual` 时必填 |
| `HEALTHOS_NEO4J_DATABASE` | `neo4j` | |
| `HEALTHOS_GRAPH_NEO4J_HEALTH` | `false` | 是否让 `/actuator/health` 探测 Neo4j。默认关：`jdbc` 模式下 Neo4j 根本不是依赖，开着会因 7687 连不上把整体报 DOWN。用 `neo4j`/`dual` 部署时设 `true` |

**观测**

| 变量 | 默认 | 说明 |
|---|---|---|
| `HEALTHOS_TRACING_ENABLED` | `true` | 关掉后所有 `beginTrace` 返回 `null`、`span` 变空对象，业务代码无需判空 |
| `HEALTHOS_TRACING_WRITER` | `async` | `async`（单写线程 + 有界队列，满了丢并计数）/ `direct`（同步，便于测试） |
| `HEALTHOS_TRACING_RETENTION_DAYS` | `14` | 写入时算进 `expires_at`，改小不会立刻删老数据 |
| `healthos.tracing.queue-capacity` | `10000` | 异步队列长度 |
| `healthos.tracing.max-spans-per-trace` | `500` | 超限后新 span 被丢弃并写一条 `spans_truncated` 事件 |
| `healthos.tracing.retention-cron` | `0 30 3 * * *` | 每日清理；也可 `POST /traces/maintenance/sweep?dryRun=false` 手动触发 |

**患者域**

| 变量 | 默认 | 说明 |
|---|---|---|
| `healthos.patient.object-prefix` | `patients` | 附件在对象存储里的前缀 |
| `healthos.patient.max-object-bytes` | `20971520` | 单个附件上限（20 MB） |
| `healthos.patient.max-batch-rows` | `5000` | 单批次最大行数，超限直接拒绝而不是跑一半 |

**照护文档导出**

| 变量 | 默认 | 说明 |
|---|---|---|
| `HEALTHOS_CARE_PDF_ENABLED` | `true` | 关掉后 `/pdf` 直接 `503`，JSON 导出不受影响 |
| `HEALTHOS_CARE_PDF_FONT_PATH` | 空 | 指向 `.ttf`/`.ttc`；留空按候选列表探测本机字体 |
| `healthos.care.signer-users` | `[admin, doctor]` | 可签发账号；**空列表 = 谁都不能签**，不是谁都能签 |
| `healthos.care.min-review-comment-chars` | `5` | 签发时审核意见最少有效字数 |

字体状态在 `/health/dependencies` 的 `carePdf` 里（`ok(font=msyh.ttc)` / `unavailable(reason=...)`），
部署机器缺字体时先看这里，别等第一次导出才发现。

**模型路由（默认关闭）**

| 变量 | 默认 | 说明 |
|---|---|---|
| `HEALTHOS_MODEL_ROUTING_ENABLED` | `false` | 打开后 `/api/v1/system/model-routes` 才可用 |
| `HEALTHOS_MODEL_ROUTING_MASTER_KEY` | 空 | **启用时必填**：加密模型密钥（AES-GCM）并计算配置指纹（HMAC）；换掉它等于轮换主密钥，旧密文会报 `MODEL_CREDENTIAL_UNREADABLE` |
| `HEALTHOS_MODEL_ROUTING_PROXY` | 空 | 服务端代理；留空表示不支持 `system_proxy` 连接模式 |
| `HEALTHOS_MODEL_ROUTING_BASELINE_MODEL` | `qwen-max` | 迁移基线模型：与它一致且协议为 `chat` 的配置视为"一直在用"，免能力探测 |
| `HEALTHOS_MODEL_ROUTING_BASELINE_BASE_URL` | DashScope 兼容地址 | 迁移基线地址 |
| `healthos.model-routing.provider-timeout-seconds` | `120` | 单次探测的供应商超时 |
| `healthos.model-routing.structured-output-max-tokens` | `8000` | 结构化输出的最大 token |

启用时也**必须**给 `HEALTHOS_AI_API_KEY`（探测与真调用都要用）；
配置中心里的密钥走 `model_credentials` 表加密存储，与环境变量是两套，互不覆盖。

**访问控制与认证**

| 变量 | 默认 | 说明 |
|---|---|---|
| `healthos.auth.users` | 空 | 多账号列表；配了它就以列表为准，没配仍用上面的单账号 |
| `healthos.auth.session-ttl` | `8h` | 令牌有效期；剩余不足 1/3 时下一次请求自动续期（沿用原 `jti`） |
| `healthos.auth.max-login-attempts` | `5` | 同一账号连续失败次数上限，超过即锁定 |
| `healthos.auth.lock-duration` | `15m` | 锁定时长；同时决定计数窗口（窗口 = 锁定 × 2） |
| `healthos.auth.cookie-secure` | `false` | 同上，Cookie 的 `Secure` 标记 |
| `healthos.security.admin-users` | `admin` | 运维账号：孤儿扫描/清理、观测保留策略清理 |
| `healthos.security.trace-global-users` | `admin` | 轨迹全局读白名单（轨迹里含问答原文与患者 ID） |
| `healthos.security.model-admin-users` | `admin` | **模型配置管理**白名单（角色 `MODEL_ADMIN`），与运维白名单分开 |

患者域数据**一律按归属人隔离，管理员也不例外**——"能看别人档案"不该由一个默认配置悄悄打开。
越权一律返回 `404` 而不是 `403`：403 等于告诉对方"这个 ID 确实存在"，那本身就是信息泄露。
详见 [`docs/extending/EXTENDING.md`](docs/extending/EXTENDING.md) 第 8.7 节。

生成口令哈希（非 local 环境唯一可用的形式）：

```bash
java -cp target/classes com.kangheng.healthos.service.auth.PasswordHash '你的口令'
# => {pbkdf2}210000$<salt>$<hash>
```

`healthos.environment != local` 时启动自检会**逐项拒绝**：

| 检查 | 拒绝条件 |
|---|---|
| 会话密钥 | 为空、仍是 `change-me` 占位值、或短于 32 字节（签名强度由密钥决定，换密钥就能伪造任意用户） |
| 账号口令 | 配了 `users` 就查列表、没配就查单账号；**任一账号口令为空即拒绝**（空口令账号等于后门） |
| 口令存储 | 出现明文口令即拒绝——配置文件会进版本库、进镜像、进备份，明文等于口令公开 |

源工程在这里给了 `change_me` 默认值静默降级，本次刻意改成 fail-fast。

---

## 8. 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/auth/login` | 登录，签发 HMAC 签名 Cookie；失败 5 次后 `429` |
| POST | `/api/v1/auth/logout` | 登出：把 `jti` 记入吊销名单 + 清 Cookie（幂等） |
| GET | `/api/v1/auth/me` | 当前身份、角色（服务端现算）与到期时间 |
| POST | `/api/v1/classics` | 上传（multipart + `Idempotency-Key`） |
| GET | `/api/v1/classics` | 分页列表 |
| GET | `/api/v1/classics/{id}` | 详情 + 五个节点状态与明细 |
| GET | `/api/v1/classics/{id}/nodes` | 节点列表 |
| POST | `/api/v1/classics/{id}/retry` | 重试（复用检查点） |
| GET | `/api/v1/classics/{id}/graph` | 图谱（节点/边/证据） |
| GET | `/api/v1/classics/{id}/graph/neighbors` | **多跳邻域**（`entityId`、`depth`，默认 1，上限 4） |
| GET | `/api/v1/classics/{id}/chunks` | 切片分页（含 `charStart`/`charEnd`/`pageFrom`），引用反查原文用 |
| GET | `/api/v1/classics/{id}/chunks/{chunkId}` | 单条切片；切片不属于该文档 → `404` |
| GET | `/api/v1/classics/{id}/evidence` | 抽取证据分页（实体/关系的原文出处） |
| GET | `/api/v1/classics/{id}/artifacts` | 产物清单（`MARKDOWN` / `EXTRACTION` / `MANIFEST` 等，含大小与生成时间） |
| GET | `/api/v1/classics/{id}/artifacts/{artifactId}/download` | 下载产物；`Content-Disposition` 带 RFC 5987 中文文件名，产物内容缺失 → `500 ARTIFACT_CONTENT_MISSING` |
| POST | `/api/v1/qa/ask` | 带引用问答；`mode=single`（默认）/ `parallel`（三路并行）；带 `patientId` 时**额外合并该患者绑定知识库的命中** |
| GET | `/api/v1/qa/patients/{patientId}/knowledge-bases` | 患者知识库绑定列表 + 可用数据集（`available` 带 `bound` 标记） |
| POST | `/api/v1/qa/patients/{patientId}/knowledge-bases` | 绑定数据集（`datasetId` 必须存在于知识库；重复绑定幂等返回同一条） |
| DELETE | `/api/v1/qa/patients/{patientId}/knowledge-bases/{bindingId}` | 解绑；**不删远端数据集** |
| POST | `/api/v1/qa/patients/{patientId}/knowledge-bases/{bindingId}/reconcile` | 校验绑定是否仍指向存在的数据集；失效标 `STALE` 并保留记录 |
| GET | `/api/v1/qa/branches` | 当前注册的问答分支（自描述，加分支后自动出现） |
| GET | `/api/v1/model-usage/summary?days=7` | **模型成本看板**：按模型 / 按主线聚合，含 `cachedPromptTokens`、`cacheHitRate`、`unpricedCalls`、当日配额、`peakNow`；仅运维账号（其它账号 `403`） |
| GET | `/api/v1/qa/conversations/{id}/messages` | 历史消息（含当时的引用快照与分支明细） |
| POST | `/api/v1/qa/runs` | **持久化问答运行**（`202`）；带 `idempotencyKey`，同键同问题返回同一个 run |
| GET | `/api/v1/qa/runs/{runId}` | 运行详情（节点明细 + 尝试记录 + 冻结快照） |
| GET | `/api/v1/qa/runs/{runId}/events?afterSeq=&limit=` | 增量拉事件（SSE 断了用它补齐） |
| GET | `/api/v1/qa/runs/{runId}/stream?afterSeq=` | **订阅事件流（SSE）**，`text/event-stream`，终态主动断开 |
| POST | `/api/v1/qa/runs/{runId}/retry` | 重试失败节点（`nodeKeys` 留空 = 全部失败节点）；**汇总节点一并重跑** |
| POST | `/api/v1/qa/runs/{runId}/cancel` | 取消运行（已终态则幂等返回当前状态） |
| POST | `/api/v1/patients` | 建档（可带 `externalRef`、`draft=true` 落草稿） |
| POST | `/api/v1/patients/{id}/metrics` | 上报指标（同 code 覆盖） |
| GET | `/api/v1/patients/{id}` | 档案 + 指标 + 异常计数 |
| PATCH | `/api/v1/patients/{id}` | 编辑（必带 `expectedRevision`，版本不符 → `409 PATIENT_REVISION_CONFLICT`） |
| POST | `/api/v1/patients/{id}/confirm`、`/archive`、`/restore` | 状态流转（各带 `expectedRevision`）；`DELETED` 为终态，`restore` 不允许"复活" |
| GET | `/api/v1/patients/{id}/revisions` | 修订列表 |
| GET | `/api/v1/patients/{id}/revisions/{revision}` | 某版快照（含 `sha256`） |
| GET | `/api/v1/patients` | 分页列表 |
| POST | `/api/v1/patients/batches` | 批次导入（multipart，CSV/JSON 自动识别；`202` + `batchId`；同 `sha256` 返回 `reused=true`） |
| GET | `/api/v1/patients/batches/{batchId}` | 批次进度与逐行结果（`ok`/`fail`/`total`） |
| POST | `/api/v1/patients/batches/{batchId}/retry` | 只重跑失败行 |
| POST | `/api/v1/patients/{id}/objects` | 上传附件（multipart，落 `patients/<id>/<objectId>.<ext>`，`putAt` 不去重） |
| GET | `/api/v1/patients/{id}/objects` | 对象清单 |
| DELETE | `/api/v1/patients/{id}/objects/{objectId}` | 删除单个附件 |
| GET | `/api/v1/patients/maintenance/orphans` | 孤儿扫描（**只读**，不改任何东西） |
| POST | `/api/v1/patients/maintenance/orphans/purge` | 清理孤儿（必须显式点名 key，越界/仍被引用会跳过并回报） |
| POST | `/api/v1/patients/{id}/deletion/previews` | 删除预览：算清资源 + 一次性 `previewToken`；`reason` 走请求体（中文理由不必自己编码） |
| GET | `/api/v1/patients/{id}/deletion/tasks`、`/{taskId}` | 删除任务列表 / 详情（含每条资源的 `erased`） |
| POST | `/api/v1/patients/{id}/deletion/tasks/{taskId}/confirm` | 确认删除（令牌 + 版本号双校验，不符 → `403 DELETION_TOKEN_INVALID`） |
| GET | `/api/v1/patients/deletions/{taskId}/ledger` | 台账回读，`verified=false` 表示内容与登记哈希不一致 |
| GET | `/api/v1/care/catalog` | 报告/计划模板目录（分节 key、类型、必填项） |
| POST | `/api/v1/care/reports` | 生成客户健康评估报告（`202` + `documentId`） |
| GET | `/api/v1/care/reports`、`/api/v1/care/plans` | 报告 / 计划列表（按 owner 过滤） |
| GET | `/api/v1/care/documents/{id}` | 文档详情（分节内容、状态、`revision`、`signature`、打卡任务） |
| PATCH | `/api/v1/care/documents/{id}/sections/{key}` | 编辑单个分节（`expectedRevision` 不符 → `409`） |
| POST | `/api/v1/care/documents/{id}/sign` | 签发（报告类）；签发后 `status=SIGNED` 并记 `signedRevision` |
| POST | `/api/v1/care/documents/{id}/revise` | 基于当前版本派生新修订（异步，`202`） |
| POST | `/api/v1/care/documents/{id}/confirm` | 确认执行计划（计划类） |
| PUT | `/api/v1/care/documents/{id}/checkins/{taskId}` | 计划打卡 |
| GET | `/api/v1/care/documents/{id}/revisions`、`/audits` | 修订历史 / 操作审计 |
| GET | `/api/v1/care/documents/{id}/export` | **导出 JSON**（冻结的聚合快照 + 当前 `status`/`revision`/`signature`）；文档未完成 → `409 CARE_DOCUMENT_INCOMPLETE` |
| GET | `/api/v1/care/documents/{id}/pdf` | **导出 PDF**（内嵌中文字体，逐行排版 + 页码 + 未签发时的"草稿"水印）；无可用字体 → `503 CARE_PDF_UNAVAILABLE` |
| GET | `/api/v1/patients/{patientId}/memory` | 当前记忆版本（不可变版本链的"现在指针"） |
| POST | `/api/v1/patients/{patientId}/memory/initialize` | 初始化（幂等）：从已冻结来源采样生成 v1 |
| GET | `/api/v1/patients/{patientId}/memory/versions` | 版本列表（按 `version` 倒序，分页） |
| GET | `/api/v1/patients/{patientId}/memory/versions/{memoryId}` | 版本详情 |
| GET | `/api/v1/patients/{patientId}/memory/versions/{memoryId}/export` | **导出 Markdown**（`X-Content-SHA256` 内容哈希 + `private,no-store` + UTF-8 文件名）；版本不存在 → `404` |
| POST | `/api/v1/patients/{patientId}/memory/followups` | 提交随访（`202`）：`expectedMemoryId` 做乐观并发，同 `operationId` 幂等、内容变了报 `MEMORY_OPERATION_CONFLICT`，**原文照搬不加工、不摘要** |
| GET | `/api/v1/patients/{patientId}/memory/followups` | 随访列表 |
| POST | `/api/v1/patients/{patientId}/memory/refresh` | 显式刷新（`202`）：把当前事实重新采样发布一版 |
| GET | `/api/v1/patients/{patientId}/memory/jobs/{jobId}` | 任务详情（状态机 `QUEUED→RUNNING→SUCCEEDED/FAILED/CANCELLED`） |
| POST | `/api/v1/patients/{patientId}/memory/jobs/{jobId}/retry` | 重试失败任务（退回 `QUEUED` 重投，不改请求内容） |
| POST | `/api/v1/patients/{patientId}/memory/jobs/{jobId}/cancel` | 取消排队/失败任务；已成功版本不可撤销（→ `409`） |
| GET | `/api/v1/traces` | 轨迹列表（`businessType`/`businessId`/`owner`/`status`/`from`/`to` + 分页） |
| GET | `/api/v1/traces/{traceId}` | span 树 + 事件 + `warnings` |
| GET | `/api/v1/traces/{traceId}/export` | OTLP/JSON（可直接投喂 Collector） |
| GET | `/api/v1/traces/overview` | 总量 / 失败数 / 运行中 / 按服务 p50·p95 |
| GET | `/api/v1/traces/maintenance` | 保留策略状态（上次清理时间、删了多少） |
| POST | `/api/v1/traces/maintenance/sweep?dryRun=true` | 保留清理，**默认预演**，`dryRun=false` 才真删 |
| GET | `/health`、`/health/dependencies` | 存活 / 依赖就绪（含 OCR、知识库、图存储、观测写入器、**PDF 字体**） |
| GET | `/api/v1/system/model-routes/permissions` | 当前账号在模型配置中心的权限（公开，用于前端隐藏入口） |
| GET | `/api/v1/system/model-routes` | 全部主线的当前配置（需 `MODEL_ADMIN`；响应一律 `no-store`，**不含密钥明文**） |
| GET | `/api/v1/system/model-routes/{pipelineKey}` | 单条主线的当前配置（`REPORT`/`QA`/`AGENT`/`MEMORY`/`EXTRACTION`/`GRAPH`/`EMBEDDING`） |
| PUT | `/api/v1/system/model-routes/{pipelineKey}` | 保存配置 → **追加新版本**；`expectedVersion` 不符 → `409 MODEL_CONFIG_CONFLICT`；同参重复保存 = 空操作（版本不变） |
| POST | `/api/v1/system/model-routes/{pipelineKey}/probe` | 能力探测（`connectivity`/`structured`/`streaming`/`tool_calling`）；有副作用故用 POST，10 秒内重复 → `429` |
| GET | `/api/v1/system/model-routes/{pipelineKey}/versions` | 历史版本（只追加，版本号单调递增） |
| POST | `/api/v1/system/model-routes/{pipelineKey}/rollback` | 回滚到历史版本 → **创建新版本**（不拨指针）；基线已变 → `409` |
| POST | `/api/v1/patients/{patientId}/collaborations` | 提交多智能体协同（`202` + `runId`）；`scenario` + `scopes` 不匹配 → `422 AGENT_SCENARIO_SCOPE_INVALID`；同 `operationId` 幂等 |
| GET | `/api/v1/collaborations/{runId}` | 协同运行详情（四角色节点状态、来源、答案、免责声明）；越权 → `404` |
| GET | `/api/v1/collaborations` | 运行列表（键集分页：`patientId` / `limit` / `beforeId`），只列自己的 |
| GET | `/api/v1/collaborations/{runId}/events?afterSeq=&limit=` | **增量事件**（轮询主通道）：`seq/type/nodeId/status/createdAt`，带 `nextSeq`/`hasMore`/`eventSeq`/`status` |
| GET | `/api/v1/collaborations/{runId}/stream?afterSeq=` | 事件流（SSE）；与轮询同一份事件、同一个 `seq`，终态主动断开 |
| POST | `/api/v1/collaborations/{runId}/cancel` | 取消（`expectedVersion` 可选）；终态幂等返回；版本落后 → `409 AGENT_VERSION_CONFLICT` |
| POST | `/api/v1/collaborations/{runId}/retry` | 重试失败运行；成功/取消不可逆 → `409 AGENT_RUN_STATE`；排队/执行中幂等返回（不重复入队） |
| GET | `/api/v1/collaborations/{runId}/nodes/{nodeId}?mode=redacted\|full` | 节点正文；默认脱敏，`full` 需全局读权限 + 7 天保留期，并落 `node.payload_read` 审计事件 |
| GET | `/api/v1/agent-lab/catalog` | Agent Lab 工具目录：6 个只读工具、4 个 Agent、服务状态与当日握手指标；`no-store` |
| POST | `/api/v1/agent-lab/services/{serviceId}/probe` | 协议校验前两步（`initialize` → `tools/list`），`output=null`；未知服务 `404 MCP_SERVICE_NOT_FOUND` |
| POST | `/api/v1/agent-lab/services/{serviceId}/call` | 三步协议 + 一次工具调用；越权工具 → 回执 `failed` + `MCP_TOOL_REJECTED`；输出空 → `MCP_OUTPUT_INVALID` |
| POST | `/api/v1/qa/messages/{messageId}/memo` | 显式收藏一条已完成助手答案；同消息重复点击幂等；未完成 / 无患者归属 → 明确错误码 |
| GET | `/api/v1/qa/patients/{patientId}/memos` | 书签列表（键集分页 `before` / `limit`），按 owner 隔离 |
| GET | `/api/v1/qa/memos/{memoId}` | 书签详情：冻结的问题、正文、引用快照与 `contentHash` |
| POST | `/api/v1/qa/bindings/{bindingId}/diagnostics` | 提交单路 RAG 诊断（`202` + `Location`）；必须带 `Idempotency-Key`，缺失 → `422`；同键换参数 → `409` |
| GET | `/api/v1/qa/conversations/{conversationId}/diagnostics` | 诊断列表（键集分页） |
| GET | `/api/v1/qa/diagnostics/{runId}` | 诊断详情：`result`（检索结论）+ `draft`（片段摘要，失败保留）+ `evidence[]` |
| GET | `/api/v1/patient-intakes/exams/{examId}/drafts/{scope}` | 资料录入审阅草稿（`basic` / `clinical`）；无草稿返回 `draft=null` |
| PUT | `/api/v1/patient-intakes/exams/{examId}/drafts/{scope}` | 保存草稿（`baseRevision` + `expectedVersion`）；版本/修订冲突 `409`；**不改临床修订与确认状态** |
| DELETE | `/api/v1/patient-intakes/exams/{examId}/drafts/{scope}` | 删除草稿（幂等） |
| POST | `/api/v1/patients/{patientId}/memory/agent-followups` | 记忆 Agent 摘录候选（`202`）；按 `operationId` 幂等；候选 `PENDING`，不自动写记忆版本 |
| GET | `/api/v1/patients/{patientId}/memory/agent-notes` | 摘录候选列表 |
| POST | `/api/v1/patients/{patientId}/memory/agent-notes/{noteId}/publish` | 显式发布：校验 `candidateHash` / 逐字摘录 / `sourceHash`，通过后置 `PUBLISHED`（幂等） |

---

## 9. 与原工程的差异（诚实清单）

先说结论：**原件是 v2 全量平台（patient / memory / agent / tracing 五大域、约 170 个后端模块），
本工程是其中的「典籍 + 问答 + 患者指标」垂直切片。**下面是逐项对账，包括我此前写错、现已更正的一条。

| 项 | 源工程 | 本工程 | 原因 |
|---|---|---|---|
| 扫描件 OCR | 百度 Paddle-VL | **已接入并真联调**：`generic`（HTTP multipart）/ `paddle`（hubserving `POST /predict/ocr_system`）两种客户端。`paddle` 已对仓库内**真 PaddleOCR 服务**（PP-OCRv3 / paddle 2.6.2）跑通端到端：上传扫描件 → 202 → 五节点全 SUCCEEDED → **库里 chunk 与识别结果 6/6 行逐字命中**，`/health/dependencies` 报 `ocr: ok` | 原判断「付费服务无法验证」是**误判**：`/predict/ocr_system` + `{images:[base64]}` + `results[0].{text,confidence,text_region}` 是开源自建 hubserving 的形状，不需要账号（百度智能云那套是 `/rest/2.0/ocr/v1/...?access_token=` + `words_result`，代码不是照它写的）。真正没验的只剩百度云商用接口本身 |
| 知识库 | RAGFlow 外部服务 | **已接入**：`ragflow` / `hybrid`（双写 + 检索降级）；本地模式保留为默认 | RAGFlow 重量级（含 embedding 模型），默认不引入更利于开箱跑通 |
| 图谱存储 | Neo4j（Cypher） | **已接入**：`neo4j` / `dual`（PG 权威 + Neo4j 图查询 + 熔断降级） | 保留 PG 作权威存储，避免主链路被图库可用性绑死 |
| 向量检索 / 重排 | **也不在后端**：全部交给 RAGFlow Chat 的 preset（`similarity_threshold=0.2`、`keywords_similarity_weight=0.3`、`top_n=8`、`rerank_model` 默认空） | **参数已对齐并透传**：`/api/v1/retrieval` 带 `similarity_threshold`、`vector_similarity_weight`、`rerank_id`（实测 stub 收到 `threshold=0.2 keywords_weight=0.3`）。**本地检索已具备向量能力**：`bge-m3`（Ollama，1024 维）+ `chunk_vector` 表，与词面按 `0.6/0.4` 加权融合（见 §3.21）；同义查询实测由「0 条」变「2 条」。**差在**：无 ANN 索引（候选集小，Java 侧算余弦）、**无 cross-encoder 重排**——本机 Ollama 0.40 没有 `/api/rerank`，`bge-reranker-v2-m3` 只能当生成模型跑，拿不到分数；要真重排得起 llama.cpp server 或走 RAGFlow 的 `rerank_id` | 原工程后端并未使用 pgvector（`grep` 全文 0 命中），此前把它写成"源工程用 pgvector"是我的误判，已更正。重排的"融合"与"交叉编码器"是两件事：前者已在本地做，后者需要真的 rerank 端点，没端点就不假装有 |
| 问答路径 | 三路并行（`ragflow`/`modern`/`tcm`）+ `summary`，带节点级租约、心跳、断点续跑 | **三路并行 + 汇总已实现**（`AnswerBranch` SPI + `BranchOrchestrator`，错误码同名 `QA_BRANCHES_INCOMPLETE`）；**持久化 run + 断点续跑已实现**（`qa_run` / `qa_run_node` / `qa_event` / `qa_attempt` 四表，逐节点落库、失败节点单独重跑、汇总节点跟着重跑、事件流按 `seq` 断点续传、SSE 订阅，见 §3.9）。**差在**：无真正的租约与心跳（认领靠 Outbox 的 `FOR UPDATE SKIP LOCKED`，不是节点级租约），续跑靠"整轮重放"而非跨进程接管在途节点，`modern` 路只做患者指标、不含"模型常识"那半边 | 单机单次请求内编排，延迟可预测；医疗场景下"模型常识"这条路本来就该慎用 |
| 观测 / Tracing | `tracing_*` 一整套（业务投影、导出、保留策略、ClickHouse 诊断） | **已实现核心**：`trace_run` / `trace_span` / `trace_event` 三表 + W3C 风格 traceId/spanId + span 树 + OTLP/JSON 导出 + 按 `expires_at` 的保留清理 + p50/p95 总览。**差在**：无 ClickHouse 列存与跨服务采样决策，`parent` 关系靠线程内 `ThreadLocal` 搬运而非 W3C `traceparent` 头（跨进程会断链） | 单机切片先要"能自己看自己"；埋点若一开始就依赖外部 Collector，跑通成本会盖过业务本身 |
| 患者域范围 | 建档 / 批次导入 / 草稿 / 删除台账 / 孤儿回收 / 对象清单 / 资料录入 等十余模块 | **已实现主干**：`DRAFT→ACTIVE⇄ARCHIVED→DELETED` 状态机、乐观锁修订（`revision` + 409 冲突）、修订快照、CSV/JSON 批次导入（逐行独立事务 + sha256 幂等 + 按 `external_ref` upsert）、对象清单与附件、孤儿扫描/点名清理、删除四段式（预览令牌 → 确认 → 异步擦除 → 台账 `sha256` 可回读验证）、**资料录入链路**（上传 → OCR 归一化 → 分片抽取 → 两道人工核查 → 人工补录 → 生成档案，见 §3.8）、**按归属人隔离（owner 逐表过滤，越权 404）**。**已补**：资料录入审阅草稿 `patient_review_draft`（`basic` / `clinical` 两个 scope，`baseRevision` + `expectedVersion` 双锁，服务端暂存不改正式修订，见 §3.20）。**差在**：无字段级加密与留存期策略、无"法律冻结"、批次不处理跨行裸换行、录入抽取目前只覆盖 13 项常用指标与 6 个基础字段（不含结构化报告模板） | 删除与台账涉及合规，宁可做完整链路也不做半套；加密与冻结依赖 KMS 与法务规则，非本切片可拍板 |
| 任务队列 | Celery + Redis | Outbox + 有界线程池 | 源工程典籍链路本就是 Outbox + DB 调度 |
| 照护文档导出 | reportlab 生成 PDF（`STSong-Light`）+ JSON 导出 | **已实现**：JSON 导出（冻结聚合 + 当前签发状态）与 PDF 导出（PDFBox 3 + **内嵌真实中文字体**，逐行排版、页码、未签发时右上角"草稿"标记）。**差在**：只导报告与计划两类，不含源工程那套 Word/Excel 模板；字体靠本机已装的 `msyh.ttc` 等，探测不到就 `503` 而不是回退成"豆腐块" | PDFBox 没有内置 CJK 字体，reportlab 的 `STSong-Light` 是**字体名引用**而非嵌入，照搬会在别的机器上丢字；宁可显式不可用 |
| 典籍产物 | `chunks` / `evidence` / `artifacts` 查询与下载 | **已实现**：切片分页与单条、证据分页、产物清单与下载（按 kind 定 `Content-Type` 与扩展名，中文文件名走 RFC 5987）。**差在**：不做产物重建（只读已落库的），不提供批量打包 | 产物是流水线的中间留痕，先保证"查得到、下得来" |
| 问答知识库绑定 | 患者与 RAGFlow 数据集的绑定关系 | **已实现**：`qa_knowledge_binding` 表 + 绑定/解绑/校验（`reconcile`）接口；提问带 `patientId` 时把绑定库的命中**合并**进依据，**按正文内容去重**（跨库 chunkId 不同），合并失败只降级不中断。**差在**：不做跨来源重排（两种分不是同一把尺子），绑定库的检索仍依赖 RAGFlow 可用（本地模式没有"库"的概念） | 绑定是"这位患者的资料也一起看"，不是"换个库检索"；混排等于编一个不存在的序 |
| 患者长效记忆与随访 | `memory_*` 一族：不可变版本链、只从冻结来源取事实、随访幂等、Agent 摘录 | **已实现主干**：`patient_memory_version`（不可变版本链 + `contentHash`/`sourceHash`）、`patient_memory_state`（当前指针）、`patient_followup`（原文照搬）、`patient_memory_job`（异步发布，QUEUED→RUNNING→SUCCEEDED/FAILED/CANCELLED）；事实只从已落库档案修订、已签发照护修订、签署记录、打卡采样、随访原文取，逐条记 `contentHash`，导出 Markdown 带 `X-Content-SHA256` 可校验；随访 `operationId` 幂等、`expectedMemoryId` 乐观并发、异步由 Outbox 驱动。**已补摘录候选（收敛版）**：`memory_agent_note` 逐字摘录（`content.contains(quote)` + 覆盖全部分类）、`candidateHash`/`sourceHash`、显式发布与幂等（见 §3.20）；**差在**：无租约 / fencing token / 心跳 / attempt 表；摘录**不直接并入正式记忆版本**，发布凭据落在 note 行内（不污染"只认已发布修订"的冻结来源） | 单实例切片上，租约与 fencing 是为一台机器抢不到锁才需要的冗余；记忆的可信度来自"只认已发布修订"，掺模型摘要就破了这条线 |
| 多智能体 | 4 角色 + LangGraph + MCP | **已实现编排主干**：`AgentCollaborationService` 承担依赖图（`MEMORY ‖ PIPELINE → LIFESTYLE → SYNTHESIS`）、跳过语义、五类来源校验（`EMPTY_SOURCES`/`UNOBSERVED_SOURCES`/`TOOL_SOURCE_MISSING`/`HANDOFF_SOURCE_MISSING`/`ROLE_SCHEMA_INVALID`）、交接完整性（同轮 + `kind=role` + 成功 + `sha256(output)==outputHash`）与整轮成败判定；**事实由编排层读、执行器只做组织**（`AgentRoleExecutor` SPI），默认实现是**确定性模板执行器**（不调模型，只组织已读到的事实）。**已补齐**：事件流（`agent_run_event` + 增量轮询 + SSE + 断点续传）、控制面（`cancel` / `retry` / 列表 / 节点正文与审计）、断点续跑（重试复用已成功角色节点，不重跑）、Agent Lab 工具目录与三步协议校验（见 §3.17 / §3.18）——**并已支持真 STDIO MCP Server**：`--healthos.agent-lab.transport=stdio` 时拉起 `tools/mcp-server/patient-business.js`，由子进程自己声明工具清单、回调内部只读端点取数（`handshakeMs≈136ms`，实测见 §3.18）。**差在**：执行器不接真实模型；`stdio` 形态只用于这个诊断控制台，不被业务链路当常驻依赖 | 多智能体的难点从来不是"让模型说话"，而是"四个角色各自守规矩、交接可追溯、来源能核对"；先把骨架钉死，接模型时变的只是措辞。子进程形态的价值是证明"外部客户端能按协议接进来"，所以只做一次一进程、不做常驻 |
| 模型路由 | `model_route_*` 一族：每条业务主线各配各的模型与密钥、版本化、能力探测 | **已实现主干**：`model_routes`（当前指针）+ `model_route_versions`（不可变历史）+ `model_credentials`（AES-256-GCM 加密）+ `model_route_probes`（探测记录）四表；七条主线各自独立配置；乐观并发（`expectedVersion` → `409 MODEL_CONFIG_CONFLICT`）；**回滚 = 用旧配置创建新版本**（版本号单调递增，不拨指针）；四类合成能力探测（`connectivity`/`structured`/`streaming`/`tool_calling`，固定输入 `Return {"ok":true}`，**不碰患者数据、不执行工具**）；密钥 AAD 绑定 + 主密钥指纹 + HMAC 配置指纹；响应一律 `no-store` 且**永不回显明文密钥**；模型配置管理员名单与运维名单分开（`MODEL_ADMIN`）。**差在**：无配置生效灰度（切换即全量）、模型中转成本口径**只覆盖 chat 调用**（embedding 不计量）、无配置变更审计表（变更历史只在版本行里）、探测依赖外网可达（本机实测全部 `MODEL_CONNECTION_FAILED`） | 医疗场景下"哪条主线用哪个模型"必须可控且可追溯。**已补成本/配额**：`model_usage` 逐次记账 + 按主线的当日闸门（§3.22）；但"在没接真模型之前灰度是空转"这个判断没变，所以只做了能真验证的那部分 |
| 认证 / 会话 | 单账号存 `.secrets/credentials.local.json`；HMAC 令牌无 `jti`，登出只清 Cookie；无限流；口令明文比对 | **已加强**：多账号配置、令牌 v2（带 `jti`/`iat`）、**登出真实吊销**、滑动续期（沿用 `jti`）、按账号的失败锁定（`429`）、PBKDF2-HMAC-SHA256 口令哈希、启动期 fail-fast 自检、`/health/dependencies` 暴露认证姿态；**吊销名单与失败计数落库**（重启不丢、多实例共享，已用真实进程验证）。**差在**：没有用户表/角色表（账号仍是配置白名单，无授权管理层）；无"记住我"、无刷新令牌、无第三方登录 | 吊销与限流是安全项，靠"单实例可接受"糊过去等于把两个缺口写成设计；账号体系则属于产品决策，切片阶段不引入 |
| RBAC | 前端有编排页面、后端无表（静态数据，原型态） | **角色由配置白名单派生**（`ADMIN` / `TRACE_READER` / `USER`），每次请求现算；**没有**角色表、权限点与授权管理接口 | 真实需求是"谁是运维、谁能全局读轨迹"，两个白名单就够；引入 RBAC 表等于为一个未成形的需求先建三层抽象 |

> 两点补充：
> ① 源工程前端那个「RBAC 角色与权限编排」页面 0 个 API 调用、数据来自静态文件，属于原型态，本工程没有把它当真功能复刻。
> ② 这张表是**对账表不是宣传页**——每行都写过实际验证方式；发现写错就改，见上面"向量检索"那一行的更正说明。

---

## 10. 扩展点

扩展分两类：**十一条 SPI**（模型 / OCR / 知识库 / 图存储 / 对象存储 / 问答分支 / 流水线节点 / 观测写入器 / 患者状态机 / MCP 传输层 / 用量计量…）
+ **五类契约与数据层扩展**（智能体角色执行器、事件类型与控制命令、只读工具、草稿 scope、诊断模式）。
业务代码只依赖接口/契约；完整的操作步骤、代码骨架与验证命令在
**[`docs/extending/EXTENDING.md`](docs/extending/EXTENDING.md)**，这里只给索引：

| 扩展点 | 接口 | 怎么扩 | 会不会动业务代码 |
|---|---|---|---|
| 问答分支 | `spi/qa/AnswerBranch` | **加个 `@Component` 就自动参与并行** | 不会（`BranchOrchestrator` 注入 `List<AnswerBranch>`） |
| 模型 | `spi/ai/LlmProvider` | 加实现 + 配 `ai.provider` | 不会 |
| OCR | `spi/ocr/OcrProvider` | 加实现 + 配 `ocr.provider` | 不会 |
| 知识库 | `spi/retrieval/KnowledgeBase` | 加实现 + 配 `retrieval.provider` | 不会 |
| 图存储 | `spi/graph/GraphStore` | 加实现 + 配 `graph.store` | 不会 |
| 对象存储 | `spi/storage/ObjectStorage` | 加实现 + 配 `storage.type` | 不会 |
| 流水线节点 | `model/classics/PipelineNodeSpec` 枚举 | 加枚举值 + orchestrator 分支 + detail | 会（改 orchestrator） |
| 观测写入器 | `spi/tracing/TraceWriter` | 加实现（如 Kafka / OTLP gRPC），配 `tracing.writer` | 不会（默认 `async`） |
| 观测埋点 | `TracingService#span/event/callIn` | 在业务节点里包一层，跨线程用 `callIn` 搬 `Frame` | 只加埋点，不改逻辑 |
| **问答运行节点** | `service/qa/run/QaNodeSpec` | 加分支即多一个节点；持久化 run 自动把它纳入断点续跑 | 不会（节点由 `AnswerBranch` 派生） |
| 患者知识库 | `service/qa/KnowledgeBindingService` | 绑定/解绑/校验 + 检索合并；换库只换 `datasetId` | 不会（绑定是数据，不是代码） |
| 文档导出 | `service/care/CarePdfRenderer` | 换字体改 `care.pdf.font-path`；换版式改渲染器 | 只改渲染器 |
| 记忆发布 | `spi/outbox/OutboxEventHandler` | 新增异步发布类实现 `eventType()` 返回 `"patient_memory_publish"`，`@Component` 即自动注册 | 不会（调度器注入 `List<OutboxEventHandler>`） |
| **模型能力探测** | `spi/routing/RouteProbe` | 加实现 + 配 `model-routing`；契约自带 `RouteTarget`/`ProbeOutcome`，不依赖对外契约层 | 不会（探测结果只进配置中心，不改业务） |
| **智能体角色执行器** | `spi/agent/AgentRoleExecutor` | 加 `@Component` 实现即接管该角色；**事实由编排层读好再交给你**，执行器只负责组织与措辞 | 不会（编排、依赖、来源校验全在 service 层） |
| **事件类型 / 控制命令** | `contract/agent/AgentRunContract` | 词表常量收口在契约类；emit 挂在"状态已落库"之后，控制命令必须带 `expectedVersion` | 会（改契约词表，需同步 §8.99 的四条硬规矩） |
| **只读工具（Agent Lab）** | `model/agent/AgentTool` + `boundTools` | 加枚举值 + 声明绑定 + 执行分支 + **改 `CATALOG_VERSION`** | 会（动工具清单契约） |
| **草稿 scope / 诊断模式** | `PatientDraftService` / `QaDiagnosticService` | 加 scope 常量 + payload 白名单；诊断新模式先想清"失败算什么" | 不会（唯一约束与幂等键已就位） |
| **向量化模型** | `spi/ai/EmbeddingModel` | 换模型改 `healthos.embedding.*`（`model` 与 `dimension` 必须同步改）；换实现加 `@Bean` 到 `EmbeddingConfig` | 不会（检索只依赖 `EmbeddingModel` 接口） |
| **MCP 传输层** | `spi/agent/McpServerBridge` | 换语言/换框架重写 MCP Server，只改 `healthos.agent-lab.*` 四个键与 `tools/mcp-server/` 下的脚本；要新的传输方式（如 WebSocket）加实现 + 装配分支 | 不会（`AgentLabService` 只认端口） |
| **用量计量 / 配额** | `spi/routing/ModelUsageMeter` | 换计量后端（如带汇总表的自建服务）加实现；换计费口径只改 `healthos.model-usage.prices` | 不会（provider 只调 `assertWithinQuota`/`record`） |

几个具体的下一步：

1. **接真实模型**：`HEALTHOS_AI_PROVIDER=openai` + Base URL/Key/模型名。
   `OpenAiCompatibleLlmProvider` 已处理 JSON 结构校验、413 转二分、空输出报错。
2. **本地向量检索**：加 `EmbeddingProvider` + `chunk_vector` 表，只改 `LocalKnowledgeBase` 内部打分，
   `KnowledgeBase#retrieve` 签名不变，因此问答与分支全都不用动（装了 pgvector 就把 `REAL[]` 换 `vector(n)`）。
3. **RAGFlow Chat 而不是 retrieval**：原工程走的是 Chat（多轮 refine + 绑定 prompt 模板），
   本工程走 retrieval API。要复刻那层，新增 `RagflowChatKnowledgeBase` 实现同一个接口即可。
4. **换 MinIO**：实现 `ObjectStorage` 四个方法；`healthy()` 必须廉价探活，读失败必须抛异常。
5. **换消息队列**：保留 outbox 表，只把投递换成 MQ 生产，消费侧复用 `ClassicIngestHandler`。
6. **补业务域**：照 `classics` 的角色分层补齐 `controller/`、`contract/`、`model/`、`mapper/`、
   `service/`、`worker/` 六个目录（域名换成新域即可），
   记得建表幂等、注册 outbox 处理器、在 `/health/dependencies` 登记新依赖。
7. **JDK 21**：把 `java.version` 改 21，`InfraConfig` 里两个执行器换成虚拟线程执行器。

改之前先读扩展指南第 9 节「红线」——**67 条**，比具体代码更重要。最容易忘的几条：
降级要留痕、无依据不作答、偏移补不上就留 null、配置缺失要启动失败、新依赖必须能在健康检查里看见、
事件不许打断业务、`eventSeq`/`version` 不许被 `updateById` 回写、终态控制必须幂等、
子进程不许拿到身份（只给凭证）、记账失败不许阻塞业务、OCR 探活不许只认 2xx（hubserving 的 405 才是在线）、
会话吊销与失败计数不许只存内存、库不可用时降级要留痕、一条语句里不许对同一行改两次。

---

## 11. 已知限制

- **没有用户表与角色表**：账号来自配置白名单，`/me` 的 `roles` 由 `ADMIN` / `TRACE_READER` / `USER`
  三份配置清单**每次请求现算**，不是固定返回值。因此也不存在"改角色"的接口——
  改配置即改角色，没有授权管理层。
- **会话吊销名单与登录失败计数已落库**（原来在进程内存，已改）：重启不丢、多实例共享，
  已用真实进程验证——登出后重启，旧 Cookie 仍 `401`；失败计数锁定后重启，仍 `429`。
  `auth.session-store=memory` 可切回纯内存，但那只适合单实例调试：内存模式下"重启即恢复登录"
  和"A 登出、B 放行"都成立。库不可用时自动降级到本地名单并留痕
  （`/health/dependencies` 显示 `store=jdbc:DEGRADED`），此时跨节点仍会漏判。
- 调度器为单机 `@Scheduled`，多实例靠 `SKIP LOCKED` 保证不重复执行，但没有做任务均衡。
- 指标只做单点越界判定，没有趋势、复合指标与参考区间分人群。
- **OCR 精度受引擎限制，选引擎要紧**：同一张 `samples/scan-page.png`，Tesseract 有 4 处实质错误
  （「四君子汤」→「四君子注」、「白术」→「和白术」、「食少便溏」→「食少便沪」、「舌淡苔白」→「舌淡苦白」），
  其中两处是中医术语错；PaddleOCR（PP-OCRv3）6 行里 5 行逐字正确，第 6 行漏了「溏」——
  **它也不是零错**。抽取侧已加 `isProbableTitle` 兜底
  （2–10 个纯汉字、无标点、仅在无当前方剂时启用），但字符级错误无法在抽取层完全消除。
  **处方/病历类扫描件建议用 `provider=paddle`**，别用 Tesseract 省钱。
- **Paddle 已真联调，但联的是开源自建而非百度云商用接口**：已跑通 `generic`（仓库内 Tesseract）
  与 `paddle`（仓库内 PaddleOCR 服务）两条。百度智能云 `accurate_basic` 那套
  （`access_token` + `words_result`）**没有**验证过——协议不同，接它要另写一个 provider。
  Paddle 侧还有两个已知硬限制：PP-OCRv4 检测模型在 paddle 2.6.2 上 SIGILL（只能用 v3）；
  镜像 `Dockerfile.paddle` 全量版 8.38 GB，slim 版才适合落地。
- `tools/ragflow-stub` 不是 RAGFlow，只实现用到的 5 个接口，检索用的是词面打分而非真向量，
  因此它的相似度分数不可用于评估召回质量。
- **stub 的数据集存在内存里，容器一重启就空了**：重启后 `hybrid` 会自动回落本地切片，
  问答不中断，但要重新上传文档才会重建 RAGFlow 索引——它是契约验证工具，不是持久化知识库。
- `hybrid` / `dual` 只保证「外部失败不影响主链路」，**不保证两侧数据强一致**：
  外部写失败只记 `note`，没有对账与补偿任务。
- **向量检索没有 ANN 索引**：全量取本文档的切片向量在 Java 侧算余弦，候选上限
  `candidate-limit=200`。万级切片要换 pgvector（`vector(1024)` + HNSW）或独立向量库，
  替换点在 `ChunkVectorService` 的两句 SQL，接口不动。
- **没有 cross-encoder 重排**：本地做的是「向量分 + 词面覆盖率」的加权融合，不是
  reranker 对 (query, passage) 逐对打分。本机 Ollama 0.40 没有 `/api/rerank`，
  `bge-reranker-v2-m3` 只有 `completion` 能力、拿不到分数；要真重排得起 llama.cpp server
  或走 RAGFlow 的 `rerank_id`。
- **融合权重是按小样本调的**：`vector-weight=0.6`、`vector-min-score=0.45` 是在
  bge-m3 + 中医典籍切片上试出来的，换模型或换语料要重调；调的依据是"同义查询召回
  与无关查询误召"这两组对照，不是看分数高不高。
- **首查有补建延迟**：历史文档第一次被检索时要现场算向量（实测 6 片 1.9s，含模型首次加载）。
  之后切片向量直接从 `chunk_vector` 读，但**问题仍要每次向量化一次**（约几十毫秒，模型常驻时）。
  不想有这一下就在上线后主动重跑一次索引流水线。
- **并行问答的汇总是确定性拼装，不是模型综合**：三路答案按固定顺序加标题拼接，
  没有"交叉印证/冲突消解"这一步。原工程 `summary` 节点是模型调用，本工程刻意没做——
  规则拼装至少不会在汇总环节引入新的事实错误。
- **持久化 run 的续跑是"整轮重放"，不是跨进程接管在途节点**：节点认领靠 Outbox 的
  `FOR UPDATE SKIP LOCKED`（一次一个实例能领到），但**没有节点级租约与心跳**——
  实例在节点执行中途被 kill，节点会停在 `RUNNING`，靠 `attempt_count` 与 `auto_attempt_limit`
  兜底重试，不会自动把在途节点移交给别人。续跑用的是**提交时冻结的上下文**，
  这保证依据不变，但也意味着续跑不会重新检索——文档在这期间新增了内容也看不到。
- **重试会连带重跑 `summary`**：因为汇总读的是各分支的落库结果。这是刻意的
  （分支换了新结果而汇总不重算，最终答案就还是上一轮的），但代价是重试不再便宜。
- **run 的 owner 隔离同样靠"每个查询显式带 owner"**，与患者域同一条规则，漏写一处就是一次越权读。
- `modern` 分支只在传了 `patientId` 且该患者有指标时才启用；没有患者上下文时该路 `SKIPPED`，
  而不是用模型常识补一段"现代医学怎么看"——这是有意为之，不是缺失。
- **观测默认走异步单写线程**：队列满了**丢并计数**（`/health/dependencies` 里能看到 `dropped=N`），
  绝不反压业务。这是有意的取舍——观测失真的代价远小于拖慢业务；代价是极端情况下会丢轨迹。
- **观测数据不在业务事务里**：业务回滚时最需要看失败轨迹，若同事务写入，回滚会把轨迹一起抹掉。
  因此轨迹的可见性有毫秒级延迟（`TraceQueryService` 每个入口先 `flush()` 缓解）。
- **跨进程会断链**：`parent` 关系靠线程内 `ThreadLocal` 搬运，没有走 W3C `traceparent` 头。
  本工程是单进程切片，够用；一旦拆服务，父子关系需要换成从 HTTP 头解析。
- **观测状态词表收口在 `finishTrace`**：业务侧按自己的话传（`OK` / `INSUFFICIENT_EVIDENCE`），
  落库前统一折算成四值健康度，原生词进 `outcome` 列。新增业务域别绕开这一步自己写 `status`。
- **保留清理按写入时算好的 `expires_at`**：把 `retention-days` 调小不会立刻删老数据，
  新老数据各按当初承诺的期限走；要立即清就显式 `POST /maintenance/sweep?dryRun=false`。
- **孤儿判定是推断，不是事实**：扫描靠"对象存储的 key 在 DB 里找不到引用"，只读、
  不给"删全部孤儿"的接口——清理必须显式点名 key，越界（含 `../`）与仍被引用的 key 会被跳过并回报。
- **批次导入逐行独立事务**：坏行不连坐，但也意味着**部分成功是常态**（`status=PARTIAL`），
  调用方必须读 `ok`/`fail` 而不是只看 HTTP 200。CSV 解析器（`CsvLite`）不处理字段内的裸换行。
- **删除是四段式而非一次调用**：预览 → 确认（令牌 + 版本双校验）→ 异步擦除 → 台账归档。
  一次调用删掉全部资源看似省事，但没人能证明"删的确实是当时看过的那份清单"。
- **owner 隔离靠"每个查询显式带 owner"，没有全局拦截器**：
  漏写一处条件就是一次越权读。Java 侧不像 SQLAlchemy 那样能在 Session 层统一注入，
  所以规则是"新增患者域查询必须带 owner 条件"，并由 `OwnerIsolationTest` 断言真实 WHERE 子句。
  新加患者域表时记得同步加 `owner` 列 + 索引 + 回填，否则新表对所有用户可见。
- **owner 回填是全表 UPDATE，每次启动都跑**：行数不大时无感；到百万级应改成按 id 游标增量补。
- **典籍文档是共享资产，不按 owner 过滤**：中医典籍库对所有登录用户可见；
  但它的**处理轨迹**按上传者隔离——文档共享、操作日志不共享，这两件事刻意不同。
- **越权一律 404 不 403**：403 会泄露"这个 ID 确实存在"。例外是"显式查他人轨迹列表"，
  它返回 `403 TRACE_SCOPE_FORBIDDEN`——这是"你不许这么查"，不是"东西不存在"。
- **模型路由没有生效灰度**：保存即全量切换，没有"新配置先跑 10% 流量"这回事。
  能力探测也只是**一次性**检查（`model_route_probes` 留痕），不持续监控——
  供应商侧挂了要等下一次探测才发现。成本与配额统计同样没做。
- **模型路由的密钥只有一份**：没有多密钥轮换（一次只能有一把 `model_credentials` 生效），
  也没有按主线的配额上限。`master-key` 轮换后旧密文直接不可解——这是刻意的 fail-fast，
  但意味着轮换前必须先把所有主线重新配一遍密钥。
- **多智能体的角色执行器不调模型**：默认实现是确定性模板执行器，只组织编排层读好的事实。
  因此现在能验证的是"编排骨架守规矩"（依赖、跳过、来源校验、交接完整性），
  **不是"模型答得好"**。接入真实模型前，答案质量无从谈起。
- **多智能体的 6 个只读工具是固定清单**：`AgentLabService` 提供了目录、探活与 `tools/call` 回执，
  但**没有"工具自己注册"的机制**。要加工具得改 `AgentTool` 枚举与 `boundTools`（这两处会动契约），
  并在 `CATALOG_VERSION` 上留痕——目录版本号变了，调用方手里的缓存必须失效。
- **Agent Lab 是进程内协议校验，不是真的 MCP Server**：`initialize → tools/list → tools/call`
  三步在同一个 JVM 里按 STDIO 语义走了一遍，校验"发现集等于授权集"，但没有子进程、没有对外 URL、
  没有真实的 JSON-RPC 帧编解码。因此它证明的是"越权工具会被拒"，不是"能被外部 MCP 客户端接入"。
- **Agent Run 事件不暴露 `detail`**：`agent_run_event.detail` 只落库不出接口，
  事件流对外只有 `type` / `seq` / `nodeId` / `status`。原因是 detail 里可能带模型输入的片段；
  要看细节走 `/nodes/{nodeId}`（有脱敏与审计），两条路刻意分开。
- **Agent Run 没有节点级租约与心跳**：`cancel` 是"置标记 + 把 RUNNING 节点改 CANCELLED"，
  不是抢占式中断；执行线程仍会把当前节点跑完，只是下一个节点前会看到 `CANCELLED` 而停下。
  `deadline_at` 超时同理——判定发生在节点边界，卡在单次模型调用里的 run 要等这次调用返回。
- **`retry` 只对 `FAILED` 生效**：`SUCCEEDED` 与 `CANCELLED` 不可逆（再跑就不是"重试"而是"重开"）。
  `QUEUED` / `RUNNING` 的 retry 返回当前视图而不新建——这是"响应丢失对账"的语义，不是遗漏。
- **断点续跑只复用 `kind='role' AND status='succeeded'`**：校验节点、工具节点一律重跑。
  复用它们等于跳过校验——省下的那点时间是拿"来源可能已变"换的。
- **协同的"整轮失败"粒度偏粗**：必需角色任一失败，整轮就是 `FAILED`，
  已完成角色的来源虽然保留在节点里，但不会作为部分结果对外交付。
- **QA 诊断是单路检索，不是完整问答**：`ragflow_diagnostic` 只跑检索一路，
  产出 `retrievalStatus`（hit/no_hit/unknown）与 `resultKind`（retrieval_only / no_evidence /
  model_unknown_retrieval），不调模型、不生成答案。运行层 `status` 恒为 `SUCCEEDED`
  （除诊断自身崩），结论写在 `result.executionStatus` 里（`NO_EVIDENCE` / `DEGRADED`）——
  "确实没检到"是有效结论，抛给 Outbox 只会无限重试一个永远不会变的结果。
- **患者资料草稿没有"提交"端点**：`patient_review_draft` 只做服务端暂存
  （`basic` / `clinical` 两个 scope，`base_revision` + `version` 双锁，请求侧版本号叫 `expectedVersion`），
  草稿**永不改动**正式修订与确认状态。真正的落库仍走 `/exams/{examId}/confirm` 那条人工确认链路；
  这样做是为了让"暂存"和"确认"在权限与审计上不混为一谈。
- **`stdio` 形态一次操作拉一个进程**：握手完就退出，`handshakeMs ≈ 136ms`（其中 135ms 是 node 启动）。
  常驻进程会更快，但"身份必须由父进程按登录态锁定"这条更重要——常驻就得把身份塞进请求。
  它只用于诊断控制台，不是业务热路径。
- **内部工具端点的信任边界在子进程**：令牌 + 回环 + 短命凭证挡住了"别人打进来"和"子进程指定身份"，
  但挡不住"拿到令牌的人改子进程"。所以那个脚本是 200 行零依赖、不碰数据库、不产生任何出站连接；
  它一旦被改坏，影响面就是"按当前登录态多读几次自己本来就能读的数据"。
- **成本只算 chat 调用**：embedding（`bge-m3` 本地）不计费也不计量；接云端 embedding 时要另加一条主线口径，
  否则总成本会漏掉向量化的那部分。
- **配额是"日累计"粒度**：没有小时级窗口、没有并发预扣。并发打满时可能同时放过 N 个请求，
  超出的部分要等下一轮闸门才拦住——这是"先记账后校验"的固有偏差，不是 bug，
  但要把配额定得比真实预算低一点点。
- **金额口径未与上游账单对账**：`cost_micros` 用的是配置里的价目表（已按官方三档价逐档填写）。
  2026-11-05 用真实 DeepSeek 密钥验证过：`usage` 原样落库、`cost = (hit×命中价 + miss×未命中价 +
  output×输出价) × 峰时倍数` 可用行内数字手算复现（512/39/2281 → 9107 微元）、两次连打命中缓存
  92.9%，与官方口径一致。**但没做「当月账单 vs 本系统累计」的核对**——那需要账单接口，
  不在本期范围；且峰谷只按 UTC 工作日建模，中国法定节假日会按峰价高估。
- **记忆摘录不并入正式记忆版本**：`memory_agent_note` 的候选是 `PENDING`，
  `observations` 里每条摘录都要逐字出现在来源正文中（`content.contains(quote)`）——
  这只保证"摘的是原文"，不保证"摘得对"。
  发布是显式的（`POST .../publish`，校验 `candidateHash` / `sourceHash`），
  且凭据落在 note 行内——不污染"记忆版本只认已发布修订"这条冻结来源红线。

---

## 12. Web 控制台（前端）

内置静态前端，让已落地的后端接口变成可演示的 Web UI。**与后端同源部署**，无需单独的构建/打包/部署步骤——
`src/main/resources/static` 下的文件由 Maven 直接打进 jar，运行时由 Spring 的静态资源处理在根路径 `/` 提供。

### 12.1 怎么打开

```bash
# 起好 PostgreSQL 并启动应用后，浏览器直接访问：
open http://127.0.0.1:18100/        # 或手动打开
```

登录页默认预填 `admin / admin123`；登录后左侧导航进入各模块。登出会吊销令牌（不只是清 Cookie）。

### 12.2 文件结构

```
src/main/resources/static/
├── index.html                 # 外壳：登录遮罩 + 侧边栏 + 内容区 + toast/modal 宿主
└── assets/
    ├── app.css                # 医疗风主题（无外部字体/CDN，系统字体栈含 CJK）
    ├── api.js                 # 统一 fetch 封装：Cookie 鉴权、401 跳登录、ApiError 解析、文件下载
    ├── ui.js                  # 基础组件：toast / modal / table / 表单 / 状态标签 / 时间格式化
    ├── router.js              # 极简 hash 路由（路由都在 # 片段，服务端只认 '/'，天然 SPA 友好）
    ├── app.js                 # 引导：登录态、导航、头部动作、路由启动
    └── pages/
        ├── dashboard.js       # 系统概览：/health/dependencies 姿态
        ├── patients.js        # 患者档案：列表/建档/详情/状态流转/指标/附件
        ├── intake.js          # 资料录入：上传/两道核查/确认建档
        ├── care.js            # 健康管理：报告·计划/分节编辑/签发/确认/打卡/导出/修订·审计
        ├── classics.js        # 典籍处理：上传/流水线节点/切片·证据·产物下载/重试
        ├── qa.js              # 智能问答：同步问答/异步运行(轮询+SSE)/知识库绑定
        └── memory.js          # 患者记忆：初始化/版本链/随访/刷新/异步任务
```

### 12.3 设计要点

- **鉴权**：登录成功后后端下发 HttpOnly Cookie（`healthos_java_session`），前端所有请求带 `credentials:'same-origin'`，自动复用；
  `api.js` 统一拦截 `401` 并回调 `app.js` 弹回登录页。
- **错误体**：后端 `GlobalExceptionHandler` 统一返回 `{code,message,...}`，前端 `UI.toastErr` 直接展示 `message`。
- **乐观并发**：患者编辑、照护分节编辑、状态流转、随访/刷新、记忆任务等都按后端契约带 `expectedRevision` / `expectedMemoryId` / `operationId`，冲突时后端返回 409，前端如实提示。
- **导出/下载**：照护 JSON/PDF、记忆 Markdown、典籍产物都走 `Api.download()`（blob + `Content-Disposition` 文件名）。
- **异步任务**：记忆发布、问答运行等走 Outbox/后台 worker，前端提交拿到 `202 + jobId/runId` 后轮询或用 SSE（`EventSource`）订阅进度。
- **零构建零依赖**：纯原生 HTML/CSS/JS（classic script，非 ES module，避免任何加载约束），不引用任何外部 CDN，断网也能用。

### 12.4 改造指引

改页面只动 `assets/pages/*.js`，共享能力在 `api.js` / `ui.js`；加导航项改 `app.js` 的 `NAV` 与 `index.html` 的脚本引入；
新增路由在对应 page 文件里 `HOS.router.register('/your/path/:id', renderFn)`。改完 `mvn clean package` 即重新打包，无需前端工具链。
