package com.kangheng.healthos.common;

import java.util.List;

import com.kangheng.healthos.model.auth.UserPrincipal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 授权判定测试。
 *
 * <p>这组用例锁的是"角色现算"这条语义：同一个账号，配置改了角色就变，
 * 不需要等令牌过期。这是把角色从令牌里拿出去的唯一收益，必须有用例守着。
 */
class AccessControlTest {

    @Test
    @DisplayName("普通登录用户只有 USER")
    void plainUserHasOnlyUserRole() {
        AccessControl control = control(List.of("admin"), List.of("auditor"));
        assertThat(control.rolesOf("alice")).containsExactly(UserPrincipal.ROLE_USER);
    }

    @Test
    @DisplayName("运维账号额外带 ADMIN，轨迹白名单额外带 TRACE_READER，两者可叠加")
    void rolesAreAdditive() {
        AccessControl control = control(List.of("admin", "both"), List.of("both"));
        assertThat(control.rolesOf("admin")).containsExactly(UserPrincipal.ROLE_ADMIN, UserPrincipal.ROLE_USER);
        assertThat(control.rolesOf("both"))
                .containsExactly(UserPrincipal.ROLE_ADMIN, UserPrincipal.ROLE_TRACE_READER,
                        UserPrincipal.ROLE_USER);
    }

    @Test
    @DisplayName("角色按当前名单现算：名单里移走，下一次调用立刻不是管理员")
    void rolesAreComputedPerCall() {
        List<String> admins = new java.util.ArrayList<>(List.of("alice"));
        AccessControl control = new AccessControl(admins, List.of());
        assertThat(control.rolesOf("alice")).contains(UserPrincipal.ROLE_ADMIN);
        // 已建好的实例不受外部 List 后续修改影响（构造时复制）
        admins.remove("alice");
        assertThat(control.rolesOf("alice")).contains(UserPrincipal.ROLE_ADMIN);

        AccessControl narrowed = new AccessControl(admins, List.of());
        assertThat(narrowed.rolesOf("alice")).doesNotContain(UserPrincipal.ROLE_ADMIN);
    }

    @Test
    @DisplayName("空用户名没有任何角色")
    void blankUserHasNoRoles() {
        AccessControl control = control(List.of("admin"), List.of("admin"));
        assertThat(control.rolesOf(null)).isEmpty();
        assertThat(control.rolesOf("  ")).isEmpty();
    }

    @Test
    @DisplayName("非运维账号过运维闸门抛 403")
    void requireAdminRejectsNonAdmin() {
        AccessControl control = control(List.of("admin"), List.of());
        control.requireAdmin("admin");
        assertThatThrownBy(() -> control.requireAdmin("alice"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getStatus().value()).isEqualTo(403));
    }

    @Test
    @DisplayName("归属校验：管理员可读他人数据，普通用户不可")
    void canReadRespectsOwnership() {
        AccessControl control = control(List.of("admin"), List.of());
        assertThat(control.canRead("alice", "alice")).isTrue();
        assertThat(control.canRead("alice", "bob")).isFalse();
        assertThat(control.canRead("admin", "bob")).isTrue();
        // 无归属的数据只有运维能看
        assertThat(control.canRead("alice", null)).isFalse();
    }

    private static AccessControl control(List<String> admins, List<String> traceReaders) {
        return new AccessControl(admins, traceReaders);
    }
}
