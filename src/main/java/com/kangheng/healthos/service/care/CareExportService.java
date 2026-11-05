package com.kangheng.healthos.service.care;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.care.CareContract;
import com.kangheng.healthos.mapper.care.CareDocumentMapper;
import com.kangheng.healthos.model.care.CareDocument;

import org.springframework.stereotype.Service;

/**
 * 健康管理文档导出。
 *
 * <p>对应原工程 {@code care_api} 的 {@code /reports/{id}/export}、{@code /plans/{id}/export}
 * 与两个 {@code /pdf} 端点。本工程把 report / plan 合并成 {@code /documents/{id}} 一族，
 * 所以导出路径也统一到 {@code /documents/{id}/export|pdf}——<b>kind 由文档自己决定，
 * 不需要客户端再选一次路径</b>，少一处"传错 kind 就 404"的机会。
 *
 * <p>两条硬规则：
 * <ol>
 *   <li><b>未完成不导出</b>：{@code complete=false} 时返回 409。半份报告一旦流出，
 *       接收方无法判断它缺了哪一节；</li>
 *   <li><b>导出即快照</b>：JSON 用的是生成时冻结的 aggregate，PDF 用的是落库的 sections，
 *       两者都不重新调模型、不重新算指标。</li>
 * </ol>
 */
@Service
public class CareExportService {

    private static final String ERROR_INCOMPLETE = "CARE_DOCUMENT_INCOMPLETE";

    private final CareService careService;
    private final CareDocumentMapper documentMapper;
    private final CarePdfRenderer pdfRenderer;

    public CareExportService(CareService careService,
                             CareDocumentMapper documentMapper,
                             CarePdfRenderer pdfRenderer) {
        this.careService = careService;
        this.documentMapper = documentMapper;
        this.pdfRenderer = pdfRenderer;
    }

    /** 导出结果：文件名、媒体类型与内容。 */
    public record ExportFile(String filename, String contentType, byte[] content) {
    }

    /**
     * 导出 JSON。
     *
     * <p>聚合体是生成时冻结的，但 <b>status / revision / signature 取当前值</b>：
     * 原工程存库的 aggregate 在签发后不再更新，导出一份"已签发"的报告却写着
     * {@code status=draft}，接收方会以为这是未审稿。
     */
    public ExportFile exportJson(String owner, String documentId) {
        CareContract.DocumentView view = requireComplete(owner, documentId);
        CareDocument document = documentMapper.selectById(documentId);

        Map<String, Object> payload = new LinkedHashMap<>(Json.readMap(document.getAggregate()));
        payload.put("status", document.getStatus());
        payload.put("revision", document.getRevision());
        payload.put("signedRevision", document.getSignedRevision());
        payload.put("activeRevision", document.getActiveRevision());
        payload.put("complete", view.complete());
        payload.put("sections", sectionMap(view.sections()));
        if (view.signature() != null) {
            payload.put("signature", signatureMap(view.signature()));
        }
        payload.put("disclaimer", CareContract.DISCLAIMER);
        payload.put("exportedAt", OffsetDateTime.now().toString());

        String name = (isPlan(document) ? "客户四周执行计划" : "客户健康评估报告") + ".json";
        return new ExportFile(name, "application/json", Json.write(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 导出 PDF。字体不可用时报 503，不返回半成品。 */
    public ExportFile renderPdf(String owner, String documentId) {
        CareContract.DocumentView view = requireComplete(owner, documentId);
        CareDocument document = documentMapper.selectById(documentId);
        Map<String, Object> snapshot = Json.readMap(document.getSourceSnapshot());
        byte[] content = pdfRenderer.render(view, snapshot);
        String base = isPlan(document) ? "客户四周执行计划" : "客户健康评估报告";
        int revision = document.getRevision() == null ? 1 : document.getRevision();
        return new ExportFile(base + "_v" + revision + ".pdf", "application/pdf", content);
    }

    /** 健康检查里暴露 PDF 能力，缺字体时运维能看见，而不是等用户点导出才发现。 */
    public Map<String, Object> pdfStatus() {
        return pdfRenderer.status();
    }

    private CareContract.DocumentView requireComplete(String owner, String documentId) {
        // get() 已做 owner 校验（越权 404），这里只需要判断完整度。
        CareContract.DocumentView view = careService.get(owner, documentId);
        if (!view.complete()) {
            throw new BizException(ERROR_INCOMPLETE,
                    "文档尚未完成生成与汇总校验，不能导出", org.springframework.http.HttpStatus.CONFLICT);
        }
        return view;
    }

    private boolean isPlan(CareDocument document) {
        return CareService.KIND_PLAN.equals(document.getKind());
    }

    private Map<String, Object> sectionMap(List<CareContract.SectionView> sections) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (CareContract.SectionView section : sections) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", section.title());
            item.put("text", section.text());
            item.put("metricRefs", section.metricRefs());
            item.put("present", section.present());
            map.put(section.key(), item);
        }
        return map;
    }

    private Map<String, Object> signatureMap(CareContract.SignatureView signature) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("signer", signature.signer());
        map.put("reviewComment", signature.reviewComment());
        map.put("signedRevision", signature.signedRevision());
        map.put("contentHash", signature.contentHash());
        map.put("startDate", signature.startDate());
        map.put("kind", signature.kind());
        map.put("signedAt", signature.signedAt() == null ? null : signature.signedAt().toString());
        return map;
    }
}
