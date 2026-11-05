/* 患者档案：列表、建档、详情（概览/附件/版本）、状态流转、指标、附件。 */
(function (global) {
  "use strict";
  const HOS = global.HOS, Api = HOS.Api, UI = HOS.UI;

  async function render(ctx) {
    const id = ctx.params.id;
    if (id) return renderDetail(ctx, id);
    return renderList(ctx);
  }

  async function renderList(ctx) {
    const el = ctx.el;
    HOS.app.setHead("患者档案", '<button class="btn btn-primary" id="new-patient">＋ 新建患者</button>');
    el.innerHTML = '<div class="card"><div id="patients-table">' + UI.spinner() + "</div></div>";
    document.getElementById("new-patient").addEventListener("click", openCreate);

    try {
      const page = await Api.get("/api/v1/patients?size=50");
      const rows = (page && page.items) || page || [];
      document.getElementById("patients-table").innerHTML = UI.table([
        { key: "id", label: "ID", cls: "code" },
        { key: "name", label: "姓名" },
        { key: "gender", label: "性别" },
        { key: "riskLevel", label: "风险等级", render: v => UI.pill(v) },
        { key: "status", label: "状态", render: v => UI.pill(v) },
        { key: "revision", label: "版本" },
        { key: "createdAt", label: "建档时间", render: v => UI.date(v) },
        { key: "op", label: "操作", render: (v, r) => '<a class="btn btn-sm" href="#/patients/' + r.id + '">查看</a>' },
      ], rows, "还没有患者，点击右上角新建。");
    } catch (e) { UI.toastErr(e); }
  }

  function openCreate() {
    const fields = [
      { name: "name", label: "姓名", required: true },
      { name: "gender", label: "性别", type: "select", required: true, options: [{ value: "男", label: "男" }, { value: "女", label: "女" }] },
      { name: "birthDate", label: "出生日期", type: "date", hint: "yyyy-mm-dd，可空" },
      { name: "phone", label: "联系电话", placeholder: "可空" },
      { name: "externalRef", label: "外部编号", placeholder: "可空" },
      { name: "draft", label: "建成草稿（不完备档案）", type: "select", options: [{ value: "", label: "否（直接生效）" }, { value: "true", label: "是" }] },
    ];
    const body = UI.formFields(fields);
    UI.modal({
      title: "新建患者",
      body: body,
      actions: [
        { label: "取消" },
        { label: "创建", primary: true, onClick: async () => {
          const f = UI.readForm(body);
          const req = { name: f.name, gender: f.gender, birthDate: f.birthDate || null, phone: f.phone || null, externalRef: f.externalRef || null, draft: f.draft === "true" ? true : null };
          try { await Api.post("/api/v1/patients", req); UI.toast("已创建", "ok"); HOS.router.go("/patients"); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  async function renderDetail(ctx, id) {
    const el = ctx.el;
    el.innerHTML = UI.spinner();
    let detail;
    try { detail = await Api.get("/api/v1/patients/" + id); }
    catch (e) { UI.toastErr(e); el.innerHTML = '<div class="empty">加载失败</div>'; return; }

    const p = detail.patient;
    const status = p.status;
    const canConfirm = status === "DRAFT";
    const canArchive = status === "ACTIVE";
    const canRestore = status === "ARCHIVED";
    HOS.app.setHead("患者 · " + p.name, "");

    el.innerHTML =
      '<div class="toolbar">' +
        '<button class="btn" id="back">← 返回列表</button>' +
        '<button class="btn" id="edit">编辑</button>' +
        (canConfirm ? '<button class="btn btn-primary" id="confirm">确认建档</button>' : "") +
        (canArchive ? '<button class="btn" id="archive">归档</button>' : "") +
        (canRestore ? '<button class="btn" id="restore">恢复</button>' : "") +
        '<div class="spacer"></div>' +
        '<button class="btn" id="add-metric">录入指标</button>' +
        '<button class="btn" id="upload-obj">上传附件</button>' +
      "</div>" +
      '<div class="tabs" id="tabs">' +
        '<a data-t="overview" class="active">概览</a>' +
        '<a data-t="objects">附件</a>' +
        '<a data-t="revisions">版本历史</a>' +
      "</div>" +
      '<div id="tab-body"></div>';

    document.getElementById("back").onclick = () => HOS.router.go("/patients");
    document.getElementById("edit").onclick = () => openEdit(p);
    if (canConfirm) document.getElementById("confirm").onclick = () => transition(id, "confirm", p.revision, "确认建档");
    if (canArchive) document.getElementById("archive").onclick = () => transition(id, "archive", p.revision, "归档");
    if (canRestore) document.getElementById("restore").onclick = () => transition(id, "restore", p.revision, "恢复");
    document.getElementById("add-metric").onclick = () => openMetric(id);
    document.getElementById("upload-obj").onclick = () => openUpload(id);

    const tabBody = document.getElementById("tab-body");
    function showTab(t) {
      document.querySelectorAll("#tabs a").forEach(a => a.classList.toggle("active", a.dataset.t === t));
      if (t === "overview") renderOverview(tabBody, detail);
      else if (t === "objects") renderObjects(tabBody, id);
      else if (t === "revisions") renderRevisions(tabBody, id);
    }
    document.querySelectorAll("#tabs a").forEach(a => a.onclick = () => showTab(a.dataset.t));
    showTab("overview");
  }

  function renderOverview(mount, detail) {
    const p = detail.patient;
    const ab = detail.abnormal || {};
    const abTxt = Object.entries(ab).map(([k, v]) => UI.pill(k) + " " + v).join("　") || '<span class="muted">无异常</span>';
    const metricsRows = (detail.metrics || []).map(m => ({
      code: m.code, name: m.name, value: (m.value + (m.unit ? " " + m.unit : "")),
      status: m.status, ref: (m.referenceLow != null || m.referenceHigh != null) ? (m.referenceLow + "~" + m.referenceHigh) : "-",
      measuredAt: m.measuredAt,
    }));
    mount.innerHTML =
      '<div class="card"><h3>基本信息</h3><dl class="kv">' +
        "<dt>ID</dt><dd class=\"code\">" + UI.escapeHtml(p.id) + "</dd>" +
        "<dt>姓名</dt><dd>" + UI.escapeHtml(p.name) + "</dd>" +
        "<dt>性别</dt><dd>" + UI.escapeHtml(p.gender) + "</dd>" +
        "<dt>出生日期</dt><dd>" + UI.escapeHtml(p.birthDate) + "</dd>" +
        "<dt>联系电话</dt><dd>" + UI.escapeHtml(p.phone) + "</dd>" +
        "<dt>外部编号</dt><dd>" + UI.escapeHtml(p.externalRef) + "</dd>" +
        "<dt>状态</dt><dd>" + UI.pill(p.status) + "</dd>" +
        "<dt>风险等级</dt><dd>" + UI.pill(p.riskLevel) + "</dd>" +
        "<dt>版本</dt><dd>" + p.revision + "</dd>" +
        "<dt>建档人</dt><dd>" + UI.escapeHtml(p.createdBy) + "</dd>" +
        "<dt>建档时间</dt><dd>" + UI.date(p.createdAt) + "</dd>" +
      "</dl></div>" +
      '<div class="card"><h3>指标异常概览</h3><div>' + abTxt + "</div></div>" +
      '<div class="card"><h3>体检指标 <span class="sub">' + (detail.metrics || []).length + " 项</span></h3>" +
        UI.table([
          { key: "code", label: "代码", cls: "code" },
          { key: "name", label: "名称" },
          { key: "value", label: "数值" },
          { key: "ref", label: "参考区间" },
          { key: "status", label: "判定", render: v => UI.pill(v) },
          { key: "measuredAt", label: "测量日期", render: v => UI.escapeHtml(v) },
        ], metricsRows, "暂无指标") +
      "</div>";
  }

  async function renderObjects(mount, id) {
    mount.innerHTML = UI.spinner();
    try {
      const list = await Api.get("/api/v1/patients/" + id + "/objects");
      mount.innerHTML = '<div class="card"><h3>附件 <span class="sub">' + list.length + " 个</span></h3>" +
        UI.table([
          { key: "id", label: "ID", cls: "code" },
          { key: "filename", label: "文件名" },
          { key: "kind", label: "类型", render: v => UI.pill(v) },
          { key: "sizeBytes", label: "大小", render: v => (v / 1024).toFixed(1) + " KB" },
          { key: "status", label: "状态", render: v => UI.pill(v) },
          { key: "createdAt", label: "上传时间", render: v => UI.date(v) },
          { key: "op", label: "操作", render: (v, r) => '<button class="btn btn-sm btn-danger" data-del="' + r.id + '">删除</button>' },
        ], list, "暂无附件") + "</div>";
      mount.querySelectorAll("[data-del]").forEach(b => b.onclick = async () => {
        if (!confirm("确认删除该附件？")) return;
        try { await Api.del("/api/v1/patients/" + id + "/objects/" + b.dataset.del); UI.toast("已删除", "ok"); renderObjects(mount, id); }
        catch (e) { UI.toastErr(e); }
      });
    } catch (e) { UI.toastErr(e); }
  }

  async function renderRevisions(mount, id) {
    mount.innerHTML = UI.spinner();
    try {
      const list = await Api.get("/api/v1/patients/" + id + "/revisions");
      mount.innerHTML = '<div class="card"><h3>版本历史 <span class="sub">' + list.length + " 版</span></h3>" +
        UI.table([
          { key: "revision", label: "版本" },
          { key: "actor", label: "操作人" },
          { key: "reason", label: "原因" },
          { key: "sha256", label: "SHA256", cls: "code", render: v => UI.escapeHtml((v || "").slice(0, 16) + "…") },
          { key: "createdAt", label: "时间", render: v => UI.date(v) },
        ], list, "暂无版本") + "</div>";
    } catch (e) { UI.toastErr(e); }
  }

  function openEdit(p) {
    const fields = [
      { name: "name", label: "姓名", value: p.name },
      { name: "gender", label: "性别", type: "select", value: p.gender, options: [{ value: "男", label: "男" }, { value: "女", label: "女" }] },
      { name: "birthDate", label: "出生日期", type: "date", value: p.birthDate },
      { name: "phone", label: "联系电话", value: p.phone || "" },
      { name: "externalRef", label: "外部编号", value: p.externalRef || "" },
      { name: "reason", label: "修改原因" },
    ];
    const body = UI.formFields(fields);
    UI.modal({
      title: "编辑患者（乐观锁版本 " + p.revision + "）",
      body: body,
      actions: [
        { label: "取消" },
        { label: "保存", primary: true, onClick: async () => {
          const f = UI.readForm(body);
          const req = { expectedRevision: p.revision, name: f.name || null, gender: f.gender || null, birthDate: f.birthDate || null, phone: f.phone || null, externalRef: f.externalRef || null, reason: f.reason || null };
          try { await Api.patch("/api/v1/patients/" + p.id, req); UI.toast("已保存", "ok"); HOS.router.go("/patients/" + p.id); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  function transition(id, action, revision, title) {
    const fields = [{ name: "reason", label: "操作原因", placeholder: "可选" }];
    const body = UI.formFields(fields);
    UI.modal({
      title: title + "（版本 " + revision + "）",
      body: body,
      actions: [
        { label: "取消" },
        { label: "确定", primary: true, onClick: async () => {
          const f = UI.readForm(body);
          try { await Api.post("/api/v1/patients/" + id + "/" + action, { expectedRevision: revision, reason: f.reason || null }); UI.toast("已" + title, "ok"); HOS.router.go("/patients/" + id); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  function openMetric(id) {
    const fields = [
      { name: "code", label: "指标代码", required: true, placeholder: "如 GLU" },
      { name: "name", label: "指标名称", required: true, placeholder: "如 空腹血糖" },
      { name: "value", label: "数值", required: true, placeholder: "数字" },
      { name: "unit", label: "单位", placeholder: "如 mmol/L" },
      { name: "referenceLow", label: "参考下限", placeholder: "可空" },
      { name: "referenceHigh", label: "参考上限", placeholder: "可空" },
      { name: "measuredAt", label: "测量日期", type: "date", placeholder: "可空" },
    ];
    const body = UI.formFields(fields);
    UI.modal({
      title: "录入指标",
      body: body,
      actions: [
        { label: "取消" },
        { label: "保存", primary: true, onClick: async () => {
          const f = UI.readForm(body);
          const m = {
            code: f.code, name: f.name,
            value: f.value == null ? null : Number(f.value),
            unit: f.unit || null,
            referenceLow: f.referenceLow ? Number(f.referenceLow) : null,
            referenceHigh: f.referenceHigh ? Number(f.referenceHigh) : null,
            measuredAt: f.measuredAt || null,
          };
          try { await Api.post("/api/v1/patients/" + id + "/metrics", [m]); UI.toast("指标已保存", "ok"); HOS.router.go("/patients/" + id); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  function openUpload(id) {
    const body = document.createElement("div");
    body.innerHTML = '<label class="field"><span>选择文件</span><input type="file" id="obj-file" /></label>' +
      '<label class="field"><span>类型（可选）</span><input type="text" id="obj-kind" placeholder="如 化验单 / 影像" /></label>';
    UI.modal({
      title: "上传附件",
      body: body,
      actions: [
        { label: "取消" },
        { label: "上传", primary: true, onClick: async () => {
          const file = body.querySelector("#obj-file").files[0];
          if (!file) { UI.toast("请选择文件", "warn"); return false; }
          const fd = new FormData();
          fd.append("file", file);
          const kind = body.querySelector("#obj-kind").value;
          if (kind) fd.append("kind", kind);
          try { await Api.post("/api/v1/patients/" + id + "/objects", null, { form: fd }); UI.toast("上传成功", "ok"); HOS.router.go("/patients/" + id); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  HOS.router.register("/patients", render);
  HOS.router.register("/patients/:id", render);
})(window);
