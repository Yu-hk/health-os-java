package com.kangheng.healthos.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.kangheng.healthos.model.patient.Patient;

/**
 * 全量运行配置。对应原项目 {@code healthos_v2/config.py} 的 Settings。
 *
 * <p>刻意保留原项目的“前缀式分组 + 可被环境变量覆盖”风格，
 * 但把原项目里带 {@code change_me} 默认值的敏感项改为<b>空值 + 启动校验</b>，
 * 避免漏配时静默使用弱口令。
 */
@Data
@ConfigurationProperties(prefix = "healthos")
public class HealthOsProperties {

    /** local / staging / prod，非 local 时启动校验会拒绝占位密钥。 */
    private String environment = "local";

    private Auth auth = new Auth();
    private Storage storage = new Storage();
    private Ai ai = new Ai();
    private Extraction extraction = new Extraction();
    private Pipeline pipeline = new Pipeline();
    private Retrieval retrieval = new Retrieval();
    private Embedding embedding = new Embedding();
    private Ocr ocr = new Ocr();
    private Graph graph = new Graph();
    private Qa qa = new Qa();
    private Tracing tracing = new Tracing();
    private Patient patient = new Patient();
    private Security security = new Security();
    private Care care = new Care();
    private ModelRouting modelRouting = new ModelRouting();
    private AgentLab agentLab = new AgentLab();
    private ModelUsage modelUsage = new ModelUsage();

    /**
     * 访问控制白名单。
     *
     * <p>患者域的数据一律按 owner 隔离，管理员也不例外——"能看别人档案"这件事
     * 不该由一个默认配置悄悄打开。这里只列两类明确的例外：
     * <ul>
     *   <li>{@code adminUsers}：运维类接口（孤儿扫描/清理）专用，只读元数据不读患者内容；</li>
     *   <li>{@code traceGlobalUsers}：轨迹全局读。轨迹里含问答原文与患者 ID，
     *       默认谁都能读会变成信息泄露面。</li>
     * </ul>
     * 对应原项目 {@code model_route_security.permissions()} 的三个白名单，
     * 但去掉了"模型配置管理"那一项——本工程还没有模型配置中心。
     */
    @Data
    public static class Security {
        /** 运维接口白名单（孤儿扫描与清理）。 */
        private List<String> adminUsers = new ArrayList<>(List.of("admin"));
        /** 轨迹全局读白名单。 */
        private List<String> traceGlobalUsers = new ArrayList<>(List.of("admin"));
        /**
         * 模型配置中心管理白名单。
         *
         * <p>与运维白名单<b>刻意分开</b>：运维权限是"清理孤儿文件"这类跨用户维护，
         * 模型配置是"改全系统调哪个模型"——后者一次改错影响所有业务主线。
         */
        private List<String> modelAdminUsers = new ArrayList<>(List.of("admin"));
    }

    @Data
    public static class Auth {
        private String username = "admin";
        private String password = "";
        private String sessionSecret = "";
        private Duration sessionTtl = Duration.ofHours(8);
        private String cookieName = "healthos_java_session";
        /**
         * 多账号列表。
         *
         * <p>配了它就以列表为准，没配仍走 {@code username}/{@code password} 单账号。
         * 之所以要它：患者域的数据按归属人隔离，而"隔离"只有一个用户时验证不出来——
         * 第二个账号一出现，漏掉 owner 条件就立刻变成越权读。
         */
        private List<User> users = new ArrayList<>();
        /**
         * 登录失败锁定阈值（同一账号连续失败次数）。超过则锁定 {@link #lockDuration}。
         *
         * <p>取 5 次是折中：正常人记错口令两三次就到头；而 5 次 / 15 分钟把爆库速度
         * 压到每分钟 0.33 次，暴力枚举在口令长度够的前提下不可行。
         */
        private int maxLoginAttempts = 5;
        /** 锁定时长；同时用作滑动窗口的一半（窗口 = 锁定 × 2）。 */
        private Duration lockDuration = Duration.ofMinutes(15);
        /**
         * Cookie 是否加 {@code Secure} 标记。
         *
         * <p>本地 http 访问必须为 false，否则浏览器直接丢掉 Cookie，表现为"登录成功但立刻未登录"。
         * 走 HTTPS 的部署必须置 true，否则令牌会在明文信道上传输。
         */
        private boolean cookieSecure = false;
        /**
         * 会话吊销名单与登录失败计数的存储位置：{@code jdbc}（默认）/ {@code memory}。
         *
         * <p>默认 {@code jdbc}。内存模式看着省事，代价是两条安全缺口：
         * 重启后吊销名单清空（登出过的令牌又活了）、多实例各存一份
         * （在 A 登出后拿同一张 Cookie 打 B 照样进得去）。所以它只留给单实例本地调试，
         * 选中时会打 warn。
         */
        private String sessionStore = "jdbc";

        @Data
        public static class User {
            private String username;
            private String password;
        }
    }

    @Data
    public static class Storage {
        /** 目前仅支持 local；MinIO 实现见 README「扩展点」。 */
        private String type = "local";
        private String root = "./.local/objects";
    }

    @Data
    public static class Ai {
        /** template（离线确定性）/ openai（OpenAI 兼容接口）。 */
        private String provider = "template";
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-v4-pro";
        private Duration timeout = Duration.ofSeconds(180);
    }

    /** 对应原项目 extraction_* 系列参数。 */
    @Data
    public static class Extraction {
        private int chunkChars = 1500;
        private int chunkOverlap = 150;
        private int maxEntitiesPerSegment = 25;
        private int maxRelationsPerSegment = 40;
        private int splitMinChars = 400;
        private int maxSplitDepth = 3;
        private int segmentMaxAttempts = 2;
        private Duration retryBase = Duration.ofSeconds(2);
        private Duration retryMax = Duration.ofSeconds(15);
        private double retryJitterRatio = 0.25;
        private double minConfidence = 0.55;
    }

    @Data
    public static class Pipeline {
        private boolean dispatcherEnabled = true;
        /** 调度间隔（毫秒）。用毫秒而不是 Duration，方便直接喂给 @Scheduled。 */
        private long dispatcherIntervalMs = 2000;
        private int dispatcherBatchSize = 5;
        private int outboxMaxAttempts = 3;
    }

    /**
     * 检索参数。切片长度直接决定引用的粒度：
     * 太大则"引用整篇"，太小则切片间语义断裂。中医典籍按 200 字左右一句群切比较合适。
     */
    @Data
    public static class Retrieval {
        /** local（本地切片表）/ ragflow（外部知识库）/ hybrid（写两边，检索优先 RAGFlow 并降级）。 */
        private String provider = "local";
        private int chunkChars = 200;
        private int chunkOverlap = 20;
        /** 参与的候选切片上限。 */
        private int candidateLimit = 200;
        /** 返回并展示给用户的引用条数。 */
        private int topK = 5;
        /**
         * 向量分在融合排序里的权重（0~1）。1 = 只看向量，0 = 只看词面。
         *
         * <p>不做"先按向量召回、再按词面重排"的两段式：本地候选集本来就小（几十到几百条），
         * 两段式只是把一个可解释的加权和拆成两个不可解释的门槛。
         */
        private double vectorWeight = 0.6;
        /**
         * 纯向量召回的准入门槛（词面一个都没命中时的兜底）。低于它的不返回，
         * 避免"任何问题都能从任何文档里挑出 5 条"。
         */
        private double vectorMinScore = 0.45;
        /**
         * 向量索引策略：
         * <ul>
         *   <li>{@code auto}（默认）—— 库里有 pgvector 就用；没有就尝试装（需要权限），
         *       装不上退回 Java 侧余弦，不影响启动；</li>
         *   <li>{@code pgvector} —— 只认已装好的扩展，<b>不</b>尝试 {@code CREATE EXTENSION}；</li>
         *   <li>{@code off} —— 连探测都不做，行为与加索引之前一致。</li>
         * </ul>
         * 三种模式都只是"快慢"的区别：没有索引时检索仍然正确，只是要把整篇的向量取回内存算。
         */
        private String vectorIndex = "auto";
        /**
         * 交叉编码器重排（cross-encoder rerank）开关。
         *
         * <p>{@code false}（默认）—— 不重排，检索走「词面 + 向量」融合序。
         * 设为 {@code true} 时还需 {@code rerankUrl} 指向一个真的 rerank 端点
         * （本机 Ollama 0.40 没有 {@code /api/rerank} 且拿不到 logits，所以必须自建，见
         * {@code tools/rerank-service}）；端点不可用或报错时自动退回融合序，不阻断问答。
         */
        private boolean rerankEnabled = false;
        /** 重排服务地址（{@code /rerank} 与 {@code /health} 两个端点）。 */
        private String rerankUrl = "http://127.0.0.1:8899";
        /**
         * 送给重排器的候选条数（融合序的前 N 条）。cross-encoder 逐对打分比双塔贵，
         * 不必把全部候选都送进去，取融合序头部这批"本来就有戏"的即可。
         */
        private int rerankCandidateLimit = 20;
        /**
         * 重排后保留的条数；{@code <=0} 表示沿用 {@code topK}。重排只重排、可截断，
         * 不会引入候选之外的文本。
         */
        private int rerankTopK = 0;
        private Ragflow ragflow = new Ragflow();
    }

    /**
     * 向量化（embedding）配置。
     *
     * <p>默认接本机 Ollama 的 {@code bge-m3}（1024 维，中英混排、支持长文）。
     * 设为 {@code none} 就退回纯词面检索，其余链路不变。
     */
    @Data
    public static class Embedding {
        /** ollama（本机/自建）/ none（关闭，退回词面检索）。 */
        private String provider = "ollama";
        private String baseUrl = "http://127.0.0.1:11434";
        private String model = "bge-m3";
        /**
         * 期望维度。>0 时会在每次响应上校验——换了模型却忘了改维度，
         * 会得到"能检索但分数全是噪声"的结果，比直接报错难查得多。
         */
        private int dimension = 1024;
        /** 单次请求的文本条数。Ollama 对 batch 有上限，16 是实测稳的值。 */
        private int batchSize = 16;
        private Duration timeout = Duration.ofSeconds(30);
        /** 模型常驻时长，避免每次问答都重新加载 1.2GB 权重。 */
        private String keepAlive = "30m";
    }

    /**
     * Agent Lab 的 MCP 传输层配置。
     *
     * <p>两种形态对应两种现实：{@code in-process} 在同进程里枚举目录并查库（默认，
     * 零依赖、不会因子进程挂掉而拖垮目录接口）；{@code stdio} 用 {@code ProcessBuilder}
     * 拉起一个真的 MCP 服务子进程，走 stdio 上的 JSON-RPC——它证明的是"外部客户端
     * 能按协议接进来"，而不只是"授权逻辑写得对"。
     *
     * <p><b>为什么子进程要回调内部端点</b>：子进程不拿数据库凭据、不解析业务规则，
     * 只做协议适配。数据仍由本进程按登录态锁定 owner 后读出，避免"把数据访问权
     * 连同一个 Node 运行时一起交出去"。
     */
    @Data
    public static class AgentLab {
        /** in-process / stdio。其它值启动失败，不静默降级。 */
        private String transport = "in-process";
        /** node 可执行文件；可以用绝对路径指向某个特定版本。 */
        private String nodeCommand = "node";
        /** MCP server 脚本路径（相对进程工作目录或绝对路径）。 */
        private String serverScript = "tools/mcp-server/patient-business.js";
        /**
         * 内部只读工具端点基址。子进程把 {@code tools/call} 转发到这里。
         * 只监听回环地址——它带着内部令牌，暴露到 0.0.0.0 等于把患者读取接口
         * 挂到内网任何一台机器都能打通的位置。
         */
        private String toolEndpoint = "http://127.0.0.1:18100/internal/agent-lab/tools";
        /**
         * 内部令牌。空值 = 子进程形态不可用（端点直接拒绝），而不是"跳过校验"。
         * 这个令牌只在父子进程之间共享，不对外发放。
         */
        private String internalToken = "";
        /** 单次 JSON-RPC 往返的上限。握手是三步，实测本地子进程 < 200ms。 */
        private Duration timeout = Duration.ofSeconds(20);
    }

    /**
     * 模型成本与配额。
     *
     * <p>单价单位是「货币 / 百万 token」，因为上游就是这么报价的；落库用微元
     * （1 单位 = 1e6 微元），于是有一个正好抵消的换算：
     * <pre>cost_micros = tokens × 单价</pre>
     * 不用浮点存钱，也不做"先除 1e6 再乘 1e6"的往返。
     *
     * <p><b>为什么要分三种输入价</b>：DeepSeek 等厂商对"输入命中缓存"的报价比未命中
     * 便宜一到两个数量级（Flash 是 $0.003 vs $0.15，**50 倍**）。只用一个输入价，
     * 长会话里缓存命中率高的时候成本会被高估几十倍——这种"算错但看起来合理"的数字最危险。
     *
     * <p><b>峰谷价</b>：官方峰谷相差 2 倍，且只按 UTC 工作日固定时段区分。
     * 这里按 {@code peak-windows-utc} 判定并乘 {@code peak-multiplier}；
     * 中国法定节假日不建模（那天会按峰值估价，属于保守高估）。不值得为它引入一张假期表。
     *
     * <p>配额按主线（pipelineKey）统计，{@code 0} 表示不限。
     * 限流的目标是"别让某条主线失控烧穿预算"，不是卡某个用户问了几句。
     */
    @Data
    public static class ModelUsage {
        /** 关闭后既不记账也不设闸门（只影响统计，不影响调用）。 */
        private boolean enabled = true;
        /** 计价货币，只作展示与对账标识，不参与换算。 */
        private String currency = "USD";
        /** 单条主线当日 token 上限；0 = 不限。 */
        private long dailyTokenQuota = 0;
        /** 单条主线当日成本上限（微元）；0 = 不限。 */
        private long dailyCostQuotaMicros = 0;
        /** 高峰期倍数（官方峰谷比 2.0）；1.0 表示不分峰谷。 */
        private double peakMultiplier = 1.0;
        /** 高峰时段，UTC，形如 {@code 01:00-04:00}；空表示不分峰谷。仅周一至周五生效。 */
        private List<String> peakWindowsUtc = new ArrayList<>();
        /** 模型 → 单价。没配的模型照样记账，但 cost 留空，不猜价格。 */
        private Map<String, Price> prices = new LinkedHashMap<>();

        /** 单价：货币 / 百万 token。命中缓存与未命中分开报价。 */
        @Data
        public static class Price {
            /** 输入命中缓存。上游没给这个数时按 {@code cacheMissInputPerMillion} 计。 */
            private double cacheHitInputPerMillion = 0;
            /** 输入未命中缓存。 */
            private double cacheMissInputPerMillion = 0;
            /** 输出（含推理 token）。 */
            private double outputPerMillion = 0;
        }
    }

    /**
     * RAGFlow 接入参数。
     *
     * <p>字段命名对齐官方 HTTP API v1：{@code parseType=1} 表示内置解析器，
     * {@code parserId} 决定切片方式。原项目用 DeepDOC 做版面分析，这里用 naive 文本切片，
     * 因为上游已经由 OCR/PDFBox 归一化成了干净 Markdown。
     */
    @Data
    public static class Ragflow {
        private String baseUrl = "http://127.0.0.1:9380";
        private String apiKey = "";
        /** 数据集名前缀，实际名称为 prefix + documentId，便于按文档隔离与幂等清理。 */
        private String datasetPrefix = "healthos-";
        private String parserId = "naive";
        /** 1 = 内置解析器，2 = Pipeline 编排。 */
        private int parseType = 1;
        private String embeddingModel = "";
        private Duration timeout = Duration.ofSeconds(120);
        /** 触发解析后轮询文档状态的总等待时长与间隔。 */
        private Duration parseTimeout = Duration.ofSeconds(180);
        private Duration parsePollInterval = Duration.ofSeconds(2);
        /** 检索相似度阈值，低于该值的结果不参与作答。默认值对齐原工程 preset 的 0.2。 */
        private double similarityThreshold = 0.2;
        /** 关键词相似度权重；向量占 1-weight。同样对齐原工程 preset（0.3 / 0.7）。 */
        private double keywordsSimilarityWeight = 0.3;
        /** 重排模型 ID，留空表示不重排（原工程 preset 里也是空串）。 */
        private String rerankId = "";
        private int pageSize = 30;
    }

    /**
     * 问答配置。{@code mode} 默认 {@code single}（保持原单路行为），
     * 改成 {@code parallel} 才会走三路并行 + 汇总。
     */
    @Data
    public static class Qa {
        private String mode = "single";
        /** 单路分支的硬超时，超时即判该路失败，不影响其余路。 */
        private Duration branchTimeout = Duration.ofSeconds(20);
        /**
         * 持久化问答运行的总时限。
         *
         * <p>超时的运行不会被自动判死——它停在当前节点，运维可以按节点重试。
         * 自动杀掉正在跑的模型调用反而更糟：那次开销已经花掉了，
         * 却什么结果都没留下。
         */
        private Duration runTimeout = Duration.ofMinutes(15);
        /**
         * 单个节点的自动尝试上限。
         *
         * <p>超过后不再自动重试，等人工按节点重试。自动重试只覆盖"外部服务抖动"
         * 这类明确可重试的失败，语义错误重试一百次也是错的。
         */
        private int nodeMaxAttempts = 2;
    }

    /**
     * 观测参数。
     *
     * <p>默认开启、默认只落库不外发：本地开发也能看到耗时分布，但不引入任何
     * OTLP Collector 之类的额外依赖。关掉即整条链路退化为空操作，业务零影响。
     */
    @Data
    public static class Tracing {
        private boolean enabled = true;
        /** async（默认，单写线程不阻塞业务）/ direct（同步写，便于测试与小部署）。 */
        private String writer = "async";
        /** 写队列上限。满了丢新条目并计数，绝不阻塞业务线程。 */
        private int queueCapacity = 10_000;
        /** 轨迹保留天数，过期由保留策略清理。 */
        private int retentionDays = 14;
        /** 单条轨迹最多保留的 span 数，超出部分丢弃（防止失控循环打爆表）。 */
        private int maxSpansPerTrace = 500;
        /** 单次清理最多处理的批次数，每批 {@code sweepBatchSize} 行。 */
        private int sweepBatchSize = 200;
        private int sweepMaxBatches = 50;
        /** 每日清理时点（Spring cron 六段式）。 */
        private String retentionCron = "0 30 3 * * *";
        /** 概览统计扫描的最大行数，避免大表把接口拖死。 */
        private int overviewMaxRows = 5_000;
    }

    /** 患者域参数。 */
    @Data
    public static class Patient {
        /** 对象存储里的患者附件前缀，同时是孤儿扫描的边界。 */
        private String objectPrefix = "patients";
        /** 单个附件大小上限（字节）。 */
        private long maxObjectBytes = 20L * 1024 * 1024;
        /** 单批次导入最大行数。 */
        private int maxBatchRows = 5_000;
    }

    /**
     * 健康管理（care）参数。
     *
     * <p>{@code signerUsers} 是<b>可签发账号白名单</b>。签发意味着"以专业身份确认这份内容"，
     * 不能人人可签——否则"已签发"这个状态就失去了意义。对齐原工程
     * {@code settings.care_doctor_users}。
     */
    @Data
    public static class Care {
        /** 可签发报告/计划的账号白名单。空列表表示谁都不能签（安全默认）。 */
        private List<String> signerUsers = new ArrayList<>(List.of("admin", "doctor"));
        /** 审核意见最少有效字符数（去掉空白后计）。低于此值拒绝签发。 */
        private int minReviewCommentChars = 5;
        /** 计划起始日期缺省值相对今天的天数偏移。 */
        private int defaultPlanStartDays = 0;
        /** 四周计划的天数。 */
        private int planDays = 28;
        /** PDF 导出参数。 */
        private Pdf pdf = new Pdf();

        /**
         * PDF 导出。
         *
         * <p>中文 PDF 必须嵌入字体，PDFBox 没有内置 CJK 字体（reportlab 的 STSong-Light
         * 是它自己的 CID 字体，不能照搬）。字体属于<b>部署环境件</b>，不进 jar：
         * 配置 {@code font-path} 指向 .ttf/.ttc，或留空按常见系统路径探测。
         * 探测不到不是启动失败——但导出会明确报 {@code CARE_PDF_UNAVAILABLE}，
         * 且 {@code /health/dependencies} 里能看到原因，不允许"静默出一个没字的 PDF"。
         */
        @Data
        public static class Pdf {
            /** 字体文件或目录；支持 .ttf / .ttc。留空则按常见路径探测。 */
            private String fontPath = "";
            /** 关闭后导出接口一律报不可用（用于明确不提供 PDF 的部署）。 */
            private boolean enabled = true;
        }
    }

    /**
     * 模型配置中心（model routing）参数。
     *
     * <p>对应原工程 {@code config.Settings} 里的模型路由相关项。
     *
     * <p><b>默认关闭</b>，而且启用必须给 {@code masterKey}。原因很直接：
     * 这个配置中心管的是"每条业务主线调哪个模型、用哪把密钥"，
     * 密钥会加密落库、指纹会参与历史比对。主密钥留空就启用，
     * 等于把"能不能安全地换密钥"这件事悄悄降级成"先把密钥存下来再说"。
     */
    @Data
    public static class ModelRouting {
        /** 是否启用模型配置中心。关闭时相关接口一律 503。 */
        private boolean enabled = false;
        /** 主密钥：凭据加密（AES-GCM）与配置指纹（HMAC-SHA256）都用它。留空则无法启用。 */
        private String masterKey = "";
        /** 服务端代理地址。留空表示不支持 {@code system_proxy} 连接模式。 */
        private String systemProxy = "";
        /** 基线供应商 ID：与它一致、且协议为 chat 的配置视为迁移基线，免能力探测。 */
        private String baselineProviderId = "qwen";
        /** 基线模型名。 */
        private String baselineModel = "qwen-max";
        /** 基线地址。 */
        private String baselineBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        /** 基线协议（chat / responses / chat_tool）。 */
        private String baselineProtocol = "chat";
        /** 供应商调用总超时（秒）。初始化默认参数时按它推算各级时限。 */
        private int providerTimeoutSeconds = 120;
        /** 结构化输出上限（token）。初始化默认参数时按它封顶。 */
        private int structuredOutputMaxTokens = 8000;
    }

    @Data
    public static class Ocr {
        /** none（未接入，遇到扫描件明确报错）/ paddle / generic。 */
        private String provider = "none";
        /** paddle 走 {@code /predict/ocr_system}，generic 走 {@code /ocr}。 */
        private String protocol = "generic";
        private String baseUrl = "";
        private String apiKey = "";
        private String language = "chi_sim";
        private Duration timeout = Duration.ofSeconds(180);
        /** PDF 转图分辨率。200 DPI 是识别率与耗时的常用折中。 */
        private int dpi = 200;
        /** 单页识别结果的置信度下限，低于该值的行直接丢弃，不参与后续抽取。 */
        private double minConfidence = 0.45;
        /** 单文档最多 OCR 页数，防止超大扫描件打满工作线程。 */
        private int maxPages = 200;

        /**
         * 文本层 / OCR 分流模式，对齐原工程 {@code patient_ocr_mode}：
         * {@code auto}（逐页判定，仅对不达标页补 OCR）、{@code text}（只认文本层，不足即报错）、
         * {@code ocr}（无条件整篇 OCR，用于强制重扫）。
         */
        private String mode = "auto";
        /**
         * <b>每页</b>文本层有效字符下限。整篇一次计数会让"前几页正文 + 后几页扫描图"
         * 因总数达标而漏掉扫描页，因此判定必须逐页做；任一页不足即该页不可信。
         * 80 取自原工程 {@code document_ocr.py} 的逐页阈值。
         */
        private int textLayerMinCharsPerPage = 80;
        /** 整篇有效字符下限：防止"每页只有页码"的乱码文本层被当成正文。 */
        private int textLayerMinChars = 20;
        /** 是否允许"字少且页面无图像"的书名页 / 空白分隔页留在文本路径。有图页永不豁免。 */
        private boolean allowSparseTextPages = false;
        /** OCR 输出有效字符下限，低于此值视为识别失败，不产出半截文档。 */
        private int ocrMinResultChars = 30;
    }

    /**
     * 图存储参数。
     *
     * <p>{@code jdbc} 用 PostgreSQL 表（默认，零外部依赖）；{@code neo4j} 以 Neo4j 为权威；
     * {@code dual} 双写，读走 Neo4j、失败回落 JDBC。
     */
    @Data
    public static class Graph {
        private String store = "jdbc";
        private Neo4j neo4j = new Neo4j();
    }

    @Data
    public static class Neo4j {
        private String uri = "bolt://127.0.0.1:7687";
        private String username = "neo4j";
        private String password = "";
        private String database = "neo4j";
        /** 建连超时，避免 Neo4j 不可达时请求线程长时间挂起。 */
        private Duration connectionTimeout = Duration.ofSeconds(5);
        /** 多跳查询的默认与最大跳数。 */
        private int defaultDepth = 1;
        private int maxDepth = 4;
    }
}
