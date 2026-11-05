/* UI 基础组件：toast、modal、表格、表单、状态标签、分页等。 */
(function (global) {
  "use strict";
  const HOS = global.HOS = global.HOS || {};
  const UI = {};

  // ---------- toast ----------
  UI.toast = function (msg, type) {
    const host = document.getElementById("toast-host");
    const el = document.createElement("div");
    el.className = "toast " + (type || "");
    el.textContent = msg;
    host.appendChild(el);
    setTimeout(() => {
      el.style.opacity = "0";
      el.style.transition = "opacity .3s";
      setTimeout(() => el.remove(), 300);
    }, 3200);
  };

  UI.toastErr = function (e) {
    const msg = (e && e.message) ? e.message : String(e);
    UI.toast(msg, "err");
  };

  // ---------- modal ----------
  UI.modal = function ({ title, body, actions }) {
    const host = document.getElementById("modal-host");
    host.classList.remove("hidden");
    const modal = document.createElement("div");
    modal.className = "modal";
    const actHtml = (actions || []).map((a, i) =>
      '<button class="btn ' + (a.primary ? "btn-primary" : "") + ' ' + (a.danger ? "btn-danger" : "") + '" data-i="' + i + '">' + a.label + "</button>"
    ).join("");
    modal.innerHTML =
      '<div class="modal-head"><span>' + (title || "") + '</span><button class="modal-close">×</button></div>' +
      '<div class="modal-body"></div>' +
      (actHtml ? '<div class="modal-foot">' + actHtml + "</div>" : "");
    host.innerHTML = "";
    host.appendChild(modal);
    const bodyEl = modal.querySelector(".modal-body");
    if (typeof body === "string") bodyEl.innerHTML = body;
    else if (body) bodyEl.appendChild(body);

    function close() { host.classList.add("hidden"); host.innerHTML = ""; }
    modal.querySelector(".modal-close").addEventListener("click", close);
    host.addEventListener("click", function onBackdrop(ev) {
      if (ev.target === host) { close(); host.removeEventListener("click", onBackdrop); }
    });
    (actions || []).forEach((a, i) => {
      const btn = modal.querySelector('.modal-foot .btn[data-i="' + i + '"]');
      if (!btn) return;
      btn.addEventListener("click", () => {
        const r = a.onClick ? a.onClick() : true;
        if (r !== false) close();
      });
    });
    return { close: close, body: bodyEl };
  };

  // ---------- 状态标签 ----------
  UI.pill = function (status) {
    const p = HOS.Api.statusPill(status);
    return '<span class="pill ' + p[0] + '">' + escapeHtml(p[1]) + "</span>";
  };

  // ---------- 表格 ----------
  // columns: [{key, label, render?, cls?}]
  UI.table = function (columns, rows, emptyText) {
    if (!rows || rows.length === 0) {
      return '<div class="empty">' + (emptyText || "暂无数据") + "</div>";
    }
    const head = "<tr>" + columns.map(c => "<th>" + escapeHtml(c.label) + "</th>").join("") + "</tr>";
    const body = rows.map(r => {
      return "<tr>" + columns.map(c => {
        let v = c.render ? c.render(r[c.key], r) : fmt(r[c.key]);
        return '<td class="' + (c.cls || "") + '">' + (v == null || v === "" ? '<span class="muted">-</span>' : v) + "</td>";
      }).join("") + "</tr>";
    }).join("");
    return '<table><thead>' + head + "</thead><tbody>" + body + "</tbody></table>";
  };

  // ---------- 表单 ----------
  // fields: [{name, label, type?, placeholder?, options?, required?, value?, hint?}]
  UI.formFields = function (fields, mount) {
    const wrap = document.createElement("div");
    fields.forEach(f => {
      const label = document.createElement("label");
      label.className = "field";
      label.innerHTML = "<span>" + escapeHtml(f.label) + (f.required ? " *" : "") + "</span>";
      let control;
      if (f.type === "select") {
        control = document.createElement("select");
        (f.options || []).forEach(o => {
          const opt = document.createElement("option");
          opt.value = o.value; opt.textContent = o.label;
          if (o.value === f.value) opt.selected = true;
          control.appendChild(opt);
        });
      } else if (f.type === "textarea") {
        control = document.createElement("textarea");
        control.placeholder = f.placeholder || "";
        control.value = f.value || "";
      } else {
        control = document.createElement("input");
        control.type = f.type || "text";
        control.placeholder = f.placeholder || "";
        control.value = f.value || "";
      }
      control.name = f.name;
      if (f.required) control.required = true;
      label.appendChild(control);
      if (f.hint) {
        const h = document.createElement("div");
        h.className = "muted";
        h.style.fontSize = "12px";
        h.style.marginTop = "3px";
        h.textContent = f.hint;
        label.appendChild(h);
      }
      wrap.appendChild(label);
    });
    if (mount) mount.appendChild(wrap);
    return wrap;
  };

  UI.readForm = function (formEl) {
    const out = {};
    Array.from(formEl.querySelectorAll("input, select, textarea")).forEach(el => {
      if (!el.name) return;
      if ((el.type === "checkbox")) out[el.name] = el.checked;
      else if (el.value === "") out[el.name] = null;
      else out[el.name] = el.value;
    });
    return out;
  };

  // ---------- 工具 ----------
  function escapeHtml(s) {
    if (s == null) return "";
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }
  UI.escapeHtml = escapeHtml;

  function fmt(v) {
    if (v == null) return "";
    if (typeof v === "object") return JSON.stringify(v);
    return escapeHtml(v);
  }
  UI.fmt = fmt;

  UI.date = function (v) {
    if (!v) return "-";
    const d = new Date(v);
    if (isNaN(d)) return escapeHtml(v);
    return d.toLocaleString("zh-CN", { hour12: false });
  };

  UI.ago = function (v) {
    if (!v) return "-";
    const d = new Date(v).getTime();
    const diff = Date.now() - d;
    if (diff < 0) return UI.date(v);
    const s = Math.floor(diff / 1000);
    if (s < 60) return s + " 秒前";
    const m = Math.floor(s / 60);
    if (m < 60) return m + " 分钟前";
    const h = Math.floor(m / 60);
    if (h < 24) return h + " 小时前";
    return Math.floor(h / 24) + " 天前";
  };

  UI.spinner = function () { return '<span class="spinner"></span>'; };

  HOS.UI = UI;
})(window);
