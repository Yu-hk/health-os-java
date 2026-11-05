/* 应用引导：登录态、框架、导航、头部动作、路由启动。 */
(function (global) {
  "use strict";
  const HOS = global.HOS;
  const Api = HOS.Api, UI = HOS.UI;

  const app = {};
  HOS.app = app;

  const NAV = [
    { path: "/dashboard", label: "系统概览", icon: "◉" },
    { path: "/patients", label: "患者档案", icon: "👤" },
    { path: "/intake", label: "资料录入", icon: "📥" },
    { path: "/care", label: "健康管理", icon: "🩺" },
    { path: "/classics", label: "典籍处理", icon: "📚" },
    { path: "/qa", label: "智能问答", icon: "💬" },
    { path: "/memory", label: "患者记忆", icon: "🧠" },
  ];

  app.user = null;

  app.setHead = function (title, actionsHtml) {
    document.getElementById("breadcrumb").textContent = title || "";
    const host = document.getElementById("head-actions");
    host.innerHTML = "";
    if (actionsHtml) host.innerHTML = actionsHtml;
  };

  app.go = (p) => HOS.router.go(p);

  function renderNav() {
    const nav = document.getElementById("sidebar-nav");
    nav.innerHTML = NAV.map(n =>
      '<a data-path="' + n.path + '" href="#' + n.path + '"><span>' + n.icon + "</span><span>" + n.label + "</span></a>"
    ).join("");
  }

  function showShell() {
    document.getElementById("login-screen").classList.add("hidden");
    document.getElementById("app-shell").classList.remove("hidden");
    document.getElementById("current-user").textContent =
      (app.user ? app.user.username + "（" + (app.user.roles || []).join("、") + "）" : "");
    renderNav();
    HOS.router.start();
  }

  function showLogin(msg) {
    document.getElementById("app-shell").classList.add("hidden");
    document.getElementById("login-screen").classList.remove("hidden");
    const err = document.getElementById("login-error");
    if (msg) { err.textContent = msg; err.classList.remove("hidden"); }
    else err.classList.add("hidden");
  }

  async function doLogin(username, password) {
    const btn = document.getElementById("login-btn");
    btn.disabled = true; btn.textContent = "登录中…";
    try {
      await Api.login(username, password);
      app.user = await Api.me();
      showShell();
      UI.toast("登录成功", "ok");
    } catch (e) {
      document.getElementById("login-error").textContent = e.message;
      document.getElementById("login-error").classList.remove("hidden");
    } finally {
      btn.disabled = false; btn.textContent = "登录";
    }
  }

  async function doLogout() {
    try { await Api.logout(); } catch (e) { /* 忽略 */ }
    app.user = null;
    showLogin();
  }

  async function boot() {
    Api.onUnauthorized = () => { app.user = null; showLogin("登录已失效，请重新登录"); };
    document.getElementById("login-form").addEventListener("submit", (e) => {
      e.preventDefault();
      doLogin(document.getElementById("login-username").value.trim(),
              document.getElementById("login-password").value);
    });
    document.getElementById("logout-btn").addEventListener("click", doLogout);

    try {
      app.user = await Api.me();
      showShell();
    } catch (e) {
      // 401 或未登录 → 显示登录页
      showLogin();
    }
  }

  document.addEventListener("DOMContentLoaded", boot);
})(window);
