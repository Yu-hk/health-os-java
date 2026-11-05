/* 资料录入：上传体检资料、异步抽取、两道核查、确认生成档案。 */
(function (global) {
  "use strict";
  const HOS = global.HOS, Api = HOS.Api, UI = HOS.UI;

  async function render(ctx) {
    const id = ctx.params.examId;
    if (id) return renderDetail(ctx, id);
    return renderList(ctx);
  }

  async function renderList(ctx) {
    const el = ctx.el;
    HOS.app.setHead("资料录入", '<button class="btn btn-primary" id="upload-intake">＋ 上传体检资料</button>');
    el.innerHTML = '<div class="card"><div id="intake-table">' + UI.spinner() + "</div></div>";
    document.getElementById("upload-intake").onclick = openUpload;

    try {
      const page = await Api.get("/api/v1/patient-intakes?size=50");
      const rows = (page && page.items) || page || [];
      document.getElementById("intake-table").innerHTML = UI.table([
        { key: "id", label: "录入单 ID", cls: "code" },
        { key: "patientId", label: "患者", cls: "code", render: v => v ? '<a href="#/patients/' + v + '">' + UI.escapeHtml(v) + "</a>" : "-" },
        { key: "sourceFilename", label: "来源文件" },
        { key: "status", label: "状态", render: v => UI.pill(v) },
        { key: "basicReviewStatus", label: "基础核查", render: v => UI.pill(v) },
        { key: "clinicalReviewStatus", label: "临床核查", render: v => UI.pill(v) },
        { key: "createdAt", label: "创建时间", render: v => UI.date(v) },
        { key: "op", label: "操作", render: (v, r) => '<a class="btn btn-sm" href="#/intake/' + r.id + '">详情</a>' },
      ], rows, "还没有录入单，点击右上角上传体检资料。");
    } catch (e) { UI.toastErr(e); }
  }

  function openUpload() {
    const body = document.createElement("div");
    body.innerHTML = '<label class="field"><span>选择体检资料文件（PDF / 图片）</span><input type="file" id="intake-file" /></label>';
    UI.modal({
      title: "上传体检资料",
      body: body,
      actions: [
        { label: "取消" },
        { label: "上传（异步抽取）", primary: true, onClick: async () => {
          const file = body.querySelector("#intake-file").files[0];
          if (!file) { UI.toast("请选择文件", "warn"); return false; }
          const fd = new FormData();
          fd.append("file", file);
          try { const r = await Api.post("/api/v1/patient-intakes", null, { form: fd }); UI.toast("已受理，抽取中", "ok"); HOS.router.go("/intake/" + r.examId); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  async function renderDetail(ctx, examId) {
    const el = ctx.el;
    el.innerHTML = UI.spinner();
    let v;
    try { v = await Api.get("/api/v1/patient-intakes/" + examId); }
    catch (e) { UI.toastErr(e); el.innerHTML = '<div class="empty">加载失败</div>'; return; }

    HOS.app.setHead("录入单 · " + examId,
      '<button class="btn" id="back">← 返回</button>' +
      (v.status === "EXTRACTED" ? '<button class="btn btn-primary" id="review">核查</button>' : "") +
      (v.basicReviewStatus === "CONFIRMED" && v.clinicalReviewStatus === "CONFIRMED" ? '<button class="btn btn-primary" id="confirm">确认建档</button>' : ""));

    const agg = v.aggregate || {};
    const fields = agg.fields || {};
    const metrics = v.parts ? v.parts.flatMap(p => p.metrics || []) : [];

    el.innerHTML =
      '<div class="toolbar"><button class="btn" id="back2">← 返回列表</button></div>' +
      '<div class="card"><h3>抽取概要</h3><dl class="kv">' +
        "<dt>状态</dt><dd>" + UI.pill(v.status) + "</dd>" +
        "<dt>来源文件</dt><dd>" + UI.escapeHtml(v.sourceFilename) + "</dd>" +
        "<dt>来源哈希</dt><dd class=\"code\">" + UI.escapeHtml((v.sourceSha256 || "").slice(0, 24)) + "…</dd>" +
        "<dt>抽取器</dt><dd>" + UI.escapeHtml(v.extractor) + "</dd>" +
        "<dt>版本</dt><dd>" + v.revision + "</dd>" +
        "<dt>创建时间</dt><dd>" + UI.date(v.createdAt) + "</dd>" +
      "</dl></div>" +
      '<div class="card"><h3>基础字段（抽取结果）</h3>' +
        (Object.keys(fields).length ? '<dl class="kv">' + Object.entries(fields).map(([k, val]) => "<dt>" + UI.escapeHtml(k) + "</dt><dd>" + UI.escapeHtml(val) + "</dd>").join("") + "</dl>" : '<div class="muted">无</div>') +
      "</div>" +
      '<div class="card"><h3>指标（分片汇总） <span class="sub">' + metrics.length + " 条</span></h3>" +
        UI.table([
          { key: "name", label: "名称" },
          { key: "value", label: "数值" },
          { key: "unit", label: "单位" },
          { key: "referenceLow", label: "参考下限" },
          { key: "referenceHigh", label: "参考上限" },
        ], metrics, "无指标") +
      "</div>";

    document.getElementById("back").onclick = document.getElementById("back2").onclick = () => HOS.router.go("/intake");
    const revBtn = document.getElementById("review");
    if (revBtn) revBtn.onclick = () => openReview(v);
    const cfBtn = document.getElementById("confirm");
    if (cfBtn) cfBtn.onclick = () => openConfirm(v);
  }

  function openReview(v) {
    const fields = [
      { name: "basicDecision", label: "基础信息核查", type: "select", options: [{ value: "", label: "本次不表态" }, { value: "CONFIRMED", label: "通过" }, { value: "REJECTED", label: "驳回（需填意见）" }] },
      { name: "basicNote", label: "基础核查意见" },
      { name: "clinicalDecision", label: "临床内容核查", type: "select", options: [{ value: "", label: "本次不表态" }, { value: "CONFIRMED", label: "通过" }, { value: "REJECTED", label: "驳回（需填意见）" }] },
      { name: "clinicalNote", label: "临床核查意见" },
    ];
    const body = UI.formFields(fields);
    UI.modal({
      title: "两道核查（可分别表态）",
      body: body,
      actions: [
        { label: "取消" },
        { label: "提交核查", primary: true, onClick: async () => {
          const f = UI.readForm(body);
          const req = { basicDecision: f.basicDecision || null, basicNote: f.basicNote || null, clinicalDecision: f.clinicalDecision || null, clinicalNote: f.clinicalNote || null };
          try { await Api.post("/api/v1/patient-intakes/" + v.id + "/reviews", req); UI.toast("已提交核查", "ok"); HOS.router.go("/intake/" + v.id); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  function openConfirm(v) {
    const fields = [
      { name: "expectedRevision", label: "录入单版本（乐观锁）", value: v.revision, hint: "通常为当前版本号" },
      { name: "externalRef", label: "外部编号", placeholder: "可空" },
      { name: "name", label: "姓名（人工补录，覆盖抽取）", placeholder: "留空=以抽取为准" },
      { name: "gender", label: "性别", type: "select", options: [{ value: "", label: "留空=以抽取为准" }, { value: "男", label: "男" }, { value: "女", label: "女" }] },
      { name: "birthDate", label: "出生日期", type: "date" },
      { name: "phone", label: "联系电话" },
    ];
    const body = UI.formFields(fields);
    UI.modal({
      title: "确认建档（两道核查已通过）",
      body: body,
      actions: [
        { label: "取消" },
        { label: "生成档案", primary: true, onClick: async () => {
          const f = UI.readForm(body);
          const req = { expectedRevision: Number(f.expectedRevision), externalRef: f.externalRef || null, name: f.name || null, gender: f.gender || null, birthDate: f.birthDate || null, phone: f.phone || null };
          try { const r = await Api.post("/api/v1/patient-intakes/" + v.id + "/confirm", req); UI.toast("档案已生成：" + (r.patientId || ""), "ok"); HOS.router.go("/patients/" + (r.patientId || "")); return true; }
          catch (e) { UI.toastErr(e); return false; }
        } },
      ],
    });
  }

  HOS.router.register("/intake", render);
  HOS.router.register("/intake/:examId", render);
})(window);
