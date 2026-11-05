/* 智能问答：同步问答、异步运行（节点/事件/SSE/重试/取消）、会话回读、患者知识库绑定。 */
(function (global) {
  "use strict";
  const HOS = global.HOS, Api = HOS.Api, UI = HOS.UI;

  async function render(ctx) {
    const runId = ctx.params.runId;
    if (runId) return renderRun(ctx, runId);
    return renderHub(ctx);
  }

  // ---------------- 问答中枢 ----------------
  async function renderHub(ctx) {
    const el = ctx.el;
    HOS.app.setHead("智能问答");
    el.innerHTML =
      '<div class="tabs" id="tabs">' +
        '<a data-t="sync" class="active">同步问答</a>' +
        '<a data-t="async">异步运行</a>' +
        '<a data-t="kb">患者知识库</a>' +
      "</div><div id=\"tab-body\"></div>";
    const tabBody = document.getElementById("tab-body");
    function show(t) {
      document.querySelectorAll("#tabs a").forEach(a => a.classList.toggle("active", a.dataset.t === t));
      if (t === "sync") renderSync(tabBody);
      else if (t === "async") renderAsync(tabBody);
      else if (t === "kb") renderKb(tabBody);
    }
    document.querySelectorAll("#tabs a").forEach(a => a.onclick = () => show(a.dataset.t));
    show("sync");
  }

  function renderSync(mount) {
    const fields = [
      { name: "question", label: "问题", type: "textarea", required: true },
      { name: "documentId", label: "文档 ID（可选，限定检索范围）" },
      { name: "conversationId", label: "会话 ID（可选，用于连续对话）" },
      { name: "patientId", label: "患者 ID（可选，并行模式指标路使用）" },
      { name: "mode", label: "模式", type: "select", options: [{ value: "single", label: "single 单路" }, { value: "parallel", label: "parallel 三路并行" }] },
    ];
    const body = UI.formFields(fields);
    mount.innerHTML = '<div class="card"><h3>同步问答（一次返回）</h3></div>';
    mount.querySelector(".card").appendChild(body);
    const btn = document.createElement("button");
    btn.className = "btn btn-primary"; btn.textContent = "提问";
    body.appendChild(btn);
    const out = document.createElement("div");
    mount.appendChild(out);
    btn.onclick = async () => {
      const f = UI.readForm(body);
      if (!f.question) { UI.toast("请输入问题", "warn"); return; }
      out.innerHTML = UI.spinner() + " 提问中…";
      try {
        const r = await Api.post("/api/v1/qa/ask", { question: f.question, documentId: f.documentId || null, conversationId: f.conversationId || null, patientId: f.patientId || null, mode: f.mode || "single" });
        out.innerHTML = renderAskResult(r);
      } catch (e) { UI.toastErr(e); out.innerHTML = '<div class="empty">问答失败</div>'; }
    };
  }

  function renderAskResult(r) {
    let html = '<div class="card"><h3>回答 <span class="sub">' + UI.escapeHtml(r.status || "") + (r.errorCode ? " · " + UI.escapeHtml(r.errorCode) : "") + '</span></h3>' +
      '<div class="pre" style="background:#f8fafc;color:#1f2933;">' + UI.escapeHtml(r.answer || "（无回答）") + "</div>";
    if (r.citations && r.citations.length) html += renderCitations(r.citations);
    html += "</div>";
    if (r.branches && r.branches.length) {
      html += '<div class="card"><h3>分支明细</h3>' + r.branches.map(b =>
        '<div style="margin-bottom:12px;"><b>' + UI.escapeHtml(b.label || b.key) + "</b> " + UI.pill(b.status) +
        (b.answer ? '<div class="pre" style="background:#f8fafc;color:#1f2933;margin-top:6px;">' + UI.escapeHtml(b.answer) + "</div>" : "") +
        (b.citations && b.citations.length ? renderCitations(b.citations) : "") + "</div>"
      ).join("");
    }
    return html;
  }

  function renderCitations(cites) {
    if (!cites.length) return "";
    return '<div style="margin-top:10px;"><div class="muted" style="font-size:12px;">引用 ' + cites.length + " 条</div>" + cites.map(c =>
      '<div style="border-left:3px solid var(--primary);padding:4px 0 4px 10px;margin:6px 0;font-size:13px;">' +
      (c.quote ? "“" + UI.escapeHtml(c.quote) + "”" : "") +
      '<span class="muted"> ' + (c.page ? "p" + c.page : "") + (c.documentId ? " · 文档 " + UI.escapeHtml(c.documentId) : "") + (c.evidenceId ? " · " + UI.escapeHtml(c.evidenceId) : "") + "</span></div>"
    ).join("") + "</div>";
  }

  function renderAsync(mount) {
    const fields = [
      { name: "question", label: "问题", type: "textarea", required: true },
      { name: "documentId", label: "文档 ID（可选）" },
      { name: "conversationId", label: "会话 ID（可选）" },
      { name: "patientId", label: "患者 ID（可选）" },
      { name: "idempotencyKey", label: "幂等键（可选）" },
      { name: "mode", label: "模式", type: "select", options: [{ value: "single", label: "single" }, { value: "parallel", label: "parallel" }] },
    ];
    const body = UI.formFields(fields);
    mount.innerHTML = '<div class="card"><h3>异步运行（长任务生命周期）</h3></div>';
    mount.querySelector(".card").appendChild(body);
    const btn = document.createElement("button");
    btn.className = "btn btn-primary"; btn.textContent = "提交运行";
    body.appendChild(btn);
    const out = document.createElement("div");
    mount.appendChild(out);
    btn.onclick = async () => {
      const f = UI.readForm(body);
      if (!f.question) { UI.toast("请输入问题", "warn"); return; }
      out.innerHTML = UI.spinner() + " 提交中…";
      try {
        const r = await Api.post("/api/v1/qa/runs", { question: f.question, documentId: f.documentId || null, conversationId: f.conversationId || null, patientId: f.patientId || null, idempotencyKey: f.idempotencyKey || null, mode: f.mode || "single" });
        UI.toast("已受理，运行 ID：" + r.runId, "ok");
        HOS.router.go("/qa/run/" + r.runId);
      } catch (e) { UI.toastErr(e); out.innerHTML = '<div class="empty">提交失败</div>'; }
    };

    // 查看已有运行
    const lookup = document.createElement("div");
    lookup.className = "card";
    lookup.innerHTML = '<h3>查看已有运行</h3><div class="row"><input id="run-id" placeholder="输入 runId" /><button class="btn btn-primary" id="go-run" style="flex:0 0 auto;">打开</button></div>';
    mount.appendChild(lookup);
    lookup.querySelector("#go-run").onclick = () => {
      const v = lookup.querySelector("#run-id").value.trim();
      if (v) HOS.router.go("/qa/run/" + v);
    };
  }

  async function renderKb(mount) {
    mount.innerHTML = '<div class="card"><h3>患者知识库绑定</h3><div class="row"><input id="kb-pid" placeholder="输入患者 ID" /><button class="btn btn-primary" id="kb-load" style="flex:0 0 auto;">加载</button></div><div id="kb-body" style="margin-top:14px;"></div></div>';
    mount.querySelector("#kb-load").onclick = async () => {
      const pid = mount.querySelector("#kb-pid").value.trim();
      if (!pid) { UI.toast("请输入患者 ID", "warn"); return; }
      const body = mount.querySelector("#kb-body");
      body.innerHTML = UI.spinner();
      try {
        const ov = await Api.get("/api/v1/qa/patients/" + pid + "/knowledge-bases");
        body.innerHTML = '<h4>已绑定</h4>' +
          UI.table([
            { key: "datasetId", label: "数据集", cls: "code" },
            { key: "datasetName", label: "名称" },
            { key: "status", label: "状态", render: v => UI.pill(v) },
            { key: "lastError", label: "最后错误" },
            { key: "op", label: "操作", render: (v, r) => '<button class="btn btn-sm" data-rec="' + r.id + '">对账</button> <button class="btn btn-sm btn-danger" data-unb="' + r.id + '">解绑</button>' },
          ], ov.bindings || [], "未绑定") +
          '<h4 style="margin-top:16px;">可绑定数据集</h4>' +
          UI.table([
            { key: "id", label: "数据集 ID", cls: "code" },
            { key: "name", label: "名称" },
            { key: "chunkCount", label: "切片数" },
            { key: "documentCount", label: "文档数" },
            { key: "op", label: "操作", render: (v, r) => r.bound ? UI.pill("已绑定") : '<button class="btn btn-sm btn-primary" data-bind="' + r.id + '">绑定</button>' },
          ], ov.available || [], "无可用数据集" + (ov.availableNote ? "（" + ov.availableNote + "）" : ""));
        body.querySelectorAll("[data-bind]").forEach(b => b.onclick = async () => {
          try { await Api.post("/api/v1/qa/patients/" + pid + "/knowledge-bases", { datasetId: b.dataset.bind, note: null }); UI.toast("已绑定", "ok"); mount.querySelector("#kb-load").click(); }
          catch (e) { UI.toastErr(e); }
        });
        body.querySelectorAll("[data-unb]").forEach(b => b.onclick = async () => {
          try { await Api.del("/api/v1/qa/patients/" + pid + "/knowledge-bases/" + b.dataset.unb); UI.toast("已解绑", "ok"); mount.querySelector("#kb-load").click(); }
          catch (e) { UI.toastErr(e); }
        });
        body.querySelectorAll("[data-rec]").forEach(b => b.onclick = async () => {
          try { await Api.post("/api/v1/qa/patients/" + pid + "/knowledge-bases/" + b.dataset.rec + "/reconcile", {}); UI.toast("已对账", "ok"); mount.querySelector("#kb-load").click(); }
          catch (e) { UI.toastErr(e); }
        });
      } catch (e) { UI.toastErr(e); body.innerHTML = '<div class="empty">加载失败</div>'; }
    };
  }

  // ---------------- 运行详情（轮询 + SSE） ----------------
  let activeStream = null;
  async function renderRun(ctx, runId) {
    const el = ctx.el;
    HOS.app.setHead("运行 · " + runId,
      '<button class="btn" id="back">← 返回</button>' +
      '<button class="btn" id="retry">重试失败节点</button>' +
      '<button class="btn" id="cancel">取消</button>' +
      '<button class="btn" id="stream">订阅事件流</button>');
    document.getElementById("back").onclick = () => HOS.router.go("/qa");
    document.getElementById("retry").onclick = async () => {
      try { await Api.post("/api/v1/qa/runs/" + runId + "/retry", {}); UI.toast("已重试", "ok"); } catch (e) { UI.toastErr(e); }
    };
    document.getElementById("cancel").onclick = async () => {
      try { await Api.post("/api/v1/qa/runs/" + runId + "/cancel", {}); UI.toast("已取消", "ok"); } catch (e) { UI.toastErr(e); }
    };
    document.getElementById("stream").onclick = () => subscribeStream(runId, el);

    el.innerHTML =
      '<div class="card"><h3>运行状态 <span id="run-status"></span></h3><div id="run-answer" class="pre" style="background:#f8fafc;color:#1f2933;">等待…</div></div>' +
      '<div class="card"><h3>节点</h3><div id="run-nodes"></div></div>' +
      '<div class="card"><h3>事件流（SSE / 增量）</h3><div id="run-events" style="max-height:300px;overflow:auto;font-size:12px;"></div></div>';

    poll(runId, el);
  }

  let pollTimer = null;
  async function poll(runId, el) {
    const statusEl = document.getElementById("run-status");
    const ansEl = document.getElementById("run-answer");
    const nodesEl = document.getElementById("run-nodes");
    const tick = async () => {
      try {
        const d = await Api.get("/api/v1/qa/runs/" + runId);
        statusEl.innerHTML = UI.pill(d.status) + (d.errorCode ? " " + UI.escapeHtml(d.errorCode) : "");
        ansEl.textContent = d.answer || "（暂无回答）";
        nodesEl.innerHTML = UI.table([
          { key: "label", label: "节点" },
          { key: "status", label: "状态", render: v => UI.pill(v) },
          { key: "attemptCount", label: "尝试" },
          { key: "errorMessage", label: "错误" },
        ], d.nodes || [], "无节点");
        if (["SUCCEEDED", "FAILED", "CANCELLED"].includes(d.status)) {
          if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
        }
      } catch (e) { /* 忽略单次失败 */ }
    };
    await tick();
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = setInterval(tick, 1500);
  }

  function subscribeStream(runId, el) {
    if (activeStream) { activeStream.close(); activeStream = null; }
    const box = document.getElementById("run-events");
    box.innerHTML = "";
    const es = new EventSource("/api/v1/qa/runs/" + runId + "/stream?afterSeq=0");
    activeStream = es;
    es.onmessage = (ev) => {
      let data;
      try { data = JSON.parse(ev.data); } catch (e) { return; }
      const line = document.createElement("div");
      line.textContent = "[" + (data.seq != null ? data.seq : "?") + "] " + (data.type || "") + " " + JSON.stringify(data.payload || "");
      box.prepend(line);
    };
    es.onerror = () => { UI.toast("事件流连接关闭", "warn"); es.close(); activeStream = null; };
    UI.toast("已订阅事件流", "ok");
  }

  HOS.router.register("/qa", render);
  HOS.router.register("/qa/run/:runId", render);
})(window);
