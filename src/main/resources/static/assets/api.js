/* 全局 API 客户端：同源由 Spring 托管，凭据走 Cookie（same-origin 自动带）。 */
(function (global) {
  "use strict";

  const Api = {};

  Api.onUnauthorized = null; // 由 app.js 注入：登录失效时回调

  async function request(method, path, opts) {
    opts = opts || {};
    const headers = {};
    let body = undefined;
    if (opts.form) {
      body = opts.form; // FormData，浏览器自动设 Content-Type
    } else if (opts.body !== undefined && opts.body !== null) {
      headers["Content-Type"] = "application/json;charset=UTF-8";
      body = JSON.stringify(opts.body);
    }
    let resp;
    try {
      resp = await fetch(path, {
        method: method,
        headers: headers,
        body: body,
        credentials: "same-origin",
      });
    } catch (e) {
      throw new Error("网络错误，无法连接到服务：" + e.message);
    }

    // 文件下载（blob）
    if (opts.blob) {
      if (!resp.ok) throw new Error("下载失败，HTTP " + resp.status);
      return resp.blob();
    }

    const text = await resp.text();
    let data = null;
    if (text) {
      try { data = JSON.parse(text); } catch (e) { data = text; }
    }

    if (resp.status === 401) {
      if (typeof Api.onUnauthorized === "function") Api.onUnauthorized();
      const msg = (data && data.message) || "登录已失效，请重新登录";
      throw new ApiError("UNAUTHORIZED", msg, resp.status, data);
    }
    if (!resp.ok) {
      const msg = (data && (data.message || data.error)) || ("HTTP " + resp.status);
      const code = (data && data.code) || ("HTTP_" + resp.status);
      throw new ApiError(code, msg, resp.status, data);
    }
    return data;
  }

  function ApiError(code, message, status, data) {
    this.code = code;
    this.message = message;
    this.status = status;
    this.data = data;
  }
  ApiError.prototype = Object.create(Error.prototype);
  Api.ApiError = ApiError;

  Api.get = (p, o) => request("GET", p, o);
  Api.post = (p, b, o) => request("POST", p, Object.assign({ body: b }, o));
  Api.put = (p, b, o) => request("PUT", p, Object.assign({ body: b }, o));
  Api.patch = (p, b, o) => request("PATCH", p, Object.assign({ body: b }, o));
  Api.del = (p, b, o) => request("DELETE", p, Object.assign({ body: b }, o));

  // 业务便捷封装
  Api.login = (username, password) => Api.post("/api/v1/auth/login", { username, password });
  Api.logout = () => Api.post("/api/v1/auth/logout");
  Api.me = () => Api.get("/api/v1/auth/me");
  Api.health = () => Api.get("/health");
  Api.dependencies = () => Api.get("/health/dependencies");

  // 触发浏览器下载（导出 JSON / PDF / 产物）
  Api.download = async function (path, filename) {
    const blob = await request("GET", path, { blob: true });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = filename || "download";
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 2000);
  };

  Api.statusPill = function (status) {
    if (!status) return ["gray", status || "-"];
    const s = String(status).toUpperCase();
    if (["OK", "READY", "SUCCEEDED", "ACTIVE", "COMPLETE", "VERIFIED", "SIGNED"].includes(s)) return ["green", status];
    if (["DRAFT", "PENDING", "QUEUED", "RUNNING", "EXTRACTED", "CONFIRMED", "DISABLED"].includes(s)) return ["amber", status];
    if (["FAILED", "UNAVAILABLE", "REJECTED", "CANCELLED", "DEGRADED", "STALE", "ERROR"].includes(s)) return ["red", status];
    if (["ARCHIVED", "DELETED", "SKIPPED"].includes(s)) return ["gray", status];
    return ["blue", status];
  };

  global.HOS = global.HOS || {};
  global.HOS.Api = Api;
})(window);
