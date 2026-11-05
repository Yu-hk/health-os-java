/* 应用引导：登录态、框架、导航、头部动作、路由启动。 */
(function (global) {
  "use strict";
  const HOS = global.HOS;
  const Api = HOS.Api, UI = HOS.UI;

  const app = {};
  HOS.app = app;

  const NAV = [
    { group: "驾驶舱", path: "/dashboard", label: "工作台总览", icon: "01" },
    { group: "核心工作空间", path: "/patients", label: "客户全周期健康档案", icon: "02" },
    { path: "/intake", label: "资料录入与核查", icon: "03" },
    { path: "/care", label: "健康报告与执行计划", icon: "04" },
    { path: "/memory", label: "患者长期记忆", icon: "05" },
    { group: "公共医学典籍资产", path: "/classics", label: "医学典籍资产管理", icon: "06" },
    { group: "智能分析与协议编排", path: "/qa", label: "智能问答大厅", icon: "07" },
    { path: "/agents", label: "Agent 与 MCP 实验室", icon: "08" },
    { group: "系统治理与全链路运维", path: "/models", label: "主线业务模型配置", icon: "09" },
    { path: "/traces", label: "全流程推导链路追踪", icon: "10" },
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
      (n.group ? '<div class="nav-group-label">' + n.group + '</div>' : '') + '<a data-path="' + n.path + '" href="#' + n.path + '"><span class="nav-number" aria-hidden="true">' + n.icon + "</span><span>" + n.label + "</span></a>"
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
    HOS.router.stop();
    document.getElementById("page").replaceChildren();
    document.getElementById("login-password").value = "";
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
    const button = document.getElementById("logout-btn");
    button.disabled = true;
    try { await Api.logout(); app.user = null; showLogin(); }
    catch (e) { UI.toastErr(e); }
    finally { button.disabled = false; }
  }

  async function boot() {
    Api.onUnauthorized = () => { app.user = null; showLogin("登录已失效，请重新登录"); };
    document.getElementById("login-form").addEventListener("submit", (e) => {
      e.preventDefault();
      doLogin(document.getElementById("login-username").value.trim(),
              document.getElementById("login-password").value);
    });
    document.getElementById("logout-btn").addEventListener("click", doLogout);
    document.getElementById("nav-toggle").addEventListener("click", () => {
      const opened = document.body.classList.toggle("nav-open");
      document.getElementById("nav-toggle").setAttribute("aria-expanded", String(opened));
    });
    document.getElementById("nav-backdrop").addEventListener("click", () => document.body.classList.remove("nav-open"));

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
