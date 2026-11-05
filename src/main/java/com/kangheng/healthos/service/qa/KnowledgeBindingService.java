package com.kangheng.healthos.service.qa;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.BizException;
import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.contract.qa.KnowledgeBindingContract;
import com.kangheng.healthos.mapper.patient.PatientMapper;
import com.kangheng.healthos.mapper.qa.KnowledgeBindingMapper;
import com.kangheng.healthos.model.patient.Patient;
import com.kangheng.healthos.model.qa.KnowledgeBindingEntity;
import com.kangheng.healthos.spi.retrieval.KnowledgeBase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 患者与外部知识库的绑定管理。
 *
 * <p>原工程的问答是"针对患者的多库检索"，本工程原来是"针对单个典籍文档检索"。
 * 补上绑定之后，两者可以并存：文档检索照旧，患者名下的库作为<b>额外依据</b>参与。
 *
 * <p>三条约束：
 * <ol>
 *   <li><b>绑定必须可核对</b>：{@code datasetId} 要能在 RAGFlow 的目录里找到。
 *       绑一个不存在的 ID，等于给这个患者挂了一个永远查不到内容的库，
 *       比报错更难排查；</li>
 *   <li><b>解绑不删库</b>：删数据是不可逆的，不该藏在"解绑"里；</li>
 *   <li><b>库没了不静默删记录</b>：标成 {@code STALE}，让"这个患者从什么时候开始少了依据"
 *       可被追溯。</li>
 * </ol>
 */
@Service
public class KnowledgeBindingService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBindingService.class);

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_STALE = "STALE";

    private final KnowledgeBindingMapper bindingMapper;
    private final PatientMapper patientMapper;
    private final KnowledgeBase knowledgeBase;

    public KnowledgeBindingService(KnowledgeBindingMapper bindingMapper,
                                   PatientMapper patientMapper,
                                   KnowledgeBase knowledgeBase) {
        this.bindingMapper = bindingMapper;
        this.patientMapper = patientMapper;
        this.knowledgeBase = knowledgeBase;
    }

    /** 患者的知识库总览：已绑的 + 可绑的。 */
    public KnowledgeBindingContract.Overview overview(String owner, String patientId) {
        requirePatient(owner, patientId);
        List<KnowledgeBindingEntity> bindings = bindings(owner, patientId);
        Set<String> bound = new LinkedHashSet<>();
        bindings.forEach(binding -> bound.add(binding.getDatasetId()));

        List<KnowledgeBindingContract.DatasetView> available = new ArrayList<>();
        String note = null;
        try {
            for (KnowledgeBase.DatasetInfo dataset : knowledgeBase.datasets()) {
                available.add(new KnowledgeBindingContract.DatasetView(dataset.id(), dataset.name(),
                        dataset.chunkCount(), dataset.documentCount(), bound.contains(dataset.id())));
            }
        } catch (RuntimeException ex) {
            // 外部不可用不影响"已经绑了什么"，这两件事必须分开呈现。
            note = "知识库目录不可用: " + ex.getMessage();
            log.warn("列举知识库失败 owner={} patient={}: {}", owner, patientId, ex.getMessage());
        }
        return new KnowledgeBindingContract.Overview(patientId,
                bindings.stream().map(this::toView).toList(), available, note);
    }

    /** 绑定。重复绑定同一个库是幂等的，返回已有记录。 */
    @Transactional
    public KnowledgeBindingContract.BindingView bind(String owner, String patientId,
                                                     KnowledgeBindingContract.BindRequest request) {
        requirePatient(owner, patientId);
        String datasetId = request.datasetId() == null ? "" : request.datasetId().strip();
        if (datasetId.isEmpty()) {
            throw BizException.badRequest("datasetId 不能为空");
        }

        KnowledgeBindingEntity existing = bindingMapper.selectOne(new LambdaQueryWrapper<KnowledgeBindingEntity>()
                .eq(KnowledgeBindingEntity::getOwner, owner)
                .eq(KnowledgeBindingEntity::getPatientId, patientId)
                .eq(KnowledgeBindingEntity::getDatasetId, datasetId)
                .last("LIMIT 1"));
        if (existing != null) {
            return toView(existing);
        }

        KnowledgeBase.DatasetInfo dataset = findDataset(datasetId);
        OffsetDateTime now = OffsetDateTime.now();
        KnowledgeBindingEntity entity = new KnowledgeBindingEntity();
        entity.setId(Ids.newId("qkb"));
        entity.setOwner(owner);
        entity.setPatientId(patientId);
        entity.setDatasetId(datasetId);
        entity.setDatasetName(dataset.name());
        entity.setNote(request.note());
        entity.setStatus(STATUS_ACTIVE);
        entity.setLastCheckedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        bindingMapper.insert(entity);
        log.info("知识库已绑定 owner={} patient={} dataset={}", owner, patientId, datasetId);
        return toView(entity);
    }

    /** 解绑：只删映射，不碰远端数据集。 */
    @Transactional
    public void unbind(String owner, String patientId, String bindingId) {
        requirePatient(owner, patientId);
        KnowledgeBindingEntity entity = bindingMapper.selectById(bindingId);
        if (entity == null || !owner.equals(entity.getOwner())
                || !patientId.equals(entity.getPatientId())) {
            throw BizException.notFound("知识库绑定");
        }
        bindingMapper.deleteById(bindingId);
        log.info("知识库已解绑（未删除远端数据集） owner={} patient={} dataset={}",
                owner, patientId, entity.getDatasetId());
    }

    /** 对账：库还在就 ACTIVE，没了就 STALE 并记下原因。 */
    @Transactional
    public KnowledgeBindingContract.BindingView reconcile(String owner, String patientId,
                                                           String bindingId) {
        requirePatient(owner, patientId);
        KnowledgeBindingEntity entity = bindingMapper.selectById(bindingId);
        if (entity == null || !owner.equals(entity.getOwner())
                || !patientId.equals(entity.getPatientId())) {
            throw BizException.notFound("知识库绑定");
        }
        OffsetDateTime now = OffsetDateTime.now();
        try {
            KnowledgeBase.DatasetInfo dataset = findDataset(entity.getDatasetId());
            entity.setDatasetName(dataset.name());
            entity.setStatus(STATUS_ACTIVE);
            entity.setLastError(null);
        } catch (BizException ex) {
            entity.setStatus(STATUS_STALE);
            entity.setLastError(ex.getMessage());
        }
        entity.setLastCheckedAt(now);
        entity.setUpdatedAt(now);
        bindingMapper.updateById(entity);
        return toView(entity);
    }

    /**
     * 问答可用的数据集 id。
     *
     * <p>只返回 {@code ACTIVE}：{@code STALE} 的库已经查不到了，继续传过去只会让检索
     * 每次多一次无谓的外部调用。
     */
    public List<String> activeDatasetIds(String owner, String patientId) {
        if (!StringUtils.hasText(patientId)) {
            return List.of();
        }
        return bindings(owner, patientId).stream()
                .filter(binding -> STATUS_ACTIVE.equals(binding.getStatus()))
                .map(KnowledgeBindingEntity::getDatasetId)
                .toList();
    }

    /**
     * 在患者绑定的库上检索，并与已有命中合并。
     *
     * <p>合并规则：
     * <ul>
     *   <li>按 {@code chunkId} 去重（同一个库可能被两个患者绑，也可能与文档检索重叠）；</li>
     *   <li>失败<b>只降级不报错</b>：患者资料库不可用时，典籍原文的问答仍然要能回答，
     *       但降级事实会写日志，不允许悄悄变少依据还不留痕；</li>
     *   <li>不做跨来源重排：RAGFlow 分与本地分不是同一把尺子，混排等于编一个序。</li>
     * </ul>
     */
    public List<KnowledgeBase.RetrievedChunk> augment(String owner, String patientId, String question,
                                                      List<KnowledgeBase.RetrievedChunk> base, int topK) {
        List<String> datasetIds = activeDatasetIds(owner, patientId);
        if (datasetIds.isEmpty()) {
            return base;
        }
        List<KnowledgeBase.RetrievedChunk> extra;
        try {
            extra = knowledgeBase.retrieveDatasets(datasetIds, question, topK);
        } catch (RuntimeException ex) {
            log.warn("患者知识库检索失败，本次仅用文档依据 patient={} datasets={}: {}",
                    patientId, datasetIds.size(), ex.getMessage());
            return base;
        }
        if (extra == null || extra.isEmpty()) {
            return base;
        }
        Map<String, KnowledgeBase.RetrievedChunk> merged = new LinkedHashMap<>();
        for (KnowledgeBase.RetrievedChunk chunk : base) {
            merged.putIfAbsent(key(chunk), chunk);
        }
        int added = 0;
        for (KnowledgeBase.RetrievedChunk chunk : extra) {
            if (merged.putIfAbsent(key(chunk), chunk) == null) {
                added++;
            }
        }
        log.info("患者知识库补充依据 patient={} datasets={} added={}", patientId, datasetIds.size(), added);
        return List.copyOf(merged.values());
    }

    /**
     * 去重键。
     *
     * <p><b>按内容而不是按 chunkId</b>：本地切片与 RAGFlow 切片是两份独立产生的 id，
     * 同一段原文在两个库里 id 不同，只比 id 会把它喂给模型两遍——
     * 模型会以为"多处提到"，实际上只有一处。
     */
    private String key(KnowledgeBase.RetrievedChunk chunk) {
        String content = chunk.content() == null ? "" : chunk.content().strip();
        return Integer.toHexString(content.hashCode()) + ":" + content.length();
    }

    // ------------------------------------------------------------ 内部

    private KnowledgeBase.DatasetInfo findDataset(String datasetId) {
        List<KnowledgeBase.DatasetInfo> datasets;
        try {
            datasets = knowledgeBase.datasets();
        } catch (RuntimeException ex) {
            throw new BizException("KNOWLEDGE_BASE_UNAVAILABLE",
                    "知识库目录不可用，无法核对数据集: " + ex.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (datasets.isEmpty()) {
            throw new BizException("KNOWLEDGE_BASE_UNSUPPORTED",
                    "当前知识库实现（" + knowledgeBase.name() + "）不支持数据集绑定",
                    HttpStatus.CONFLICT);
        }
        for (KnowledgeBase.DatasetInfo dataset : datasets) {
            if (dataset.id().equals(datasetId)) {
                return dataset;
            }
        }
        throw BizException.notFound("数据集 " + datasetId);
    }

    private void requirePatient(String owner, String patientId) {
        Patient patient = patientMapper.selectById(patientId);
        // 越权一律 404：403 等于承认"这个患者确实存在"。
        if (patient == null || !AccessControl.sameUser(owner, patient.getCreatedBy())) {
            throw BizException.notFound("患者档案");
        }
    }

    private List<KnowledgeBindingEntity> bindings(String owner, String patientId) {
        return bindingMapper.selectList(new LambdaQueryWrapper<KnowledgeBindingEntity>()
                .eq(KnowledgeBindingEntity::getOwner, owner)
                .eq(KnowledgeBindingEntity::getPatientId, patientId)
                .orderByAsc(KnowledgeBindingEntity::getCreatedAt));
    }

    private KnowledgeBindingContract.BindingView toView(KnowledgeBindingEntity entity) {
        return new KnowledgeBindingContract.BindingView(entity.getId(), entity.getPatientId(),
                entity.getDatasetId(), entity.getDatasetName(), entity.getNote(), entity.getStatus(),
                entity.getLastError(), entity.getLastCheckedAt(), entity.getCreatedAt());
    }
}
