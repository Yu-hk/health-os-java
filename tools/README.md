# 配套工具

三个**真能跑**的桩服务 / 真服务，用来在本地把外部依赖那条链路跑通，不需要申请任何账号或 API Key。

| 目录 | 是什么 | 端口 | 怎么起 |
| --- | --- | --- | --- |
| `ocr-service/` | OCR 服务，两个变体同占 8868：**Tesseract**（Flask + Tesseract 5.3.4 + `chi_sim`，`POST /ocr`）或 **PaddleOCR**（`POST /predict/ocr_system`）。都接收**图片**、返回带坐标的文本行 | 8868 | `docker compose --profile ocr up -d` / `--profile ocr-paddle` |
| `ragflow-stub/` | RAGFlow 契约桩：只实现本工程用到的 5 个接口，含 1.2s 异步解析延迟 | 9380 | `docker compose --profile ragflow up -d` |
| `rerank-service/` | **cross-encoder 重排服务**（Flask + `bge-reranker-v2-m3`）。`--stub` 是纯标准库打分（无需 torch/模型，仅验证契约与对照）；默认真实模式从 `hf-mirror.com` 拉模型，装不上时 `/health` 返回 503、应用侧自动降级 | 8899 | `python tools/rerank-service/server.py --stub`（无模型）/ 去掉 `--stub` 走真实模型 |
| `mcp-server/` | **真的** MCP Server（零依赖 Node，stdio 上的 JSON-RPC 2.0）。`--healthos.agent-lab.transport=stdio` 时由 Java 用 `ProcessBuilder` 拉起 | —（stdin/stdout） | 不用手动起，见 `docs/extending/EXTENDING.md` §8.103 |
| `smoke/llm_stub.py` | OpenAI 兼容桩：只回固定答案 + 固定 `usage`，用来在没有有效上游密钥时验证"用量记账 → 成本 → 配额闸门"这条链路 | 18999 | `python tools/smoke/llm_stub.py 18999 880 42` |

三个**校验脚本**：`check-layering.py` 只依赖本机代码；三个 `smoke/` 脚本对着跑着的应用打真实请求。

| 脚本 | 干什么 | 怎么跑 |
| --- | --- | --- |
| `check-layering.py` | 机械校验分层依赖方向（`禁止方向 0 违规` 才算过） | `python tools/check-layering.py` |
| `smoke/verify_all.py` | 真实链路 45 项断言：协同事件流 / 控制面 / Agent Lab / 书签 / 诊断 / 草稿 / 摘录 | `python tools/smoke/verify_all.py`（需应用已起，`HEALTHOS_BASE` 可覆盖地址） |
| `smoke/verify_ocr.py` | OCR 23 项断言：服务契约（405 即在线）/ 应用侧探活 / 扫描件入库后 chunk 与识别结果**逐字对账** | `HEALTHOS_OCR_PROVIDER=paddle python tools/smoke/verify_ocr.py`（需应用与 OCR 服务都已起） |
| `smoke/verify_auth_store.py` | 认证状态「重启不丢」：分四段跑（`before-restart` / `after-restart` / `throttle-before` / `throttle-after`），**中间要手动重启应用**——只在同一进程里测等于什么都没验 | `python tools/smoke/verify_auth_store.py before-restart`，重启后再跑 `after-restart` |
| `smoke/verify_model_usage.py` | 用量记账 / 三档单价 / 峰谷倍数 / 双闸门的 13 项断言。**打真实上游**（配了有效密钥时），同一问题连打两次用来确认第二次 `cachedPromptTokens > 0` | `HEALTHOS_QUESTION=... python tools/smoke/verify_model_usage.py`（`HEALTHOS_ROUNDS` 默认 2，`HEALTHOS_DOCUMENT` 指定问答绑定的文档） |
| `smoke/verify_pgvector.py` | pgvector ANN **真实验证**：在跑着 pgvector 的库内生成 N 行随机向量，证明生产 `<=>` 检索与暴力余弦一致、HNSW 索引被选中（Index Scan）、加速比。默认 N=200000 | `python tools/smoke/verify_pgvector.py [N]`（需 `healthos-java-pg` 容器在跑） |
| `smoke/verify_ann_realsearch.py` | 真实 **bge-m3 嵌入** + 生产 `searchTopK` 余弦 SQL 的端到端排序验证：注入 2 行真实嵌入（自身 + 近邻），确认自身排第1（score≈1.0）、近邻排第2，用完即删，不污染业务数据 | `python tools/smoke/verify_ann_realsearch.py`（需 ollama:bge-m3 与 `healthos-java-pg` 在跑） |
| `smoke/verify_rerank.py` | cross-encoder 重排**契约**验证：本地起 stub 重排服务，确认 `/health` 200、`/rerank` 按分数降序且 index 正确映射；并演示"融合序排错的强相关段被重排顶到第 1"的对照 | `python tools/smoke/verify_rerank.py`（纯标准库，无需模型） |

两个桩都在 `docker-compose.yml` 里以 profile 形式编排，**默认不启动**——
应用侧对应的开关（`healthos.ocr.provider` / `healthos.retrieval.provider`）
不打开时，这些进程一个都不会被拉起来。

## smoke 脚本的夹具

`verify_all.py` 用的是**一次真实上传/问答留下的数据**（患者、录入单、知识库绑定、会话消息），
ID 写在脚本顶部；换库或清库后要么重跑一次真实链路生成，要么用环境变量覆盖：

```bash
HEALTHOS_BASE=http://127.0.0.1:18100 \
HEALTHOS_PAT=pat_xxx HEALTHOS_EXAM=pie_xxx \
HEALTHOS_BINDING=qkb_xxx HEALTHOS_MESSAGE=qms_xxx \
python tools/smoke/verify_all.py
```

选夹具时注意：**书签那条消息必须属于一个有 `run` 的会话**，
否则拿不到 `patientId`，收藏会返回 `400 MEMO_SOURCE_MISSING`（这是刻意的，不是脚本 bug）。

## OCR 服务的两个变体

`ocr-service/` 里是**两套实现**，二选一，都监听 8868，靠 `healthos.ocr.provider` 决定应用侧调谁：

| | Tesseract | PaddleOCR |
| --- | --- | --- |
| 入口 | `server.py` | `paddle_server.py` |
| 协议 | `POST /ocr`（multipart，字段名 `file`） | `POST /predict/ocr_system`（JSON，`{"images":[base64]}`） |
| 应用侧 provider | `generic` | `paddle` |
| 镜像 | `Dockerfile` → `healthos-ocr:local`，**358 MB** | `Dockerfile.paddle-slim` → `healthos-ocr-paddle:slim`，**2.97 GB**；`Dockerfile.paddle` 全量版 8.38 GB |
| 中文识别 | 有 `chi_sim`，但中医术语易错（实测「苔」→「苦」、「溏」→「沪」，6 行里错 4 处） | PP-OCRv3，6 行里 5 行逐字正确，第 6 行漏「溏」 |
| 热态耗时 / 常驻内存 | ~90 MB | 400–550 ms/页 / 484 MB（slim）、662 MB（全量） |

**默认用 Tesseract**：体积小一个量级，够验证链路。要论识别质量（尤其中医/药品名的生僻字）才换 Paddle。

```bash
# Tesseract
docker compose --profile ocr up -d --build ocr
# PaddleOCR（与上面二选一，同占 8868）
docker build -f tools/ocr-service/Dockerfile.paddle-slim -t healthos-ocr-paddle:slim tools/ocr-service
docker compose --profile ocr-paddle up -d ocr-paddle
```

`paddle_stub.py` 是**纯标准库**的契约桩（不需要 paddle、不需要镜像），刻意只实现
`POST /predict/ocr_system` 而**不实现 `/health`**——PaddleOCR 的 hubserving 本来就没有
`/health` 路由。它的用途是证明应用侧探活没写错：对 hubserving 形状的服务，
`GET /predict/ocr_system` 返回 **405** 才是「服务在线」信号，把它当失败会误报 `ocr: unavailable`。

```bash
python tools/ocr-service/paddle_stub.py 8868          # 然后用 --healthos.ocr.provider=paddle 启动
```

### Paddle 镜像的三条已知坑（都已在 Dockerfile 里钉死）

1. **`numpy 2.x` × `opencv 4.6`** —— pip 装 `paddleocr` 会把 numpy 升到 2.x 并塞进
   `opencv-python-headless 5.x`，`import cv2` 直接崩（`numpy.core.multiarray failed to import`）。
   必须先卸 headless，再 `--force-reinstall --no-deps` 装回 `numpy==1.26.4` + `opencv-python==4.6.0.66`。
2. **`paddleocr==2.7.5` 官方 bug** —— `paddleocr.py` 第 575 行 `class PaddleOCR(predict_system.TextSystem)`
   用了 `predict_system` 却没有 import，导入即 `NameError`。Dockerfile 里补一行 import 并断言补丁位点。
3. **PP-OCRv4 检测模型在 paddle 2.6.2 上 SIGILL** —— 崩在 `SelfAttentionFusePass`。
   `enable_mkldnn=False`、`FLAGS_use_mkldnn=0`、`FLAGS_enable_pir_api=0`、`taskset` 绑 P-core
   **全部无效**；换 `PP-OCRv3` 正常。故 `paddle_server.py` 默认 `PP-OCRv3`，可用 `OCR_MODEL_VERSION` 覆盖。

## 边界要说清楚

- `llm_stub.py` **不是模型**。它不做任何推理，只把响应形状（`choices[0].message.content` +
  `usage{prompt_tokens,completion_tokens,total_tokens}`）摆对。用它验证的是**计量与闸门链路**，
  不是"模型答得好不好"；金额是否与上游账单一致仍取决于价目表配置。
- `mcp-server/patient-business.js` **不是业务服务**。它不碰数据库、不产生出站连接，
  `tools/call` 只把凭证转回本进程的内部只读端点，数据由 Java 侧按登录态读出。
- `ragflow-stub` **不是 RAGFlow**。它只对齐用到的接口契约，检索走的是词面打分，
  不是真向量检索，也不支持多路召回融合。
- `ocr-service` 是真 OCR（Tesseract 变体真调 Tesseract，Paddle 变体真跑 PP-OCRv3 模型），
  但**只吃图片**。PDF 的逐页渲染由应用侧
  的 `ocr/pdf/PdfPageRenderer` 完成——应用把每一页渲染成 PNG 再逐页送过来，
  这样 OCR 服务不需要装 PDF 依赖，也不必重复实现一遍渲染。
- `paddle_stub.py` **不是 PaddleOCR**。它只回固定文本，用来验证协议形状与探活语义，
  不验证识别质量；要验质量请起 `paddle_server.py`。
- 有文本层的 PDF 应用会直接走文本层抽取，根本不经过 OCR。

## 单独起（不进 compose）

```bash
# OCR（Tesseract）：需要镜像里有 tesseract 与中文包
docker build -t healthos-ocr tools/ocr-service -f tools/ocr-service/Dockerfile
docker run --rm -p 8868:8868 healthos-ocr

# PaddleOCR（与上面二选一，同占 8868）：slim 2.97GB，全量 Dockerfile.paddle 是 8.38GB
docker build -f tools/ocr-service/Dockerfile.paddle-slim -t healthos-ocr-paddle:slim tools/ocr-service
docker run --rm -p 8868:8868 healthos-ocr-paddle:slim

# RAGFlow 桩：纯 Python，无需额外依赖
python tools/ragflow-stub/server.py
```

应用侧对应的环境变量见根目录 README 第 2 节。
