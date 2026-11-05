/* 系统概览：健康检查 + 依赖姿态。 */
(function (global) {
  "use strict";
  const HOS = global.HOS, Api = HOS.Api, UI = HOS.UI;

  async function render(ctx) {
    const el = ctx.el;
    HOS.app.setHead("系统概览");
    el.innerHTML = '<div class="card"><h3>依赖姿态 <span class="sub">/health/dependencies</span></h3><div class="grid grid-4" id="dep-cards">' + UI.spinner() + "</div></div>" +
      '<div class="card"><h3>原始响应</h3><pre class="pre" id="dep-raw">加载中…</pre></div>';

    let dep;
    try {
      dep = await Api.dependencies();
    } catch (e) {
      UI.toastErr(e);
      el.innerHTML = '<div class="empty">无法获取依赖状态：' + UI.escapeHtml(e.message) + "</div>";
      return;
    }
    const cards = document.getElementById("dep-cards");
    const items = [
      ["status", "整体状态", true],
      ["database", "数据库", true],
      ["objectStorage", "对象存储", true],
      ["llmProvider", "模型提供方", false],
      ["knowledgeBase", "知识库", false],
      ["graphStore", "图谱存储", true],
      ["ocr", "OCR", false],
      ["carePdf", "PDF 导出", false],
      ["qaMode", "问答模式", false],
      ["tracing", "观测", false],
      ["auth", "认证姿态", false],
    ];
    cards.innerHTML = items.map(([k, label, ok]) => {
      const v = dep[k];
      let cls = "";
      if (ok && typeof v === "string") {
        if (v.indexOf("ok") >= 0 || v === "ready") cls = "ok";
        else if (v.indexOf("unavailable") >= 0 || v === "degraded") cls = "bad";
        else if (v.indexOf("disabled") >= 0) cls = "warn";
      }
      return '<div class="stat"><div class="label">' + label + '</div><div class="value ' + cls + ' code">' + UI.escapeHtml(v == null ? "-" : String(v)) + "</div></div>";
    }).join("");
    document.getElementById("dep-raw").textContent = JSON.stringify(dep, null, 2);
  }

  HOS.router.register("/dashboard", render);
})(window);
