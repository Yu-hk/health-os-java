package com.kangheng.healthos.service.routing;

import java.time.OffsetDateTime;
import java.util.function.Supplier;

import com.kangheng.healthos.common.Ids;
import com.kangheng.healthos.common.Json;
import com.kangheng.healthos.mapper.routing.ModelRouteSnapshotMapper;
import com.kangheng.healthos.model.routing.ModelRouteSnapshot;
import com.kangheng.healthos.spi.routing.ModelRouteResolver;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 快照落库单独开一个事务。
 *
 * <p>这是个容易踩的坑：如果写快照和跑业务在同一个事务里，业务失败回滚时快照也一起没了。
 * 于是每次重试都重新冻结一次——管理员恰好在两次重试之间改了模型，同一个任务就会
 * 前后用两份配置，而"冻结"本来就是为了防这个。
 *
 * <p>{@code REQUIRES_NEW} 让"这个业务实例已经绑定了哪一版配置"先独立提交，
 * 之后业务成功与否都不影响它。
 */
@Service
public class ModelRouteSnapshotStore {

    private final ModelRouteSnapshotMapper snapshotMapper;

    public ModelRouteSnapshotStore(ModelRouteSnapshotMapper snapshotMapper) {
        this.snapshotMapper = snapshotMapper;
    }

    /**
     * 读已有快照；没有就冻结一份并落库。
     *
     * @param freezer 冻结当前配置的动作，由解析器提供（本类不认识配置中心）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ModelRouteResolver.RouteSnapshot loadOrFreeze(String bizType, String bizId,
                                                         String pipelineKey,
                                                         Supplier<ModelRouteResolver.RouteSnapshot> freezer) {
        String id = Ids.stableId("mrs", bizType, bizId);
        ModelRouteSnapshot row = snapshotMapper.selectById(id);
        if (row != null) {
            return Json.read(row.getSnapshotJson(), ModelRouteResolver.RouteSnapshot.class);
        }
        ModelRouteResolver.RouteSnapshot snapshot = freezer.get();
        ModelRouteSnapshot fresh = new ModelRouteSnapshot();
        fresh.setId(id);
        fresh.setBizType(bizType);
        fresh.setBizId(bizId);
        fresh.setPipelineKey(pipelineKey);
        fresh.setSnapshotJson(Json.write(snapshot));
        fresh.setCreatedAt(OffsetDateTime.now());
        try {
            snapshotMapper.insert(fresh);
        } catch (DuplicateKeyException ignored) {
            // 同一个业务实例被两个 worker 同时拿到：以先落库的那份为准，
            // 不能各自冻结一版，否则一个任务会跑出两种配置。
            ModelRouteSnapshot winner = snapshotMapper.selectById(id);
            if (winner != null) {
                return Json.read(winner.getSnapshotJson(), ModelRouteResolver.RouteSnapshot.class);
            }
        }
        return snapshot;
    }
}
