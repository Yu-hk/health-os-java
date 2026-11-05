/* 工作台只展示本环境真实查询结果；失败或未检测时显示未知。 */
(function (global) {
  "use strict";
  const { Api, UI } = global.HOS, E = UI.escapeHtml;
  HOS.router.register("/dashboard", async function (ctx) {
    HOS.app.setHead("工作台总览", '<button class="btn" id="dashboard-refresh">刷新数据</button>');
    ctx.el.innerHTML = '<div class="welcome"><div><span class="eyebrow">康衡 HEALTHOS</span><h1>把每一份资料，串成连续的健康档案</h1><p>从资料核查到报告签发、计划执行与随访，让每一步都有据可查。</p></div><a class="btn btn-primary" href="#/intake">开始资料录入 →</a></div>' +
      '<div class="grid grid-4" id="overview-stats">' + UI.spinner() + '</div>' +
      '<div class="section-heading"><h2>业务工作空间</h2><span class="muted">按流程继续工作</span></div>' +
      '<div class="grid grid-3 workflow-grid">' + [
        ["01", "资料录入与核查", "上传资料，查看抽取结果，完成基础与临床核查。", "/intake"],
        ["02", "客户健康档案", "管理指标、附件与历史版本，衔接问答和患者记忆。", "/patients"],
        ["03", "评估报告与执行计划", "编辑评估内容、签发报告，确认计划并跟踪打卡。", "/care"],
        ["04", "医学典籍资产", "上传典籍、跟踪解析，查看切片、证据及知识图谱。", "/classics"],
        ["05", "智能问答大厅", "选择患者与典籍，执行问答，核对回答和证据引用。", "/qa"],
        ["06", "患者长期记忆", "读取已发布记忆，记录随访，查看生成任务与版本。", "/memory"],
      ].map(([n, title, desc, path]) => '<a class="workflow-card" href="#' + path + '"><span class="step-number">' + n + '</span><h3>' + title + '</h3><p>' + desc + '</p><span class="workflow-enter">进入工作空间 ↗</span></a>').join("") + '</div>' +
      '<div class="section-heading"><h2>服务运行状态</h2><span class="muted" id="checked-at">正在检测…</span></div><div class="grid grid-4" id="dependencies"></div>';
    ctx.el.querySelector("#dashboard-refresh");
    document.getElementById("dashboard-refresh").onclick = () => HOS.router.resolve();
    const requests = await Promise.allSettled([Api.dependencies(), Api.get("/api/v1/patients?size=1"), Api.get("/api/v1/classics?size=1"), Api.get("/api/v1/patient-intakes?size=100"), Api.get("/api/v1/traces/overview")]);
    if (!ctx.isActive()) return;
    const value = i => requests[i].status === "fulfilled" ? requests[i].value : null;
    const patients = value(1), classics = value(2), intakes = value(3), traces = value(4);
    const pending = intakes && (intakes.items || []).filter(row => row.status === "EXTRACTED").length;
    ctx.el.querySelector("#overview-stats").innerHTML = [
      ["客户档案", patients && patients.total, "/patients", "已建档客户"],
      ["医学典籍", classics && classics.total, "/classics", "典籍资产总数"],
      ["待核查资料", pending, "/intake", "最近 100 条录入记录"],
      ["运行中任务", traces && traces.runningTraces, "/traces", "当前账号可见轨迹"],
    ].map(([label, count, path, hint]) => '<a class="stat stat-link" href="#' + path + '"><div class="label">' + label + '</div><div class="value">' + (count == null ? "未知" : E(count)) + '</div><div class="muted">' + hint + '</div></a>').join("");
    const dep = value(0);
    ctx.el.querySelector("#checked-at").textContent = dep ? "最近检测 " + new Date().toLocaleTimeString("zh-CN") : "检测失败，可刷新重试";
    ctx.el.querySelector("#dependencies").innerHTML = [["database", "数据库"], ["objectStorage", "对象存储"], ["llmProvider", "模型提供方"], ["knowledgeBase", "知识库"], ["graphStore", "图谱存储"], ["ocr", "OCR 服务"], ["embedding", "向量化"], ["tracing", "链路追踪"]].map(([key, label]) => {
      const text = dep && dep[key] != null ? String(dep[key]) : "未知";
      const color = /unavailable|failed|degraded|error/i.test(text) ? "red" : /disabled|none|off/i.test(text) ? "gray" : /ok|ready/i.test(text) ? "green" : "blue";
      return '<div class="dependency-card"><span class="status-dot ' + color + '"></span><div><b>' + label + '</b><p>' + E(text) + '</p></div></div>';
    }).join("");
  });
})(window);
