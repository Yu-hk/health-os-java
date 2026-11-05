package com.kangheng.healthos.service.care;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.contract.care.CareContract;
import com.kangheng.healthos.mapper.care.CareDocumentMapper;
import com.kangheng.healthos.model.care.CareDocument;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 导出的两条硬规则：未完成不导出、导出内容反映当前状态。
 */
class CareExportServiceTest {

    private CareService careService;
    private CareDocumentMapper documentMapper;
    private CarePdfRenderer pdfRenderer;
    private CareExportService service;

    @BeforeEach
    void setUp() {
        careService = mock(CareService.class);
        documentMapper = mock(CareDocumentMapper.class);
        pdfRenderer = mock(CarePdfRenderer.class);
        service = new CareExportService(careService, documentMapper, pdfRenderer);
    }

    @Test
    @DisplayName("未生成完成 → 409，不导出半份报告")
    void 未完成拒绝导出() {
        when(careService.get("alice", "cdoc_1")).thenReturn(view(false));

        assertThatThrownBy(() -> service.exportJson("alice", "cdoc_1"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getCode()).isEqualTo("CARE_DOCUMENT_INCOMPLETE");
                    assertThat(biz.getStatus().value()).isEqualTo(409);
                });
        assertThatThrownBy(() -> service.renderPdf("alice", "cdoc_1"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("JSON 导出的状态取当前值，不是生成时冻结的 draft")
    void 导出状态取当前值() {
        when(careService.get("alice", "cdoc_1")).thenReturn(view(true));
        when(documentMapper.selectById("cdoc_1")).thenReturn(document("SIGNED", 3));

        CareExportService.ExportFile file = service.exportJson("alice", "cdoc_1");

        assertThat(file.filename()).isEqualTo("客户健康评估报告.json");
        assertThat(file.contentType()).isEqualTo("application/json");
        Map<String, Object> payload = Json.readMap(
                new String(file.content(), StandardCharsets.UTF_8));
        // aggregate 里存的还是生成时的 DRAFT，导出必须纠正为 SIGNED
        assertThat(payload).containsEntry("status", "SIGNED");
        assertThat(payload).containsEntry("revision", 3);
        assertThat(payload).containsKey("signature");
        assertThat(payload).containsKey("disclaimer");
        assertThat(String.valueOf(payload.get("sections"))).contains("血压偏高");
    }

    @Test
    @DisplayName("计划导出用计划文件名，PDF 文件名带版本号")
    void 计划与PDF文件名() {
        when(careService.get("alice", "cdoc_2")).thenReturn(view(true));
        CareDocument plan = document("ACTIVE", 2);
        plan.setKind(CareService.KIND_PLAN);
        when(documentMapper.selectById("cdoc_2")).thenReturn(plan);
        when(pdfRenderer.render(org.mockito.ArgumentMatchers.any(CareContract.DocumentView.class),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn("%PDF-1.7".getBytes(StandardCharsets.US_ASCII));

        assertThat(service.exportJson("alice", "cdoc_2").filename())
                .isEqualTo("客户四周执行计划.json");
        CareExportService.ExportFile pdf = service.renderPdf("alice", "cdoc_2");
        assertThat(pdf.filename()).isEqualTo("客户四周执行计划_v2.pdf");
        assertThat(pdf.contentType()).isEqualTo("application/pdf");
    }

    private CareContract.DocumentView view(boolean complete) {
        CareContract.SectionView section = new CareContract.SectionView(
                "profile", "综合健康画像", "血压偏高，建议减少钠摄入。", List.of("SBP"), true);
        CareContract.SignatureView signature = new CareContract.SignatureView(
                "doctor", "同意签发。", 1, "sha256:abc", "2026-11-01", "report",
                OffsetDateTime.now());
        return new CareContract.DocumentView(
                complete ? "cdoc_1" : "cdoc_1", "alice", "report", "pat_1",
                "SIGNED", 3, 3, null, "exam_1", 2, null, null, null, null,
                complete, List.of(section), List.of(), null, signature,
                CareContract.DISCLAIMER, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private CareDocument document(String status, int revision) {
        CareDocument document = new CareDocument();
        document.setId("cdoc_1");
        document.setOwner("alice");
        document.setKind(CareService.KIND_REPORT);
        document.setStatus(status);
        document.setRevision(revision);
        document.setAggregate(Json.write(Map.of("status", "DRAFT", "revision", 1)));
        document.setSourceSnapshot(Json.write(Map.of("basicInfo", Map.of("name", "张三"))));
        return document;
    }
}
