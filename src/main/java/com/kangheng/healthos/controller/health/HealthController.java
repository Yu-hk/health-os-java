package com.kangheng.healthos.controller.health;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.kangheng.healthos.spi.ai.LlmProvider;
import com.kangheng.healthos.service.graph.GraphService;
import com.kangheng.healthos.spi.ocr.OcrProvider;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;
import com.kangheng.healthos.spi.storage.ObjectStorage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.service.qa.BranchOrchestrator;
import com.kangheng.healthos.service.tracing.TracingService;
import com.kangheng.healthos.spi.tracing.TraceWriter;

/**
 * 健康检查。
 *
 * <p>与源工程的 {@code /health}、{@code /health/dependencies} 对齐：
 * 前者只表明进程可用，后者才反映数据库、对象存储、OCR、知识库、图库是否真的能干活。
 * 发布脚本不应该把 {@code /health=200} 当成业务可用。
 *
 * <p>外部依赖的判定分两档：
 * <ul>
 *   <li>声明了要用的外部服务不可达 → {@code degraded}（比如配了 RAGFlow 但连不上）；</li>
 *   <li>本来就没启用的外部服务 → 记为 {@code disabled}，<b>不影响</b> ready 判定。</li>
 * </ul>
 * 这样才能区分"本地模式跑得好好的"和"生产配了外部依赖但挂了"。
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectStorage storage;
    private final LlmProvider llmProvider;
    private final OcrProvider ocrProvider;
    private final KnowledgeBase knowledgeBase;
    private final GraphService graphService;
    private final com.kangheng.healthos.config.HealthOsProperties properties;
    private final com.kangheng.healthos.service.qa.BranchOrchestrator branchOrchestrator;
    private final com.kangheng.healthos.service.tracing.TracingService tracing;
    private final com.kangheng.healthos.service.care.CarePdfRenderer carePdfRenderer;
    private final com.kangheng.healthos.spi.ai.EmbeddingModel embeddingModel;
    private final com.kangheng.healthos.service.auth.SessionRevocationRegistry revocations;

    public HealthController(JdbcTemplate jdbcTemplate,
                            ObjectStorage storage,
                            LlmProvider llmProvider,
                            OcrProvider ocrProvider,
                            KnowledgeBase knowledgeBase,
                            GraphService graphService,
                            com.kangheng.healthos.config.HealthOsProperties properties,
                            com.kangheng.healthos.service.qa.BranchOrchestrator branchOrchestrator,
                            com.kangheng.healthos.service.tracing.TracingService tracing,
                            com.kangheng.healthos.service.care.CarePdfRenderer carePdfRenderer,
                            com.kangheng.healthos.spi.ai.EmbeddingModel embeddingModel,
                            com.kangheng.healthos.service.auth.SessionRevocationRegistry revocations) {
        this.jdbcTemplate = jdbcTemplate;
        this.storage = storage;
        this.llmProvider = llmProvider;
        this.ocrProvider = ocrProvider;
        this.knowledgeBase = knowledgeBase;
        this.graphService = graphService;
        this.properties = properties;
        this.branchOrchestrator = branchOrchestrator;
        this.tracing = tracing;
        this.carePdfRenderer = carePdfRenderer;
        this.embeddingModel = embeddingModel;
        this.revocations = revocations;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("service", "health-os-java");
        body.put("timestamp", OffsetDateTime.now());
        return body;
    }

    @GetMapping("/health/dependencies")
    public Map<String, Object> dependencies() {
        Map<String, Object> body = new LinkedHashMap<>();
        String database = probeDatabase();
        boolean databaseOk = "ok".equals(database);
        body.put("database", database);
        boolean storageOk = storage.healthy();
        body.put("objectStorage", storageOk ? "ok" : "unavailable");
        body.put("llmProvider", llmProvider.name());

        boolean ocrRequired = ocrProvider.enabled();
        boolean ocrOk = ocrProvider.healthy();
        body.put("ocr", ocrRequired ? (ocrOk ? "ok" : "unavailable") : "disabled");

        boolean kbRequired = !"local".equals(knowledgeBase.name());
        boolean kbOk = knowledgeBase.healthy();
        body.put("knowledgeBase", knowledgeBase.name() + (kbOk ? ":ok" : ":unavailable"));

        // 向量化是可选增强：关掉不影响 ready，但"开着却连不上"必须能看见——
        // 否则检索质量下降会被误判成模型不行，而不是向量没建起来
        if (embeddingModel.enabled()) {
            boolean embeddingOk = embeddingModel.healthy();
            body.put("embedding", embeddingModel.id()
                    + (embeddingOk ? ":ok" : ":unavailable")
                    + "(dim=" + embeddingModel.dimension() + ")");
        } else {
            body.put("embedding", "disabled");
        }

        boolean graphOk = graphService.storeHealthy();
        body.put("graphStore", graphService.storeName() + (graphOk ? ":ok" : ":unavailable"));

        // 问答装配一眼可见：默认模式 + 实际注册了几路分支
        body.put("qaMode", properties.getQa().getMode());
        body.put("qaBranches", branchOrchestrator.descriptors().stream()
                .map(item -> String.valueOf(item.get("key"))).toList());

        // 观测是可选能力，关掉不算不健康；但"开着且写不进去"要能看见丢弃数
        com.kangheng.healthos.spi.tracing.TraceWriter.Stats traceStats = tracing.stats();
        body.put("tracing", tracing.enabled()
                ? traceStats.mode() + ":ok(dropped=" + traceStats.dropped() + ")"
                : "disabled");

        // 认证姿态：上线自查用。这两件事配错了不会报错，只会在出事时才被发现——
        // 明文口令（配置进了版本库）与 cookie-secure=false（令牌走明文信道）。
        body.put("auth", authPosture());

        // PDF 导出是可选项：缺中文字体不影响主链路，但必须能看见——
        // 否则要等到用户点"导出 PDF"才发现这份部署根本出不了报告。
        body.put("carePdf", carePdfPosture());

        boolean ready = databaseOk && storageOk
                && (!ocrRequired || ocrOk)
                && (!kbRequired || kbOk)
                && graphOk;
        body.put("status", ready ? "ready" : "degraded");
        return body;
    }

    private String probeDatabase() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return "ok";
        } catch (Exception ex) {
            return "unavailable: " + ex.getMessage();
        }
    }

    /**
     * 认证姿态摘要。
     *
     * <p>只暴露"配置形态"，不暴露任何口令或密钥片段——这些信息对攻击者没有价值，
     * 对运维却是"上线前该看一眼"的检查项。
     */
    private String authPosture() {
        com.kangheng.healthos.config.HealthOsProperties.Auth auth = properties.getAuth();
        boolean hashed = true;
        if (auth.getUsers() != null && !auth.getUsers().isEmpty()) {
            hashed = auth.getUsers().stream().allMatch(user -> user == null
                    || com.kangheng.healthos.service.auth.PasswordHash.isEncoded(user.getPassword()));
        } else {
            hashed = com.kangheng.healthos.service.auth.PasswordHash.isEncoded(auth.getPassword());
        }
        return "session:v2(ttl=" + auth.getSessionTtl().toSeconds() + "s"
                + ",secure=" + auth.isCookieSecure()
                + ",passwords=" + (hashed ? "pbkdf2" : "plaintext")
                + ",store=" + sessionStorePosture() + ")";
    }

    /**
     * 会话吊销名单的存储姿态。
     *
     * <p>三种取值都要能一眼分辨，否则"看着 ok、其实登出已经不可靠"就查不出来：
     * <ul>
     *   <li>{@code jdbc} —— 落库，重启不丢、多实例共享；</li>
     *   <li>{@code jdbc:DEGRADED} —— 落库模式但已经出现过库读写失败、退回过本地名单。
     *       此时登出只在本实例可靠，跨节点或重启会漏；</li>
     *   <li>{@code memory} —— 明确配置成内存模式，只适合单实例本地调试。</li>
     * </ul>
     */
    private String sessionStorePosture() {
        if (!revocations.shared()) {
            return "memory";
        }
        return revocations.degraded() ? "jdbc:DEGRADED" : "jdbc";
    }

    /** PDF 能力姿态：不含路径之外的信息，够运维判断"要不要装字体"。 */
    private String carePdfPosture() {
        Map<String, Object> status = carePdfRenderer.status();
        if (Boolean.TRUE.equals(status.get("available"))) {
            return "ok(font=" + status.get("font") + ")";
        }
        return "unavailable(" + status.get("reason") + ")";
    }
}
