package com.kangheng.healthos.controller.tracing;

import java.time.OffsetDateTime;
import java.util.Map;

import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.common.PageResult;
import com.kangheng.healthos.service.tracing.TraceExporter;
import com.kangheng.healthos.service.tracing.TraceQueryService;
import com.kangheng.healthos.service.tracing.TraceRetentionService;
import com.kangheng.healthos.contract.tracing.TracingContract;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 观测接口。所有接口都要登录（{@code @CurrentUser} 触发会话校验）。
 *
 * <p>读写分离：查询与导出是只读；唯一会改数据的是 {@code maintenance/sweep}，
 * 且默认 {@code dryRun=true}——清理这种不可逆动作不该一个手滑就执行。
 */
@RestController
@RequestMapping("/api/v1/traces")
public class TracingController {

    private final TraceQueryService queryService;
    private final TraceRetentionService retentionService;
    private final TraceExporter exporter;
    private final AccessControl accessControl;

    public TracingController(TraceQueryService queryService,
                             TraceRetentionService retentionService,
                             TraceExporter exporter,
                             AccessControl accessControl) {
        this.queryService = queryService;
        this.retentionService = retentionService;
        this.exporter = exporter;
        this.accessControl = accessControl;
    }

    @GetMapping
    public PageResult<TracingContract.TraceSummary> list(
            @CurrentUser String username,
            @RequestParam(value = "businessType", required = false) String businessType,
            @RequestParam(value = "businessId", required = false) String businessId,
            @RequestParam(value = "owner", required = false) String owner,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return queryService.list(businessType, businessId, owner, status, from, to, page, size, username);
    }

    @GetMapping("/overview")
    public TracingContract.Overview overview(
            @CurrentUser String username,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return queryService.overview(from, to, username);
    }

    /** 保留策略的清理动作会真的删数据，限定运维账号。 */
    @GetMapping("/maintenance")
    public TracingContract.MaintenanceView maintenance(@CurrentUser String username) {
        accessControl.requireAdmin(username);
        return retentionService.maintenance();
    }

    @PostMapping("/maintenance/sweep")
    public TracingContract.SweepResult sweep(
            @CurrentUser String username,
            @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        accessControl.requireAdmin(username);
        return retentionService.sweep(dryRun, OffsetDateTime.now());
    }

    @GetMapping("/{traceId}")
    public TracingContract.TraceDetail detail(@CurrentUser String username,
                                              @PathVariable String traceId) {
        return queryService.detail(traceId, username);
    }

    @GetMapping("/{traceId}/export")
    public Map<String, Object> export(@CurrentUser String username, @PathVariable String traceId) {
        return exporter.toOtlp(queryService.detail(traceId, username));
    }
}
