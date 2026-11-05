package com.kangheng.healthos.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.kangheng.healthos.model.auth.UserPrincipal;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * 访问控制：把"谁能看什么"集中在一处判定，不让每个 Controller 各写一套。
 *
 * <p>三条不可让步的规则：
 * <ol>
 *   <li><b>患者域数据按 owner 隔离</b>，管理员也不例外。查不到就返回 404 而不是 403——
 *       返回 403 等于告诉对方"这个 ID 确实存在"，那本身就是信息泄露；</li>
 *   <li><b>后台 worker 不做 owner 过滤</b>。outbox 任务按 ID 派发，worker 不知道调用者是谁，
 *       它处理的是系统级操作；用户态入口才带 owner。</li>
 *   <li><b>全局读必须是显式白名单</b>，没有"超级用户绕过一切"的隐式后门。</li>
 * </ol>
 *
 * <p><b>为什么构造器只收两个集合、不收配置对象</b>：本类在 {@code common}，
 * 而 {@code config} 是装配层——内核反向依赖装配层会让"公共代码"变得不可复用
 * （换个配置方式就得改内核）。取值与装配由 {@code config.InfraConfig} 负责，
 * 本类只认"名单"这一个事实。
 *
 * <p>对应原项目 {@code tracing_access.visible()} 与 {@code model_route_security.permissions()}。
 */
public class AccessControl {

    private final Set<String> adminUsers;
    private final Set<String> traceGlobalUsers;
    private final Set<String> modelAdminUsers;

    public AccessControl(List<String> adminUsers, List<String> traceGlobalUsers) {
        this(adminUsers, traceGlobalUsers, List.of());
    }

    public AccessControl(List<String> adminUsers, List<String> traceGlobalUsers,
                         List<String> modelAdminUsers) {
        this.adminUsers = copy(adminUsers);
        this.traceGlobalUsers = copy(traceGlobalUsers);
        this.modelAdminUsers = copy(modelAdminUsers);
    }

    /** 是否运维账号：可访问孤儿扫描/清理这类跨用户的维护接口。 */
    public boolean isAdmin(String user) {
        return contains(adminUsers, user);
    }

    /**
     * 是否可全局读轨迹（不限于自己发起的那些）。
     */
    public boolean isTraceGlobalReader(String user) {
        return contains(traceGlobalUsers, user);
    }

    /**
     * 是否可管理模型配置中心。
     *
     * <p>模型配置是一次"全系统生效"的改动：改错了不是一个人的数据错，
     * 是所有业务主线同时调用错模型。因此它<b>不跟运维白名单合并</b>——
     * 能清理孤儿文件的人，不该顺手改全系统的模型路由。
     */
    public boolean isModelAdmin(String user) {
        return contains(modelAdminUsers, user);
    }

    /** 模型配置管理闸门：不通过就 403。 */
    public void requireModelAdmin(String user) {
        if (!isModelAdmin(user)) {
            throw new BizException("FORBIDDEN", "此账号没有模型配置管理权限", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * 当前生效的角色，权限由高到低。
     *
     * <p><b>角色不落令牌、只在这里现算</b>，所以把某人从运维名单里移走是立即生效的：
     * 如果他手上那个 8 小时有效的令牌里还写着 ADMIN，撤销权限就得等令牌过期——那是真实漏洞，
     * 不是理论问题。
     *
     * <p>任何登录用户都有 {@code USER}；命中轨迹白名单追加 {@code TRACE_READER}；
     * 命中运维白名单追加 {@code ADMIN}；命中模型配置白名单追加 {@code MODEL_ADMIN}（不互斥）。
     */
    public List<String> rolesOf(String user) {
        if (!StringUtils.hasText(user)) {
            return List.of();
        }
        List<String> roles = new ArrayList<>(4);
        if (isAdmin(user)) {
            roles.add(UserPrincipal.ROLE_ADMIN);
        }
        if (isTraceGlobalReader(user)) {
            roles.add(UserPrincipal.ROLE_TRACE_READER);
        }
        if (isModelAdmin(user)) {
            roles.add(UserPrincipal.ROLE_MODEL_ADMIN);
        }
        roles.add(UserPrincipal.ROLE_USER);
        return List.copyOf(roles);
    }

    /**
     * 能否读这条归属他人的数据。
     *
     * @param owner 数据归属者；为空表示数据还没有归属（系统生成的），只有运维账号能看
     */
    public boolean canRead(String user, String owner) {
        if (sameUser(user, owner)) {
            return true;
        }
        return isAdmin(user);
    }

    /** 运维闸门：不通过就 403。 */
    public void requireAdmin(String user) {
        if (!isAdmin(user)) {
            throw new BizException("FORBIDDEN", "此账号没有运维权限", HttpStatus.FORBIDDEN);
        }
    }

    /** 归属者与访问者是否是同一个人。 */
    public static boolean sameUser(String user, String owner) {
        return StringUtils.hasText(user) && StringUtils.hasText(owner) && user.equals(owner);
    }

    /** 复制成不可变集合：配置对象后续被改不该影响已经建好的判定。 */
    private static Set<String> copy(List<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(new LinkedHashSet<>(source));
    }

    private static boolean contains(Set<String> allowed, String user) {
        return StringUtils.hasText(user) && allowed.contains(user);
    }
}
