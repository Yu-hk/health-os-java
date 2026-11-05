/* 患者长效记忆：初始化、当前版本(Markdown+哈希)、版本链、随访原文、刷新、异步任务。 */
(function (global) {
  "use strict";
  const HOS = global.HOS, Api = HOS.Api, UI = HOS.UI;
  const CATEGORIES = ["diet", "symptoms", "exercise", "lifestyle"];

  function genOpId() {
    return "op_" + Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);
  }

  async function render(ctx) {
    const id = ctx.params.patientId;
    if (id) return renderDetail(ctx, id);
    return renderPicker(ctx);
  }

  function renderPicker(ctx) {
    const el = ctx.el;
    HOS.app.setHead("患者记忆");
    el.innerHTML = '<div class="card"><h3>选择患者</h3><div class="row"><input id="mem-pid" placeholder="输入患者 ID" /><button class="btn btn-primary" id="mem-go" style="flex:0 0 auto;">打开记忆</button></div>' +
      '<div class="muted" style="margin-top:10px;">记忆域按患者隔离，需先建档并至少有一个已签发的照护报告，才能采集事实。</div></div>';
    el.querySelector("#mem-go").onclick = () => {
      const v = el.querySelector("#mem-pid").value.trim();
      if (v) HOS.router.go("/memory/" + v);
    };
  }

  async function renderDetail(ctx, patientId) {
    const el = ctx.el;
    el.innerHTML = UI.spinner();
    let current = null;
    try { current = await Api.get("/api/v1/patients/" + patientId + "/memory"); }
    catch (e) {
      // 尚未初始化：409/404 都当作"需要先初始化"
      current = null;
    }

    HOS.app.setHead("记忆 · " + patientId,
      '<button class="btn" id="back">← 患者</button>' +
      '<button class="btn" id="init">初始化记忆</button>' +
      '<button class="btn btn-primary" id="refresh">刷新记忆</button>' +
      '<button class="btn btn-primary" id="followup">提交随访</button>');

    const curId = current ? current.id : null;
    el.innerHTML =
      '<div class="toolbar">' +
        '<button class="btn" id="back2">← 选择患者</button>' +
        '<button class="btn" id="init2">初始化记忆</button>' +
        '<button class="btn btn-primary" id="refresh2">刷新记忆</button>' +
        '<button class="btn btn-primary" id="followup2">提交随访</button>' +
      "</div>" +
      '<div class="card"><h3>当前版本</h3><div id="mem-current">' + (current ? renderVersionSummary(current) : '<span class="muted">尚未初始化。点击右上角"初始化记忆"。</span>') + "</div></div>" +
      '<div class="tabs" id="tabs"><a data-t="versions" class="active">版本历史</a><a data-t="followups">随访记录</a><a data-t="jobs">运行任务</a></div>' +
      '<div id="tab-body"></div>';

    function goInit() {
      UI.modal({
        title: "初始化记忆", body: '<div class="muted">将为患者 ' + UI.escapeHtml(patientId) + ' 采集冻结来源事实并生成第一版记忆。仅从已签发报告、已确认录入等冻结来源取事实。</div>',
        actions: [
          { label: "取消" },
          { label: "初始化", primary: true, onClick: async () => {
            try { const v = await Api.post("/api/v1/patients/" + patientId + "/memory/initialize", {}); UI.toast("已初始化，版本 " + v.version, "ok"); HOS.router.go("/memory/" + patientId); return true; }
            catch (e) { UI.toastErr(e); return false; }
          } },
        ],
      });
    }
    el.querySelector("#init").onclick = el.querySelector("#init2").onclick = goInit;
    el.querySelector("#refresh").onclick = el.querySelector("#refresh2").onclick = () => openRefresh(patientId, curId);
    el.querySelector("#followup").onclick = el.querySelector("#followup2").onclick = () => openFollowup(patientId, curId);
    el.querySelector("#back").onclick = el.querySelector("#back2").onclick = () => HOS.router.go("/memory");

    const tabBody = document.getElementById("tab-body");
    function showTab(t) {
      document.querySelectorAll("#tabs a").forEach(a => a.classList.toggle("active", a.dataset.t === t));
      if (t === "versions") renderVersions(tabBody, patientId, curId);
      else if (t === "followups") renderFollowups(tabBody, patientId);
      else if (t === "jobs") renderJobs(tabBody, patientId);
    }
    document.querySelectorAll("#tabs a").forEach(a => a.onclick = () => showTab(a.dataset.t));
    showTab("versions");
  }

  function renderVersionSummary(v) {
    return '<dl class="kv">' +
      "<dt>版本</dt><dd>" + v.version + "</dd>" +
      "<dt>内容哈希</dt><dd class=\"code\">" + UI.escapeHtml(v.contentHash || "") + "</dd>" +
      "<dt>来源哈希</dt><dd class=\"code\">" + UI.escapeHtml(v.sourceHash || "") + "</dd>" +
      "<dt>来源数</dt><dd>" + v.sourceCount + "</dd>" +
      "<dt>采样时间</dt><dd>" + UI.date(v.sampledAt) + "</dd>" +
      (v.markdown ? "</dl><div class=\"pre\" style=\"background:#f8fafc;color:#1f2933;margin-top:10px;\">" + UI.escapeHtml(v.markdown) + "</div>" : "</dl>");
  }

  function openRefresh(patientId, curId) {
    const fields = [{ name: "operationId", label: "幂等键 operationId", value: genOpId() }]; // expectedMemoryId 隐藏
    const body = UI.formFields(fields);
    UI.modal({
      title: "刷新记忆（新增一版）",
      body: body,
      actions: [
        { label: "取消" },
        { label: "刷新（异步）", primary: true, onClick: async () => {
          const f = UI.readForm(body);
          try { const a = await Api.post("/api/v1/patients/" + patientId + "/memory/refresh", { operationId: f.operationId, expectedMemoryId: curId || null }); UI.toast("已受理：" + a.job.status, "ok"); HOS.router.go("/memory/" + patientId); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  function openFollowup(patientId, curId) {
    const wrap = document.createElement("div");
    wrap.innerHTML = '<label class="field"><span>幂等键 operationId</span><input id="fu-op" value="' + genOpId() + '" /></label>' +
      '<label class="field"><span>发生时间</span><input id="fu-time" type="datetime-local" /></label>' +
      '<div id="fu-entries"></div>' +
      '<button class="btn btn-sm" id="fu-add" style="margin-top:6px;">＋ 添加一条随访</button>';
    const entriesEl = wrap.querySelector("#fu-entries");
    function addEntry() {
      const row = document.createElement("div");
      row.style.cssText = "border:1px solid var(--border);border-radius:8px;padding:10px;margin-bottom:8px;";
      row.innerHTML = '<div class="row"><select class="fu-cat">' + CATEGORIES.map(c => '<option value="' + c + '">' + c + "</option>").join("") + '</select><button class="btn btn-sm btn-danger fu-del" style="flex:0 0 auto;">删除</button></div>' +
        '<textarea class="fu-content" placeholder="患者自述原文（不改写、不摘要）"></textarea>';
      row.querySelector(".fu-del").onclick = () => row.remove();
      entriesEl.appendChild(row);
    }
    addEntry();
    wrap.querySelector("#fu-add").onclick = addEntry;
    UI.modal({
      title: "提交随访（患者自述原文）",
      body: wrap,
      actions: [
        { label: "取消" },
        { label: "提交（异步）", primary: true, onClick: async () => {
          const op = wrap.querySelector("#fu-op").value.trim();
          const time = wrap.querySelector("#fu-time").value;
          if (!op) { UI.toast("请填写 operationId", "warn"); return false; }
          if (!time) { UI.toast("请选择发生时间", "warn"); return false; }
          const entries = Array.from(entriesEl.querySelectorAll(".fu-cat")).map((sel, i) => ({
            category: sel.value, content: entriesEl.querySelectorAll(".fu-content")[i].value.trim(),
          })).filter(e => e.content);
          if (!entries.length) { UI.toast("至少填写一条随访内容", "warn"); return false; }
          const occurredAt = new Date(time).toISOString();
          try {
            const a = await Api.post("/api/v1/patients/" + patientId + "/memory/followups", { operationId: op, expectedMemoryId: curId || null, occurredAt: occurredAt, entries: entries });
            UI.toast("随访已受理：" + a.job.status, "ok"); HOS.router.go("/memory/" + patientId); return true;
          } catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  async function renderVersions(mount, patientId, curId) {
    mount.innerHTML = UI.spinner();
    try {
      const page = await Api.get("/api/v1/patients/" + patientId + "/memory/versions?size=50");
      const rows = (page && page.items) || page || [];
      mount.innerHTML = '<div class="card"><h3>版本历史 <span class="sub">' + rows.length + " 版</span></h3>" +
        UI.table([
          { key: "version", label: "版本" },
          { key: "contentHash", label: "内容哈希", cls: "code", render: v => UI.escapeHtml((v || "").slice(0, 16)) + "…" },
          { key: "sourceCount", label: "来源数" },
          { key: "sampledAt", label: "采样时间", render: v => UI.date(v) },
          { key: "op", label: "操作", render: (v, r) => '<a class="btn btn-sm" href="#/memory/' + patientId + '/v/' + r.id + '">查看/导出</a>' },
        ], rows, "暂无版本") + "</div>";
    } catch (e) { UI.toastErr(e); }
  }

  async function renderFollowups(mount, patientId) {
    mount.innerHTML = UI.spinner();
    try {
      const page = await Api.get("/api/v1/patients/" + patientId + "/memory/followups?size=50");
      const rows = (page && page.items) || page || [];
      mount.innerHTML = '<div class="card"><h3>随访记录 <span class="sub">' + rows.length + " 条</span></h3>" +
        (rows.length ? rows.map(f =>
          '<div class="card" style="margin-bottom:10px;"><div><b>' + UI.date(f.occurredAt) + "</b> " + UI.pill(f.origin || "") + " · 操作人 " + UI.escapeHtml(f.actor || "") + "</div>" +
          (f.entries || []).map(e => '<div style="margin-top:6px;border-left:3px solid var(--primary);padding-left:8px;">[' + UI.escapeHtml(e.category) + "] " + UI.escapeHtml(e.content) + "</div>").join("") +
          "</div>"
        ).join("") : '<div class="empty">暂无随访</div>') + "</div>";
    } catch (e) { UI.toastErr(e); }
  }

  async function renderJobs(mount, patientId) {
    mount.innerHTML = '<div class="card"><h3>运行任务</h3><div class="row"><input id="job-id" placeholder="输入 jobId 查看" /><button class="btn btn-primary" id="job-go" style="flex:0 0 auto;">查询</button></div><div id="job-body" style="margin-top:12px;"></div></div>';
    mount.querySelector("#job-go").onclick = async () => {
      const jid = mount.querySelector("#job-id").value.trim();
      if (!jid) return;
      const body = mount.querySelector("#job-body");
      body.innerHTML = UI.spinner();
      try {
        const j = await Api.get("/api/v1/patients/" + patientId + "/memory/jobs/" + jid);
        body.innerHTML = '<dl class="kv">' +
          "<dt>任务 ID</dt><dd class=\"code\">" + UI.escapeHtml(j.id) + "</dd>" +
          "<dt>类型</dt><dd>" + UI.pill(j.kind) + "</dd>" +
          "<dt>状态</dt><dd>" + UI.pill(j.status) + "</dd>" +
          "<dt>尝试次数</dt><dd>" + j.attemptCount + "</dd>" +
          "<dt>结果版本</dt><dd>" + UI.escapeHtml(j.resultMemoryId || "-") + "</dd>" +
          (j.error ? "<dt>错误</dt><dd class=\"muted\">" + UI.escapeHtml(j.error) + "</dd>" : "") +
          "</dl>" +
          (["QUEUED", "RUNNING"].includes(j.status) ? '<div style="margin-top:10px;"><button class="btn btn-sm" id="job-cancel">取消</button> <button class="btn btn-sm" id="job-retry">重试</button></div>' : "");
        const cb = body.querySelector("#job-cancel"); if (cb) cb.onclick = () => doJobAction(patientId, jid, "cancel", body);
        const rb = body.querySelector("#job-retry"); if (rb) rb.onclick = () => doJobAction(patientId, jid, "retry", body);
      } catch (e) { UI.toastErr(e); body.innerHTML = '<div class="empty">查询失败</div>'; }
    };
  }

  async function doJobAction(patientId, jid, action, body) {
    try {
      const j = await Api.post("/api/v1/patients/" + patientId + "/memory/jobs/" + jid + "/" + action, {});
      UI.toast("已" + (action === "cancel" ? "取消" : "重试"), "ok");
      body.querySelector("#job-go").click();
    } catch (e) { UI.toastErr(e); }
  }

  HOS.router.register("/memory", render);
  HOS.router.register("/memory/:patientId", render);
  // 版本详情（查看 + 导出）
  HOS.router.register("/memory/:patientId/v/:memoryId", async (ctx) => {
    const { patientId, memoryId } = ctx.params;
    const el = ctx.el;
    HOS.app.setHead("记忆版本 · " + memoryId, '<button class="btn" id="back">← 返回</button>');
    el.innerHTML = UI.spinner();
    let v;
    try { v = await Api.get("/api/v1/patients/" + patientId + "/memory/versions/" + memoryId); }
    catch (e) { UI.toastErr(e); el.innerHTML = '<div class="empty">加载失败</div>'; return; }
    document.getElementById("back").onclick = () => HOS.router.go("/memory/" + patientId);
    el.innerHTML =
      '<div class="toolbar"><button class="btn" id="back2">← 记忆</button><button class="btn btn-primary" id="exp">导出 Markdown（带哈希）</button></div>' +
      '<div class="card"><h3>版本 ' + v.version + ' · 内容哈希 <span class="code">' + UI.escapeHtml(v.contentHash || "") + '</span></h3>' +
      '<div class="pre" style="background:#f8fafc;color:#1f2933;">' + UI.escapeHtml(v.markdown || "") + "</div></div>" +
      '<div class="card"><h3>来源清单 <span class="sub">' + (v.sources || []).length + " 条</span></h3>" +
        UI.table([
          { key: "kind", label: "类型", render: v => UI.pill(v) },
          { key: "ref", label: "引用", cls: "code" },
          { key: "hash", label: "哈希", cls: "code", render: v => UI.escapeHtml((v || "").slice(0, 16)) + "…" },
        ], (v.sources || []).map(s => ({ kind: s.kind, ref: s.ref, hash: s.hash })), "无来源") + "</div>";
    document.getElementById("back2").onclick = () => HOS.router.go("/memory/" + patientId);
    document.getElementById("exp").onclick = () => Api.download("/api/v1/patients/" + patientId + "/memory/versions/" + memoryId + "/export", "memory-" + memoryId + ".md");
  });
})(window);
