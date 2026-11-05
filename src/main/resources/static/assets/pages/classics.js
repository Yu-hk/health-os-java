/* 典籍处理：上传典籍、五节点流水线监控、切片/证据/产物查看与下载、重试。 */
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
    HOS.app.setHead("典籍处理", '<button class="btn btn-primary" id="upload-doc">＋ 上传典籍</button>');
    el.innerHTML = '<div class="card"><div id="doc-table">' + UI.spinner() + "</div></div>";
    document.getElementById("upload-doc").onclick = openUpload;

    try {
      const page = await Api.get("/api/v1/classics?size=50&page=" + (Number(ctx.query.page) || 1));
      const rows = (page && page.items) || page || [];
      document.getElementById("doc-table").innerHTML = UI.table([
        { key: "id", label: "文档 ID", cls: "code" },
        { key: "title", label: "标题" },
        { key: "status", label: "状态", render: v => UI.pill(v) },
        { key: "currentNode", label: "当前节点" },
        { key: "contentType", label: "类型" },
        { key: "createdAt", label: "创建时间", render: v => UI.date(v) },
        { key: "op", label: "操作", render: (v, r) => '<a class="btn btn-sm" href="#/classics/' + r.id + '">详情</a>' },
      ], rows, "还没有典籍，点击右上角上传。");
      UI.listTools(ctx, document.getElementById("doc-table"), page, "/classics");
    } catch (e) { UI.toastErr(e); }
  }

  function openUpload() {
    const body = document.createElement("div");
    body.innerHTML = '<label class="field"><span>选择典籍文件（PDF / 文本 / 图片）</span><input type="file" id="doc-file" /></label>' +
      '<label class="field"><span>标题（可选）</span><input type="text" id="doc-title" placeholder="留空则用文件名" /></label>' +
      '<label class="field"><span>幂等键（可选）</span><input type="text" id="doc-key" placeholder="同键返回同一份" /></label>';
    UI.modal({
      title: "上传典籍",
      body: body,
      actions: [
        { label: "取消" },
        { label: "上传（异步处理）", primary: true, onClick: async () => {
          const file = body.querySelector("#doc-file").files[0];
          if (!file) { UI.toast("请选择文件", "warn"); return false; }
          const fd = new FormData();
          fd.append("file", file);
          const title = body.querySelector("#doc-title").value;
          if (title) fd.append("title", title);
          const key = body.querySelector("#doc-key").value;
          const headers = {};
          if (key) headers["Idempotency-Key"] = key;
          try { const r = await Api.post("/api/v1/classics", null, { form: fd, headers }); UI.toast("已受理：" + r.status, "ok"); HOS.router.go("/classics/" + r.documentId); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  async function renderDetail(ctx, documentId) {
    const el = ctx.el;
    el.innerHTML = UI.spinner();
    let det;
    try { det = await Api.get("/api/v1/classics/" + documentId); }
    catch (e) { UI.toastErr(e); el.innerHTML = '<div class="empty">加载失败</div>'; return; }

    const d = det.document;
    const failed = det.nodes && det.nodes.some(n => n.status === "FAILED");
    HOS.app.setHead("典籍 · " + (d.title || d.id),
      '<a class="btn" href="#/classics/' + encodeURIComponent(documentId) + '/graph">知识图谱</a><a class="btn" href="#/qa?documentId=' + encodeURIComponent(documentId) + '">基于此典籍问答</a><button class="btn" id="classic-refresh">刷新进度</button>' +
      '<button class="btn" id="back">← 返回</button>' +
      (failed ? '<button class="btn btn-primary" id="retry">重试失败节点</button>' : ""));

    const nodesHtml = (det.nodes || []).map(n => {
      const pct = Math.round((n.progress || 0) * 100);
      return '<div class="card"><div style="display:flex;justify-content:space-between;align-items:center;">' +
        "<div><b>" + UI.escapeHtml(n.label || n.code) + "</b> " + UI.pill(n.status) + (n.errorMessage ? ' <span class="muted">(' + UI.escapeHtml(n.errorMessage) + ")</span>" : "") + "</div>" +
        "<div class=\"muted\">" + pct + "%</div></div>" +
        '<div style="background:#eef2f6;border-radius:6px;height:8px;margin-top:8px;overflow:hidden;"><div style="width:' + pct + '%;background:var(--primary);height:100%;"></div></div>' +
        (n.startedAt ? '<div class="muted" style="font-size:12px;margin-top:6px;">开始 ' + UI.date(n.startedAt) + (n.finishedAt ? " · 结束 " + UI.date(n.finishedAt) : "") + "</div>" : "") +
        "</div>";
    }).join("");

    el.innerHTML =
      '<div class="toolbar"><button class="btn" id="back2">← 返回列表</button></div>' +
      '<div class="card"><h3>文档信息</h3><dl class="kv">' +
        "<dt>ID</dt><dd class=\"code\">" + UI.escapeHtml(d.id) + "</dd>" +
        "<dt>标题</dt><dd>" + UI.escapeHtml(d.title) + "</dd>" +
        "<dt>状态</dt><dd>" + UI.pill(d.status) + "</dd>" +
        "<dt>当前节点</dt><dd>" + UI.escapeHtml(d.currentNode) + "</dd>" +
        "<dt>任务</dt><dd class=\"code\">" + UI.escapeHtml(det.jobId) + " " + UI.pill(det.jobStatus) + "</dd>" +
        "<dt>SHA256</dt><dd class=\"code\">" + UI.escapeHtml((d.sha256 || "").slice(0, 20)) + "…</dd>" +
      "</dl></div>" +
      '<div class="card"><h3>流水线节点</h3>' + (nodesHtml || '<div class="muted">无节点</div>') + "</div>" +
      '<div class="tabs" id="tabs"><a data-t="chunks" class="active">切片</a><a data-t="evidence">证据</a><a data-t="artifacts">产物</a></div>' +
      '<div id="tab-body"></div>';

    document.getElementById("back").onclick = document.getElementById("back2").onclick = () => HOS.router.go("/classics");
    const retryBtn = document.getElementById("retry");
    document.getElementById("classic-refresh").onclick = HOS.router.resolve;
    if (!["SUCCEEDED", "FAILED", "READY", "COMPLETED"].includes(d.status)) {
      const fingerprint = JSON.stringify([d.status, d.currentNode, det.nodes]);
      UI.poll(ctx, async () => {
        const latest = await Api.get("/api/v1/classics/" + encodeURIComponent(documentId));
        if (JSON.stringify([latest.document.status, latest.document.currentNode, latest.nodes]) !== fingerprint) { HOS.router.resolve(); return false; }
      }, 3000);
    }
    if (retryBtn) retryBtn.onclick = async () => {
      try { const r = await Api.post("/api/v1/classics/" + documentId + "/retry", {}); UI.toast("已重试：" + r.status, "ok"); HOS.router.go("/classics/" + documentId); }
      catch (e) { UI.toastErr(e); }
    };

    const tabBody = document.getElementById("tab-body");
    function showTab(t) {
      document.querySelectorAll("#tabs a").forEach(a => a.classList.toggle("active", a.dataset.t === t));
      if (t === "chunks") renderChunks(tabBody, documentId);
      else if (t === "evidence") renderEvidence(tabBody, documentId);
      else if (t === "artifacts") renderArtifacts(tabBody, documentId);
    }
    document.querySelectorAll("#tabs a").forEach(a => a.onclick = () => showTab(a.dataset.t));
    showTab("chunks");
  }

  async function renderChunks(mount, documentId) {
    mount.innerHTML = UI.spinner();
    try {
      const page = await Api.get("/api/v1/classics/" + documentId + "/chunks?size=50");
      const rows = (page && page.items) || page || [];
      mount.innerHTML = '<div class="card"><h3>切片 <span class="sub">' + rows.length + " 段</span></h3>" +
        UI.table([
          { key: "chunkIndex", label: "#" },
          { key: "content", label: "内容", cls: "wrap", render: v => UI.escapeHtml((v || "").slice(0, 120)) + ((v || "").length > 120 ? "…" : "") },
          { key: "pageFrom", label: "页码" },
          { key: "charStart", label: "起始字符" },
          { key: "charEnd", label: "结束字符" },
          { key: "contentLength", label: "长度" },
        ], rows, "暂无切片") + "</div>";
    } catch (e) { UI.toastErr(e); }
  }

  async function renderEvidence(mount, documentId) {
    mount.innerHTML = UI.spinner();
    try {
      const page = await Api.get("/api/v1/classics/" + documentId + "/evidence?size=50");
      const rows = (page && page.items) || page || [];
      mount.innerHTML = '<div class="card"><h3>证据 <span class="sub">' + rows.length + " 条</span></h3>" +
        UI.table([
          { key: "id", label: "ID", cls: "code" },
          { key: "artifactId", label: "产物", cls: "code" },
          { key: "quote", label: "原文引用", cls: "wrap" },
          { key: "pageFrom", label: "起页" },
          { key: "pageTo", label: "止页" },
          { key: "confidence", label: "置信度", render: v => (v != null ? (v * 100).toFixed(0) + "%" : "-") },
        ], rows, "暂无证据") + "</div>";
    } catch (e) { UI.toastErr(e); }
  }

  async function renderArtifacts(mount, documentId) {
    mount.innerHTML = UI.spinner();
    try {
      const list = await Api.get("/api/v1/classics/" + documentId + "/artifacts");
      mount.innerHTML = '<div class="card"><h3>流程产物 <span class="sub">' + list.length + " 个</span></h3>" +
        UI.table([
          { key: "id", label: "ID", cls: "code" },
          { key: "kind", label: "类型", render: v => UI.pill(v) },
          { key: "objectKey", label: "对象键", cls: "code" },
          { key: "sizeBytes", label: "大小", render: v => (v / 1024).toFixed(1) + " KB" },
          { key: "createdAt", label: "创建时间", render: v => UI.date(v) },
          { key: "op", label: "操作", render: (v, r) => '<button class="btn btn-sm" data-dl="' + r.id + '">下载</button>' },
        ], list, "暂无产物") + "</div>";
      mount.querySelectorAll("[data-dl]").forEach(b => b.onclick = () =>
        Api.download("/api/v1/classics/" + documentId + "/artifacts/" + b.dataset.dl + "/download", b.dataset.dl));
    } catch (e) { UI.toastErr(e); }
  }

  HOS.router.register("/classics", render);
  HOS.router.register("/classics/:documentId", render);
})(window);
