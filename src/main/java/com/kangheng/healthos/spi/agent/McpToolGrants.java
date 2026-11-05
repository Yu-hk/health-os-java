package com.kangheng.healthos.spi.agent;

import java.util.Set;

/**
 * 「这次操作允许读谁的数据」的临时凭证登记处。
 *
 * <p>存在的理由：{@code stdio} 形态下，工具调用要经过一个子进程再转回本进程。
 * 如果子进程直接把 {@code owner}/{@code patientId} 递回来，那"患者由服务端按登录态锁定"
 * 就退化成了"由客户端声明"——任何人只要能写进那个环境变量就能读别人的档案。
 *
 * <p>换个做法：父进程在**发起操作时**按当前登录态签发一张一次性凭证（随机 ID），
 * 只把凭证交给子进程；子进程回调时带凭证，本进程查表还原出 owner/patientId。
 * 子进程因此<b>没有</b>能力指定身份，它只能使用父进程刚刚为这次操作签发的那个身份。
 *
 * <p>凭证很短命（默认与单次操作同量级），过期即失效；表在内存里，
 * 不落库、不跨进程共享——它描述的是"这一刻的授权"，重启后本就该没了。
 */
public interface McpToolGrants {

    /**
     * 签发一张凭证。
     *
     * @param scopeWires 本次授权的范围；端点据此判断工具是否越权
     * @return 凭证 ID（随机串，不编码任何身份信息）
     */
    String issue(String owner, String patientId, Set<String> scopeWires);

    /** 查凭证；未知或已过期返回 null。 */
    Grant resolve(String grantId);

    /** 一张有效凭证代表的服务端身份。 */
    record Grant(String owner, String patientId, Set<String> scopeWires) {
    }
}
