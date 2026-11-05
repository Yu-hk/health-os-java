package com.kangheng.healthos.controller.routing;

import java.util.Map;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.service.routing.ModelUsageService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型成本看板。
 *
 * <pre>
 * GET /api/v1/model-usage/summary?days=7
 * </pre>
 *
 * <p><b>为什么只给运维账号看</b>：成本是跨主线的全局数字，按 owner 隔离没法表达
 * "今天整体花了多少"。所以这里走 {@link AccessControl#requireAdmin} 白名单，
 * 而不是像患者域接口那样按登录人过滤——给它加个 owner 条件反而会得到一个
 * "每个人只看自己那部分、谁也不知道总量"的假看板。
 *
 * <p>响应里<b>不含</b>任何调用内容：只有主线条数、token 数、金额与"没配单价的次数"。
 * 用量明细里那一列 {@code trace_id} 也不出现在看板上——它能把花费连回具体那次问答，
 * 而那属于轨迹权限，不是成本权限。
 */
@RestController
@RequestMapping("/api/v1/model-usage")
public class ModelUsageController {

    /** 看板最多回看 90 天：明细没有保留策略，让参数无限大会变成一次全表聚合。 */
    private static final int MAX_DAYS = 90;

    private final ModelUsageService usageService;
    private final AccessControl accessControl;

    public ModelUsageController(ModelUsageService usageService, AccessControl accessControl) {
        this.usageService = usageService;
        this.accessControl = accessControl;
    }

    /** 最近 {@code days} 天的用量：按模型与按主线两个维度。 */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@CurrentUser String username,
                                                       @RequestParam(defaultValue = "7") int days) {
        accessControl.requireAdmin(username);
        int window = days <= 0 ? 7 : Math.min(days, MAX_DAYS);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(usageService.summary(window));
    }
}
