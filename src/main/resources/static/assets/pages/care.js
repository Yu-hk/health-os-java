/* 健康管理：报告/计划列表、生成报告、文档详情（分节编辑 / 签发 / 确认 / 打卡 / 导出 / 修订 / 审计）。 */
(function (global) {
  "use strict";
  const HOS = global.HOS, Api = HOS.Api, UI = HOS.UI;

  async function render(ctx) {
    const id = ctx.params.documentId;
    if (id) return renderDetail(ctx, id);
    return renderList(ctx);
  }

  async function renderList(ctx) {
    const el = ctx.el;
    HOS.app.setHead("健康管理",
      '<button class="btn btn-primary" id="gen-report">＋ 生成报告</button>');
    el.innerHTML =
      '<div class="card"><h3>免责声明</h3><div class="muted" id="disc">加载中…</div></div>' +
      '<div class="tabs" id="tabs"><a data-t="reports" class="active">健康评估报告</a><a data-t="plans">四周执行计划</a></div>' +
      '<div id="tab-body">' + UI.spinner() + "</div>";
    document.getElementById("gen-report").onclick = openGenerate;

    try {
      const cat = await Api.get("/api/v1/care/catalog");
      document.getElementById("disc").textContent = cat.disclaimer || "";
    } catch (e) { /* 可选 */ }

    const tabBody = document.getElementById("tab-body");
    async function show(t) {
      document.querySelectorAll("#tabs a").forEach(a => a.classList.toggle("active", a.dataset.t === t));
      tabBody.innerHTML = UI.spinner();
      try {
        const list = t === "reports"
          ? await Api.get("/api/v1/care/reports?limit=100")
          : await Api.get("/api/v1/care/plans?limit=100");
        tabBody.innerHTML = '<div class="card"><h3>' + (t === "reports" ? "报告" : "计划") + ' <span class="sub">' + list.length + " 份</span></h3>" +
          UI.table([
            { key: "id", label: "文档 ID", cls: "code" },
            { key: "patientId", label: "患者", cls: "code", render: v => v ? '<a href="#/patients/' + v + '">' + UI.escapeHtml(v) + "</a>" : "-" },
            { key: "kind", label: "类型", render: v => UI.pill(v) },
            { key: "status", label: "状态", render: v => UI.pill(v) },
            { key: "revision", label: "版本" },
            { key: "startDate", label: "起始日" },
            { key: "op", label: "操作", render: (v, r) => '<a class="btn btn-sm" href="#/care/' + r.id + '">打开</a>' },
          ], list, "暂无文档") + "</div>";
      } catch (e) { UI.toastErr(e); }
    }
    document.querySelectorAll("#tabs a").forEach(a => a.onclick = () => show(a.dataset.t));
    show("reports");
  }

  function openGenerate() {
    const fields = [
      { name: "examId", label: "来源体检录入单 ID", required: true, placeholder: "来自资料录入的 examId" },
      { name: "idempotencyKey", label: "幂等键（可选）", placeholder: "同键同输入返回同一份" },
    ];
    const body = UI.formFields(fields);
    UI.modal({
      title: "生成健康评估报告",
      body: body,
      actions: [
        { label: "取消" },
        { label: "生成（异步）", primary: true, onClick: async () => {
          const f = UI.readForm(body);
          try { const r = await Api.post("/api/v1/care/reports", { examId: f.examId, idempotencyKey: f.idempotencyKey || null }); UI.toast("已受理：" + r.status, "ok"); HOS.router.go("/care/" + r.documentId); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  async function renderDetail(ctx, documentId) {
    const el = ctx.el;
    el.innerHTML = UI.spinner();
    let d;
    try { d = await Api.get("/api/v1/care/documents/" + documentId); }
    catch (e) { UI.toastErr(e); el.innerHTML = '<div class="empty">加载失败</div>'; return; }

    HOS.app.setHead((d.kind === "REPORT" ? "报告" : "计划") + " · " + documentId, "");

    const sig = d.signature;
    const isReport = d.kind === "REPORT";
    const canSign = isReport && d.status !== "SIGNED";
    const canConfirmPlan = !isReport && d.status !== "ACTIVE";
    const canRevise = d.status === "SIGNED";

    const headActions =
      '<button class="btn" id="back">← 返回</button>' +
      (canSign ? '<button class="btn btn-primary" id="sign">签发</button>' : "") +
      (canConfirmPlan ? '<button class="btn btn-primary" id="confirm-plan">确认生效</button>' : "") +
      (canRevise ? '<button class="btn" id="revise">修订</button>' : "") +
      '<button class="btn" id="exp-json">导出 JSON</button>' +
      '<button class="btn" id="exp-pdf">导出 PDF</button>';

    el.innerHTML =
      '<div class="toolbar">' + headActions + "</div>" +
      '<div class="card"><h3>文档信息</h3><dl class="kv">' +
        "<dt>类型</dt><dd>" + UI.pill(d.kind) + "</dd>" +
        "<dt>患者</dt><dd>" + (d.patientId ? '<a href="#/patients/' + d.patientId + '">' + UI.escapeHtml(d.patientId) + "</a>" : "-") + "</dd>" +
        "<dt>状态</dt><dd>" + UI.pill(d.status) + "</dd>" +
        "<dt>版本</dt><dd>" + d.revision + (d.signedRevision ? "（已签 " + d.signedRevision + "）" : "") + "</dd>" +
        "<dt>起始日</dt><dd>" + UI.escapeHtml(d.startDate) + "</dd>" +
        "<dt>是否完整</dt><dd>" + (d.complete ? "是" : "否") + "</dd>" +
        (sig ? "<dt>签发人</dt><dd>" + UI.escapeHtml(sig.signer) + "</dd><dt>签发时间</dt><dd>" + UI.date(sig.signedAt) + "</dd><dt>内容哈希</dt><dd class=\"code\">" + UI.escapeHtml((sig.contentHash || "").slice(0, 20)) + "…</dd>" : "") +
      "</dl>" + (d.disclaimer ? '<div class="muted" style="margin-top:10px;font-size:12px;">' + UI.escapeHtml(d.disclaimer) + "</div>" : "") + "</div>" +
      '<div class="tabs" id="tabs"><a data-t="content" class="active">内容</a><a data-t="revisions">修订历史</a><a data-t="audits">审计流水</a></div>' +
      '<div id="tab-body"></div>';

    document.getElementById("back").onclick = () => HOS.router.go("/care");
    const signBtn = document.getElementById("sign");
    if (signBtn) signBtn.onclick = () => openSign(d);
    const cpBtn = document.getElementById("confirm-plan");
    if (cpBtn) cpBtn.onclick = async () => {
      try { await Api.post("/api/v1/care/documents/" + documentId + "/confirm", { expectedRevision: d.revision }); UI.toast("计划已生效", "ok"); HOS.router.go("/care/" + documentId); }
      catch (e) { UI.toastErr(e); }
    };
    const revBtn = document.getElementById("revise");
    if (revBtn) revBtn.onclick = async () => {
      try { const r = await Api.post("/api/v1/care/documents/" + documentId + "/revise", { expectedRevision: d.revision }); UI.toast("已生成修订草稿", "ok"); HOS.router.go("/care/" + r.documentId); }
      catch (e) { UI.toastErr(e); }
    };
    document.getElementById("exp-json").onclick = () => Api.download("/api/v1/care/documents/" + documentId + "/export", documentId + ".json");
    document.getElementById("exp-pdf").onclick = () => Api.download("/api/v1/care/documents/" + documentId + "/pdf", documentId + ".pdf");

    const tabBody = document.getElementById("tab-body");
    function showTab(t) {
      document.querySelectorAll("#tabs a").forEach(a => a.classList.toggle("active", a.dataset.t === t));
      if (t === "content") renderContent(tabBody, d);
      else if (t === "revisions") renderRevisions(tabBody, documentId);
      else if (t === "audits") renderAudits(tabBody, documentId);
    }
    document.querySelectorAll("#tabs a").forEach(a => a.onclick = () => showTab(a.dataset.t));
    showTab("content");
  }

  function renderContent(mount, d) {
    const sections = d.sections || [];
    const secHtml = sections.map(s =>
      '<div class="card"><div style="display:flex;justify-content:space-between;align-items:center;"><h3 style="margin:0;">' + UI.escapeHtml(s.title || s.key) + " " + (s.present ? "" : UI.pill("缺失")) + '</h3><button class="btn btn-sm" data-sec="' + s.key + '">编辑</button></div>' +
      '<div class="wrap" style="margin-top:8px;white-space:pre-wrap;">' + (s.text ? UI.escapeHtml(s.text) : '<span class="muted">（未填写）</span>') + "</div>" +
      (s.metricRefs && s.metricRefs.length ? '<div class="muted" style="margin-top:6px;font-size:12px;">引用指标：' + s.metricRefs.map(UI.escapeHtml).join("、") + "</div>" : "") +
      "</div>"
    ).join("");

    const tasks = d.tasks || [];
    const taskHtml = tasks.length ? '<div class="card"><h3>执行任务（打卡）</h3>' + UI.table([
      { key: "dayIndex", label: "第几周" },
      { key: "title", label: "标题" },
      { key: "content", label: "内容", cls: "wrap" },
      { key: "category", label: "类别", render: v => UI.pill(v) },
      { key: "completed", label: "状态", render: v => v ? UI.pill("已完成") : UI.pill("待完成") },
      { key: "op", label: "操作", render: (v, r) => '<button class="btn btn-sm" data-check="' + r.taskId + '">' + (r.completed ? "标记未完成" : "打卡完成") + "</button>" },
    ], tasks) + "</div>" : "";

    mount.innerHTML = secHtml + taskHtml;
    mount.querySelectorAll("[data-sec]").forEach(b => b.onclick = () => openEditSection(d, b.dataset.sec));
    mount.querySelectorAll("[data-check]").forEach(b => b.onclick = async () => {
      const task = tasks.find(t => t.taskId === b.dataset.check);
      try { await Api.put("/api/v1/care/documents/" + d.id + "/checkins/" + b.dataset.check, { completed: !task.completed, expectedVersion: (task.version || d.revision) }); UI.toast("已更新打卡", "ok"); const nd = await Api.get("/api/v1/care/documents/" + d.id); renderContent(mount, nd); }
      catch (e) { UI.toastErr(e); }
    });
  }

  function openEditSection(d, key) {
    const sec = (d.sections || []).find(s => s.key === key) || {};
    const fields = [
      { name: "text", label: "分节正文（10–8000 字）", type: "textarea", value: sec.text || "" },
      { name: "metricRefs", label: "引用指标代码（逗号分隔，可选）", value: (sec.metricRefs || []).join(", ") },
    ];
    const body = UI.formFields(fields);
    UI.modal({
      title: "编辑分节：" + (sec.title || key) + "（版本 " + d.revision + "）",
      body: body,
      actions: [
        { label: "取消" },
        { label: "保存", primary: true, onClick: async () => {
          const f = UI.readForm(body);
          const refs = (f.metricRefs || "").split(",").map(s => s.trim()).filter(Boolean);
          try { await Api.patch("/api/v1/care/documents/" + d.id + "/sections/" + key, { expectedRevision: d.revision, text: f.text, metricRefs: refs }); UI.toast("分节已保存", "ok"); HOS.router.go("/care/" + d.id); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  function openSign(d) {
    const fields = [
      { name: "expectedRevision", label: "文档版本（乐观锁）", value: d.revision },
      { name: "reviewComment", label: "审核意见（≥5 字）", type: "textarea", placeholder: "如：内容核对无误，可以签发" },
      { name: "startDate", label: "计划起始日期（签发时同步生成计划）", type: "date", placeholder: "可空" },
    ];
    const body = UI.formFields(fields);
    UI.modal({
      title: "签发报告",
      body: body,
      actions: [
        { label: "取消" },
        { label: "签发", primary: true, onClick: async () => {
          const f = UI.readForm(body);
          const req = { expectedRevision: Number(f.expectedRevision), reviewComment: f.reviewComment, startDate: f.startDate || null };
          try { await Api.post("/api/v1/care/documents/" + d.id + "/sign", req); UI.toast("已签发", "ok"); HOS.router.go("/care/" + d.id); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  async function renderRevisions(mount, documentId) {
    mount.innerHTML = UI.spinner();
    try {
      const list = await Api.get("/api/v1/care/documents/" + documentId + "/revisions");
      mount.innerHTML = '<div class="card"><h3>修订历史 <span class="sub">' + list.length + " 条</span></h3>" +
        UI.table([
          { key: "revision", label: "版本" },
          { key: "actor", label: "操作人" },
          { key: "reason", label: "原因" },
          { key: "createdAt", label: "时间", render: v => UI.date(v) },
        ], list, "暂无修订") + "</div>";
    } catch (e) { UI.toastErr(e); }
  }

  async function renderAudits(mount, documentId) {
    mount.innerHTML = UI.spinner();
    try {
      const list = await Api.get("/api/v1/care/documents/" + documentId + "/audits");
      mount.innerHTML = '<div class="card"><h3>审计流水 <span class="sub">' + list.length + " 条</span></h3>" +
        UI.table([
          { key: "revision", label: "版本" },
          { key: "actor", label: "操作人" },
          { key: "action", label: "动作", render: v => UI.pill(v) },
          { key: "reason", label: "原因" },
          { key: "changedSection", label: "变更分节" },
          { key: "createdAt", label: "时间", render: v => UI.date(v) },
        ], list, "暂无审计") + "</div>";
    } catch (e) { UI.toastErr(e); }
  }

  HOS.router.register("/care", render);
  HOS.router.register("/care/:documentId", render);
})(window);
