package com.kangheng.healthos.controller.routing;

import jakarta.validation.Valid;

import com.kangheng.healthos.common.AccessControl;
import com.kangheng.healthos.controller.auth.CurrentUser;
import com.kangheng.healthos.contract.routing.ModelRouteContract;
import com.kangheng.healthos.service.routing.ModelRouteService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型配置中心接口。
 *
 * <pre>
 * GET  /api/v1/system/model-routes/permissions   当前账号权限
 * GET  /api/v1/system/model-routes                全部主线的当前配置
 * GET  /api/v1/system/model-routes/{key}          单条主线的当前配置
 * PUT  /api/v1/system/model-routes/{key}          保存配置（追加新版本）
 * POST /api/v1/system/model-routes/{key}/probe    能力探测
 * GET  /api/v1/system/model-routes/{key}/versions 历史版本
 * POST /api/v1/system/model-routes/{key}/rollback 回滚到历史版本（创建新版本）
 * </pre>
 *
 * <p><b>三个必须守住的地方</b>：
 * <ol>
 *   <li>除 {@code /permissions} 外全部要求模型配置管理权限；</li>
 *   <li>所有响应带 {@code Cache-Control: no-store}——这个页面的请求体里可能带密钥，
 *       被浏览器缓存住等于把它写在磁盘上；</li>
 *   <li>响应体永远不含密钥明文，只给"是否已配置"。</li>
 * </ol>
 *
 * <p>对应原工程 {@code model_route_api.py}。
 */
@RestController
@RequestMapping("/api/v1/system/model-routes")
public class ModelRouteController {

    private final ModelRouteService modelRouteService;
    private final AccessControl accessControl;

    public ModelRouteController(ModelRouteService modelRouteService, AccessControl accessControl) {
        this.modelRouteService = modelRouteService;
        this.accessControl = accessControl;
    }

    /** 当前账号在配置中心的权限。 */
    @GetMapping("/permissions")
    public ResponseEntity<ModelRouteContract.PermissionsView> permissions(@CurrentUser String username) {
        return noStore(modelRouteService.permissions(username));
    }

    /** 全部主线的当前配置。 */
    @GetMapping
    public ResponseEntity<ModelRouteContract.RouteListView> list(@CurrentUser String username) {
        accessControl.requireModelAdmin(username);
        return noStore(modelRouteService.list(username));
    }

    /** 单条主线的当前配置。 */
    @GetMapping("/{pipelineKey}")
    public ResponseEntity<ModelRouteContract.RouteView> get(@CurrentUser String username,
                                                            @PathVariable String pipelineKey) {
        accessControl.requireModelAdmin(username);
        return noStore(modelRouteService.get(pipelineKey));
    }

    /** 保存配置。 */
    @PutMapping("/{pipelineKey}")
    public ResponseEntity<ModelRouteContract.RouteView> save(@CurrentUser String username,
                                                            @PathVariable String pipelineKey,
                                                            @Valid @RequestBody ModelRouteContract.SaveRouteRequest request) {
        accessControl.requireModelAdmin(username);
        return noStore(modelRouteService.save(pipelineKey, request, username));
    }

    /**
     * 能力探测。
     *
     * <p>探测会真的打一次供应商，所以放在 POST：它有副作用（写探测记录、刷新限流时间），
     * 不是幂等的读取。
     */
    @PostMapping("/{pipelineKey}/probe")
    public ResponseEntity<ModelRouteContract.ProbeView> probe(@CurrentUser String username,
                                                              @PathVariable String pipelineKey,
                                                              @Valid @RequestBody ModelRouteContract.ProbeRouteRequest request) {
        accessControl.requireModelAdmin(username);
        return noStore(modelRouteService.probe(request, pipelineKey));
    }

    /** 历史版本。 */
    @GetMapping("/{pipelineKey}/versions")
    public ResponseEntity<ModelRouteContract.VersionListView> versions(@CurrentUser String username,
                                                                      @PathVariable String pipelineKey,
                                                                      @RequestParam(required = false) Integer before) {
        accessControl.requireModelAdmin(username);
        return noStore(modelRouteService.history(pipelineKey, before));
    }

    /** 回滚到历史版本。 */
    @PostMapping("/{pipelineKey}/rollback")
    public ResponseEntity<ModelRouteContract.RouteView> rollback(@CurrentUser String username,
                                                                @PathVariable String pipelineKey,
                                                                @Valid @RequestBody ModelRouteContract.RollbackRouteRequest request) {
        accessControl.requireModelAdmin(username);
        return noStore(modelRouteService.rollback(pipelineKey, request, username));
    }

    /** 一律禁止缓存：这个接口的请求体可能携带模型密钥。 */
    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
