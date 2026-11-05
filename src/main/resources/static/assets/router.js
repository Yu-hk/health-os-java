/* 每次切换页面取消旧请求、轮询和 SSE；查询串不参与路由匹配。 */
(function (global) {
  "use strict";
  const HOS = global.HOS = global.HOS || {};
  const router = { routes: [], signal: null };
  let started = false, controller, cleanups = [];
  router.register = function (pattern, page) {
    const keys = [];
    const regex = new RegExp("^" + pattern.replace(/:[^/]+/g, value => {
      keys.push(value.slice(1)); return "([^/]+)";
    }) + "$");
    router.routes.push({ regex, keys, page });
  };
  router.current = () => location.hash.replace(/^#/, "") || "/dashboard";
  router.query = () => Object.fromEntries(new URLSearchParams(router.current().split("?")[1] || ""));
  router.onLeave = callback => cleanups.push(callback);
  function release() {
    if (controller) controller.abort();
    cleanups.splice(0).forEach(callback => { try { callback(); } catch (e) { /* continue cleanup */ } });
    if (HOS.UI.activeModal) HOS.UI.activeModal.close(true);
  }
  router.go = path => { if (location.hash === "#" + path) router.resolve(); else location.hash = "#" + path; };
  router.resolve = async function () {
    if (!started || !HOS.app.user) return;
    release(); controller = new AbortController();
    const signal = controller.signal;
    router.signal = signal;
    const path = router.current().split("?")[0];
    const host = document.getElementById("page"), el = document.createElement("div");
    host.replaceChildren(el); el.innerHTML = HOS.UI.spinner();
    global.scrollTo(0, 0);
    HOS.app.setHead("加载中…"); document.body.classList.remove("nav-open");
    let matched, params = {};
    try {
      for (const route of router.routes) {
        const match = path.match(route.regex);
        if (match) { matched = route; route.keys.forEach((key, i) => { params[key] = decodeURIComponent(match[i + 1]); }); break; }
      }
      const top = "/" + (path.split("/")[1] || "dashboard");
      document.querySelectorAll("#sidebar-nav a").forEach(a => {
        const active = a.dataset.path === top; a.classList.toggle("active", active);
        if (active) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current");
      });
      if (!matched) { HOS.app.setHead("页面不存在"); el.innerHTML = '<div class="empty">找不到此页面。<a href="#/dashboard">返回工作台</a></div>'; return; }
      await matched.page({ params, el, path, query: router.query(), signal,
        isActive: () => !signal.aborted, onLeave: router.onLeave });
    } catch (e) {
      if (signal.aborted || e.name === "AbortError") return;
      el.innerHTML = '<div class="card empty">加载失败：' + HOS.UI.escapeHtml(e.message) + '<p><button class="btn" id="route-retry">重新加载</button></p></div>';
      el.querySelector("#route-retry").onclick = router.resolve;
    }
  };
  router.start = function () {
    if (!started) { started = true; window.addEventListener("hashchange", router.resolve); }
    if (!location.hash) location.hash = "#/dashboard"; else router.resolve();
  };
  router.stop = function () { started = false; release(); router.signal = null; window.removeEventListener("hashchange", router.resolve); };
  HOS.router = router;
})(window);
