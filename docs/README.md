# 文档索引

本目录按**用途**分类，不按写作时间排列。

| 分类 | 文件 | 内容 |
| --- | --- | --- |
| `architecture/` | [`ARCHITECTURE.md`](architecture/ARCHITECTURE.md) | 系统分层、领域划分、关键时序、认证与授权链路；含本地向量检索（融合排序与降级）、Agent Run 事件流与控制面、**Agent Lab（进程内 / 真 STDIO 子进程两种传输层）**、**模型成本与配额**、QA 书签与诊断、资料草稿与记忆摘录（对应 [`system-architecture.html`](architecture/diagrams/system-architecture.html) 的文字版） |
| `architecture/diagrams/` | [`system-architecture.html`](architecture/diagrams/system-architecture.html) | 系统组成图：使用者 → HTTP API → Outbox → 流水线 / 录入编排；存储与外部依赖落点 |
| `architecture/diagrams/` | [`pipeline-workflow.html`](architecture/diagrams/pipeline-workflow.html) | 流水线内部：内容分流、分段与检查点、重试与二分回退 |
| `extending/` | [`EXTENDING.md`](extending/EXTENDING.md) | 扩展点手册：新增模型提供者 / **向量模型与 pgvector** / **MCP 传输层与 Server 实现** / **单价与配额口径** / OCR 引擎 / 知识库 / 图存储 / 问答分支 / 流水线节点 / 事件类型与控制命令 / 只读工具 / 草稿 scope 与诊断模式 |

主入口是项目根目录的 [`README.md`](../README.md)——先读它，再按需跳到这里。

## 图表怎么看

两个 HTML 都是**自包含单文件**（样式与脚本内联，字体走系统栈），双击即可在浏览器打开，
不依赖任何服务。想改图改 `.archify/` 下的 JSON 再重跑即可，但 `.archify/` 是中间产物、
不进版本库；仓库里保存的这两份 HTML 才是交付物。

## 与代码的对应

文档里出现的包名一律以 `src/main/java/com/kangheng/healthos/` 为根。
工程按**技术角色**分包，角色内再按**业务域**分包：

```
src/main/java/com/kangheng/healthos/
├── controller/    HTTP 入口
├── service/       业务编排
├── mapper/        MyBatis-Plus Mapper
├── model/         持久化实体与值对象
├── contract/      请求 / 响应契约（record）
├── spi/           扩展点接口（可插拔点）
├── provider/      spi 的具体实现
├── worker/        异步任务处理器（Outbox 消费端）
├── text/          纯文本算法：切片、引用定位（无状态、无依赖）
├── common/        公共内核：异常 / JSON / ID / 分页 / 访问控制 / CSV
└── config/        配置装配
```

角色目录下的第二级就是业务域名（`classics` / `patient` / `qa` / `tracing` / `care` / `ocr` / `graph` / `outbox` / `retrieval` / `storage` / `ai`），
例如 `controller/qa/QaController`、`service/patient/PatientService`、`spi/graph/GraphStore`。

不是每个域都用满全部角色，按需取用；但**接口归 spi、实现归 provider**
这条规则全项目一致，依赖方向由 `tools/check-layering.py` 机械校验（无例外）。
