package com.kangheng.healthos.service.patient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.config.HealthOsProperties;
import com.kangheng.healthos.service.outbox.OutboxService;
import com.kangheng.healthos.contract.patient.PatientContract;
import com.kangheng.healthos.mapper.patient.PatientBatchMapper;
import com.kangheng.healthos.mapper.patient.PatientBatchRowMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.patient.PatientBatch;
import com.kangheng.healthos.model.patient.PatientBatchRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import com.kangheng.healthos.common.CsvLite;

/**
 * 批量导入。
 *
 * <p>设计要点：
 * <ol>
 *   <li><b>提交与执行分离</b>：接口只落批次和行、写一条 outbox 事件就返回，
 *       实际导入由 worker 异步做——5000 行不该卡住一个 HTTP 请求；</li>
 *   <li><b>逐行事务</b>：某一行数据坏掉只让那一行失败，不连坐整批；</li>
 *   <li><b>幂等</b>：同一份文件（sha256 相同）重复提交直接返回已有批次；
 *       每行按外部业务号 upsert，重跑不会建出重复档案。</li>
 * </ol>
 */
@Service
public class PatientBatchService {

    private static final Logger log = LoggerFactory.getLogger(PatientBatchService.class);

    public static final String EVENT_IMPORT = "patient.batch.import";

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_FAILED = "FAILED";
    private static final String ROW_PENDING = "PENDING";
    private static final String ROW_SUCCEEDED = "SUCCEEDED";
    private static final String ROW_FAILED = "FAILED";

    /** 表头别名，容忍"external_id / externalId / external_ref"这类写法。 */
    private static final Map<String, String> ALIASES = Map.of(
            "external_id", "external_ref",
            "externalid", "external_ref",
            "externalref", "external_ref",
            "birthdate", "birth_date",
            "birthday", "birth_date",
            "mobile", "phone",
            "sex", "gender");

    private static final Set<String> KNOWN_FIELDS = Set.of(
            "external_ref", "name", "gender", "birth_date", "phone");

    private final PatientBatchMapper batchMapper;
    private final PatientBatchRowMapper rowMapper;
    private final PatientLifecycleService lifecycle;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;
    private final HealthOsProperties properties;

    public PatientBatchService(PatientBatchMapper batchMapper,
                               PatientBatchRowMapper rowMapper,
                               PatientLifecycleService lifecycle,
                               OutboxService outboxService,
                               PlatformTransactionManager transactionManager,
                               HealthOsProperties properties) {
        this.batchMapper = batchMapper;
        this.rowMapper = rowMapper;
        this.lifecycle = lifecycle;
        this.outboxService = outboxService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    // ------------------------------------------------------------ 提交

    @Transactional
    public PatientContract.BatchSubmitResponse submit(String filename, byte[] content, String actor) {
        if (content == null || content.length == 0) {
            throw BizException.badRequest("批次文件为空");
        }
        String text = stripBom(new String(content, StandardCharsets.UTF_8));
        String format = detectFormat(text);
        List<Map<String, String>> rows = "JSON".equals(format) ? parseJson(text) : parseCsv(text);
        if (rows.isEmpty()) {
            throw BizException.badRequest("批次文件没有有效数据行");
        }
        int max = properties.getPatient().getMaxBatchRows();
        if (rows.size() > max) {
            throw BizException.badRequest("单批次最多 " + max + " 行，当前 " + rows.size() + " 行");
        }

        String sha256 = Ids.sha256Hex(content);
        // 幂等查重必须限定在同一个归属人内：否则 A 上传过的文件，B 再传一次
        // 会拿到 A 的批次——那等于把别人的导入记录和 patientId 直接暴露出去。
        PatientBatch existing = batchMapper.selectOne(new LambdaQueryWrapper<PatientBatch>()
                .eq(PatientBatch::getSha256, sha256)
                .eq(PatientBatch::getCreatedBy, actor)
                .orderByDesc(PatientBatch::getCreatedAt)
                .last("limit 1"));
        if (existing != null && !STATUS_FAILED.equals(existing.getStatus())) {
            // 同一份文件重复上传：直接返回上次的批次，不再建一遍
            return new PatientContract.BatchSubmitResponse(existing.getId(), existing.getStatus(),
                    existing.getTotalRows() == null ? 0 : existing.getTotalRows(), true);
        }

        OffsetDateTime now = OffsetDateTime.now();
        PatientBatch batch = new PatientBatch();
        batch.setId(Ids.newId("pbt"));
        batch.setFilename(truncate(StringUtils.hasText(filename) ? filename : "batch." + format.toLowerCase(Locale.ROOT), 300));
        batch.setSha256(sha256);
        batch.setFormat(format);
        batch.setTotalRows(rows.size());
        batch.setSucceededRows(0);
        batch.setFailedRows(0);
        batch.setStatus(STATUS_PENDING);
        batch.setCreatedBy(actor);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batchMapper.insert(batch);

        int rowNo = 0;
        for (Map<String, String> row : rows) {
            rowNo++;
            PatientBatchRow entity = new PatientBatchRow();
            entity.setId(Ids.newId("pbr"));
            entity.setBatchId(batch.getId());
            entity.setOwner(actor);
            entity.setRowNo(rowNo);
            entity.setExternalRef(row.get("external_ref"));
            entity.setPayload(Json.write(row));
            entity.setStatus(ROW_PENDING);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            rowMapper.insert(entity);
        }
        outboxService.append("patient_batch", batch.getId(), EVENT_IMPORT,
                Map.of("batchId", batch.getId()));
        return new PatientContract.BatchSubmitResponse(batch.getId(), batch.getStatus(), rows.size(), false);
    }

    // ------------------------------------------------------------ 执行

    /** 由 outbox worker 调用。可重复执行：已完成直接返回，失败行保留原状态。 */
    public void process(String batchId) {
        PatientBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BizException("PATIENT_BATCH_NOT_FOUND", "批次不存在: " + batchId,
                    org.springframework.http.HttpStatus.NOT_FOUND);
        }
        if (STATUS_SUCCEEDED.equals(batch.getStatus())) {
            return;
        }
        batch.setStatus(STATUS_RUNNING);
        batch.setUpdatedAt(OffsetDateTime.now());
        batchMapper.updateById(batch);

        List<PatientBatchRow> rows = rowMapper.selectList(new LambdaQueryWrapper<PatientBatchRow>()
                .eq(PatientBatchRow::getBatchId, batchId)
                .eq(PatientBatchRow::getStatus, ROW_PENDING)
                .orderByAsc(PatientBatchRow::getRowNo));
        for (PatientBatchRow row : rows) {
            try {
                // 一行一个事务：成功了连行状态一起提交，失败了整行回滚再单独记失败
                transactionTemplate.executeWithoutResult(status -> importRow(row, batch.getCreatedBy()));
            } catch (RuntimeException ex) {
                markFailed(row, ex);
            }
        }
        recount(batch);
    }

    private void importRow(PatientBatchRow row, String actor) {
        Map<String, Object> payload = Json.readMap(row.getPayload());
        String name = text(payload.get("name"));
        String gender = text(payload.get("gender"));
        if (!StringUtils.hasText(name)) {
            throw new BizException("PATIENT_NAME_REQUIRED", "缺少姓名",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        if (!StringUtils.hasText(gender)) {
            throw new BizException("PATIENT_GENDER_REQUIRED", "缺少性别",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        LocalDate birthDate = null;
        String birth = text(payload.get("birth_date"));
        if (StringUtils.hasText(birth)) {
            try {
                birthDate = LocalDate.parse(birth);
            } catch (RuntimeException ex) {
                throw new BizException("PATIENT_INVALID_BIRTH_DATE", "出生日期格式应为 yyyy-MM-dd：" + birth,
                        org.springframework.http.HttpStatus.BAD_REQUEST);
            }
        }
        Patient patient = lifecycle.upsertFromExternal(text(payload.get("external_ref")),
                name.strip(), gender.strip(), birthDate, text(payload.get("phone")), actor);

        row.setStatus(ROW_SUCCEEDED);
        row.setPatientId(patient.getId());
        row.setErrorCode(null);
        row.setErrorMessage(null);
        row.setUpdatedAt(OffsetDateTime.now());
        rowMapper.updateById(row);
    }

    private void markFailed(PatientBatchRow row, RuntimeException ex) {
        String code = ex instanceof BizException biz ? biz.getCode() : "PATIENT_IMPORT_FAILED";
        row.setStatus(ROW_FAILED);
        row.setErrorCode(code);
        row.setErrorMessage(truncate(ex.getMessage() == null ? ex.getClass().getSimpleName()
                : ex.getMessage(), 500));
        row.setUpdatedAt(OffsetDateTime.now());
        rowMapper.updateById(row);
        log.warn("批次导入第 {} 行失败 code={} msg={}", row.getRowNo(), code, row.getErrorMessage());
    }

    private void recount(PatientBatch batch) {
        long succeeded = count(batch.getId(), ROW_SUCCEEDED);
        long failed = count(batch.getId(), ROW_FAILED);
        long pending = count(batch.getId(), ROW_PENDING);
        String status;
        if (pending > 0) {
            status = STATUS_RUNNING;
        } else if (failed == 0) {
            status = STATUS_SUCCEEDED;
        } else if (succeeded > 0) {
            status = STATUS_PARTIAL;
        } else {
            status = STATUS_FAILED;
        }
        batch.setSucceededRows((int) succeeded);
        batch.setFailedRows((int) failed);
        batch.setStatus(status);
        batch.setUpdatedAt(OffsetDateTime.now());
        batchMapper.updateById(batch);
    }

    private long count(String batchId, String status) {
        return rowMapper.selectCount(new LambdaQueryWrapper<PatientBatchRow>()
                .eq(PatientBatchRow::getBatchId, batchId)
                .eq(PatientBatchRow::getStatus, status));
    }

    // ------------------------------------------------------------ 查询与重试

    public PatientContract.BatchView status(String batchId, String owner) {
        PatientBatch batch = requireBatch(batchId, owner);
        List<PatientContract.BatchRowView> rows = rowMapper.selectList(
                        new LambdaQueryWrapper<PatientBatchRow>()
                                .eq(PatientBatchRow::getBatchId, batchId)
                                .eq(PatientBatchRow::getOwner, owner)
                                .orderByAsc(PatientBatchRow::getRowNo))
                .stream()
                .map(row -> new PatientContract.BatchRowView(row.getRowNo(), row.getExternalRef(),
                        row.getStatus(), row.getPatientId(), row.getErrorCode(), row.getErrorMessage()))
                .toList();
        return new PatientContract.BatchView(batch.getId(), batch.getFilename(), batch.getFormat(),
                batch.getStatus(), intOf(batch.getTotalRows()), intOf(batch.getSucceededRows()),
                intOf(batch.getFailedRows()), batch.getCreatedBy(), batch.getErrorMessage(),
                batch.getCreatedAt(), batch.getUpdatedAt(), rows);
    }

    @Transactional
    public PatientContract.BatchView retry(String batchId, String owner) {
        PatientBatch batch = requireBatch(batchId, owner);
        int reset = rowMapper.update(null, new LambdaUpdateWrapper<PatientBatchRow>()
                .eq(PatientBatchRow::getBatchId, batchId)
                .eq(PatientBatchRow::getOwner, owner)
                .eq(PatientBatchRow::getStatus, ROW_FAILED)
                .set(PatientBatchRow::getStatus, ROW_PENDING)
                .set(PatientBatchRow::getErrorCode, null)
                .set(PatientBatchRow::getErrorMessage, null)
                .set(PatientBatchRow::getUpdatedAt, OffsetDateTime.now()));
        if (reset == 0) {
            throw BizException.badRequest("没有失败行需要重试");
        }
        batch.setStatus(STATUS_PENDING);
        batch.setUpdatedAt(OffsetDateTime.now());
        batchMapper.updateById(batch);
        outboxService.append("patient_batch", batchId, EVENT_IMPORT, Map.of("batchId", batchId));
        return status(batchId, owner);
    }

    /**
     * 取批次并校验归属。
     *
     * <p>越权返回 404：批次里带着 patientId 与行内容，403 等于承认它存在。
     */
    private PatientBatch requireBatch(String batchId, String owner) {
        PatientBatch batch = batchMapper.selectOne(new LambdaQueryWrapper<PatientBatch>()
                .eq(PatientBatch::getId, batchId)
                .eq(PatientBatch::getCreatedBy, owner)
                .last("limit 1"));
        if (batch == null) {
            throw BizException.notFound("批次");
        }
        return batch;
    }

    // ------------------------------------------------------------ 解析

    private String detectFormat(String text) {
        String trimmed = text.stripLeading();
        return trimmed.startsWith("[") || trimmed.startsWith("{") ? "JSON" : "CSV";
    }

    private List<Map<String, String>> parseJson(String text) {
        // 先按 Object 读，再逐个判断是不是对象：JSON 顶层可能是数组里混了字符串，
        // 直接绑定成 Map 会在运行期抛转换异常，错误信息还看不出是第几行。
        List<Object> items = Json.readList(text, Object.class);
        List<Map<String, String>> rows = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, String> normalized = new LinkedHashMap<>();
            for (String field : KNOWN_FIELDS) {
                Object value = map.get(field);
                if (value == null) {
                    value = map.get(field.replace("_", ""));
                }
                if (value != null) {
                    normalized.put(field, String.valueOf(value).strip());
                }
            }
            if (!normalized.isEmpty()) {
                rows.add(normalized);
            }
        }
        return rows;
    }

    private List<Map<String, String>> parseCsv(String text) {
        List<List<String>> raw = CsvLite.parse(text);
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> header = null;
        for (List<String> row : raw) {
            if (CsvLite.isBlank(row)) {
                continue;
            }
            if (header == null) {
                header = new ArrayList<>();
                for (String cell : row) {
                    header.add(normalizeHeader(cell));
                }
                if (!header.contains("name") || !header.contains("gender")) {
                    throw BizException.badRequest("CSV 表头必须包含 name 与 gender，当前为：" + String.join(",", header));
                }
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            for (int i = 0; i < header.size() && i < row.size(); i++) {
                String key = header.get(i);
                if (KNOWN_FIELDS.contains(key)) {
                    item.put(key, row.get(i) == null ? "" : row.get(i).strip());
                }
            }
            if (!item.isEmpty()) {
                rows.add(item);
            }
        }
        return rows;
    }

    private static String normalizeHeader(String cell) {
        String key = cell == null ? "" : cell.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        return ALIASES.getOrDefault(key, key);
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value).strip();
    }

    private static int intOf(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
