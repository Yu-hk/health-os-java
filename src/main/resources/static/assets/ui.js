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
    if (e && e.name === "AbortError") return;
    const msg = (e && e.message) ? e.message : String(e);
    UI.toast(msg, "err");
  };

  // ---------- modal ----------
  UI.modal = function ({ title, body, actions }) {
    const host = document.getElementById("modal-host");
    if (UI.activeModal) UI.activeModal.close(true);
    const previousFocus = document.activeElement;
    let busy = false;
    host.classList.remove("hidden");
    const modal = document.createElement("div");
    modal.className = "modal";
    const actHtml = (actions || []).map((a, i) =>
      '<button class="btn ' + (a.primary ? "btn-primary" : "") + ' ' + (a.danger ? "btn-danger" : "") + '" data-i="' + i + '">' + a.label + "</button>"
    ).join("");
    modal.setAttribute("role", "dialog");
    modal.setAttribute("aria-modal", "true");
    modal.setAttribute("aria-label", title || "对话框");
    modal.innerHTML =
      '<div class="modal-head"><span>' + escapeHtml(title || "") + '</span><button class="modal-close" aria-label="关闭">×</button></div>' +
      '<div class="modal-body"></div>' +
      (actHtml ? '<div class="modal-foot">' + actHtml + "</div>" : "");
    host.innerHTML = "";
    host.appendChild(modal);
    const bodyEl = modal.querySelector(".modal-body");
    if (typeof body === "string") bodyEl.innerHTML = body;
    else if (body) bodyEl.appendChild(body);

    function close(force) {
      if (busy && force !== true) return;
      if (host.firstElementChild !== modal) return;
      host.classList.add("hidden"); host.innerHTML = "";
      host.removeEventListener("click", onBackdrop);
      document.removeEventListener("keydown", onKey);
      UI.activeModal = null;
      if (previousFocus && previousFocus.isConnected) previousFocus.focus();
    }
    function onBackdrop(ev) { if (ev.target === host) close(); }
    function onKey(ev) {
      if (ev.key === "Escape") { ev.preventDefault(); close(); }
      if (ev.key !== "Tab") return;
      const nodes = Array.from(modal.querySelectorAll('button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),a[href]'));
      const first = nodes[0], last = nodes[nodes.length - 1];
      if (ev.shiftKey && document.activeElement === first) { ev.preventDefault(); last.focus(); }
      else if (!ev.shiftKey && document.activeElement === last) { ev.preventDefault(); first.focus(); }
    }
    modal.querySelector(".modal-close").addEventListener("click", close);
    host.addEventListener("click", onBackdrop);
    document.addEventListener("keydown", onKey);
    (actions || []).forEach((a, i) => {
      const btn = modal.querySelector('.modal-foot .btn[data-i="' + i + '"]');
      if (!btn) return;
      btn.addEventListener("click", async () => {
        if (busy) return;
        if (a.onClick) {
          const invalid = Array.from(bodyEl.querySelectorAll("input,select,textarea")).find(input => !input.checkValidity());
          if (invalid) { invalid.reportValidity(); return; }
        }
        busy = true;
        const label = btn.textContent;
        modal.querySelectorAll("button").forEach(button => { button.disabled = true; });
        if (a.onClick) btn.textContent = "处理中…";
        try {
          const result = a.onClick ? await a.onClick() : true;
          busy = false;
          if (result !== false) close();
        } catch (e) { UI.toastErr(e); }
        finally {
          busy = false; btn.textContent = label;
          modal.querySelectorAll("button").forEach(button => { button.disabled = false; });
        }
      });
    });
    const first = bodyEl.querySelector("input,select,textarea") || modal.querySelector("button");
    if (first) first.focus();
    return UI.activeModal = { close: close, body: bodyEl };
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
    return '<div class="table-scroll"><table><thead>' + head + "</thead><tbody>" + body + "</tbody></table></div>";
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
        control.value = f.value == null ? "" : f.value;
      } else {
        control = document.createElement("input");
        control.type = f.type || "text";
        control.placeholder = f.placeholder || "";
        control.value = f.value == null ? "" : f.value;
      }
      control.name = f.name;
      const query = HOS.router && HOS.router.query ? HOS.router.query() : {};
      if (!control.value && query[f.name]) control.value = query[f.name];
      if (f.required) control.required = true;
      label.appendChild(control);
      if (["patientId", "documentId", "examId"].includes(f.name) && f.type !== "select") UI.resourcePicker(control, f.name);
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
    if (typeof v === "object") return escapeHtml(JSON.stringify(v));
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
  UI.valid = function (root) {
    const invalid = Array.from(root.querySelectorAll("input,select,textarea")).find(input => !input.checkValidity());
    if (invalid) { invalid.reportValidity(); return false; } return true;
  };
  UI.operationId = function () {
    if (!global.crypto || !global.crypto.getRandomValues) throw new Error("当前浏览器不支持安全随机标识，请更换浏览器后重试");
    return "op_" + Array.from(global.crypto.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, "0")).join("");
  };
  let resourceSerial = 0;
  UI.resourcePicker = async function (input, kind) {
    const endpoint = {patientId:"patients",documentId:"classics",examId:"patient-intakes"}[kind];
    const list = document.createElement("datalist"); list.id = "resource-" + (++resourceSerial);
    input.setAttribute("list", list.id); input.placeholder = "选择已有记录或输入编号";
    input.insertAdjacentElement("afterend", list);
    try {
      const data = await HOS.Api.get("/api/v1/" + endpoint + "?size=200");
      (data.items || []).forEach(row => { const option = document.createElement("option"); option.value = row.id; option.label = row.name || row.title || row.sourceFilename || row.id; list.appendChild(option); });
    } catch (e) { if (e.name !== "AbortError") input.placeholder = "列表加载失败，可输入编号或重新打开"; }
  };

  UI.poll = function (ctx, callback, delay) {
    let timer, stopped = false;
    const stop = () => { stopped = true; clearTimeout(timer); };
    ctx.onLeave(stop);
    async function tick() {
      if (stopped || !ctx.isActive()) return;
      try { if (await callback() === false) return stop(); }
      catch (e) { if (!ctx.isActive()) return; UI.toastErr(e); return stop(); }
      if (!stopped && ctx.isActive()) timer = setTimeout(tick, delay || 2500);
    }
    tick(); return stop;
  };
  UI.listTools = function (ctx, host, page, path) {
    const search = document.createElement("label"); search.className = "field";
    search.innerHTML = '<span>筛选当前页</span><input type="search" placeholder="输入姓名、标题、状态或编号" />';
    host.prepend(search);
    search.querySelector("input").oninput = event => host.querySelectorAll("tbody tr").forEach(row => { row.hidden = !row.textContent.toLowerCase().includes(event.target.value.toLowerCase()); });
    const pager = document.createElement("div"); pager.className = "toolbar";
    const current = Number(page.page) || 1, size = Number(page.size) || 50, total = Number(page.total) || 0;
    pager.innerHTML = '<span class="muted">共 ' + total + ' 条 · 第 ' + current + ' 页</span><div class="spacer"></div><button class="btn" data-prev ' + (current <= 1 ? 'disabled' : '') + '>上一页</button><button class="btn" data-next ' + (current * size >= total ? 'disabled' : '') + '>下一页</button>';
    pager.querySelector("[data-prev]").onclick = () => HOS.router.go(path + "?page=" + (current - 1));
    pager.querySelector("[data-next]").onclick = () => HOS.router.go(path + "?page=" + (current + 1));
    host.appendChild(pager);
  };

  HOS.UI = UI;
})(window);
