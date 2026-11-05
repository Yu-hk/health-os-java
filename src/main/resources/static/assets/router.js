/* 极简 hash 路由：路由都在 # 片段里，服务端只认 '/'，天然 SPA 友好。 */
(function (global) {
  "use strict";
  const HOS = global.HOS = global.HOS || {};
  const router = { routes: [], pages: {} };

  // pattern 形如 "/patients/:id"；page 为渲染函数 (ctx)=>{}
  router.register = function (pattern, page) {
    const keys = [];
    const regex = new RegExp("^" + pattern.replace(/:[^/]+/g, m => {
      keys.push(m.slice(1));
      return "([^/]+)";
    }) + "$");
    router.routes.push({ regex, keys, page });
  };

  router.go = function (path) {
    if (location.hash === "#" + path) router.resolve();
    else location.hash = "#" + path;
  };

  router.current = function () {
    return location.hash.replace(/^#/, "") || "/dashboard";
  };

  router.resolve = async function () {
    const path = router.current();
    const el = document.getElementById("page");
    el.innerHTML = HOS.UI.spinner();
    let matched = null, params = {};
    for (const r of router.routes) {
      const m = path.match(r.regex);
      if (m) {
        matched = r;
        r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
        break;
      }
    }
    // 更新导航高亮（取首段）
    const top = "/" + (path.split("/")[1] || "dashboard");
    document.querySelectorAll("#sidebar-nav a").forEach(a => {
      a.classList.toggle("active", a.getAttribute("data-path") === top);
    });

    if (!matched) {
      el.innerHTML = '<div class="empty">页面不存在：' + HOS.UI.escapeHtml(path) + "</div>";
      HOS.app.setHead("未找到", null);
      return;
    }
    try {
      await matched.page({ params, el, path, query: parseQuery(path) });
    } catch (e) {
      HOS.UI.toastErr(e);
      el.innerHTML = '<div class="empty">加载失败：' + HOS.UI.escapeHtml(e.message) + "</div>";
    }
  };

  function parseQuery(path) {
    const q = {};
    const i = path.indexOf("?");
    if (i < 0) return q;
    new URLSearchParams(path.slice(i + 1)).forEach((v, k) => { q[k] = v; });
    return q;
  }

  router.start = function () {
    window.addEventListener("hashchange", router.resolve);
    if (!location.hash) location.hash = "#/dashboard";
    else router.resolve();
  };

  HOS.router = router;
})(window);
