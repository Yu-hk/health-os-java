(function (global) {
  "use strict";
  const HOS = global.HOS, { Api, UI } = HOS, E = UI.escapeHtml;
  const base = "/api/v1/system/model-routes";
  HOS.router.register("/models", async ctx => {
    HOS.app.setHead("主线业务模型配置", '<button class="btn" id="models-refresh">刷新</button>');
    document.getElementById("models-refresh").onclick = HOS.router.resolve;
    const permissions = await Api.get(base + "/permissions");
    if (!permissions.canManageModelRoutes) { ctx.el.innerHTML = '<div class="card empty">当前账号没有模型配置管理权限，请联系管理员。</div>'; return; }
    let data;
    try { data = await Api.get(base); }
    catch (e) {
      if (e.code !== "MODEL_ROUTING_DISABLED") throw e;
      ctx.el.innerHTML = '<div class="card"><h3>模型配置中心尚未启用</h3><p class="muted">当前环境使用服务端已配置的模型。启用模型配置中心后，可在这里管理各业务主线的模型和版本。</p><a class="btn" href="#/dashboard">返回工作台</a></div>';
      return;
    }
    ctx.el.innerHTML = '<div class="page-intro"><h1>模型配置中心</h1><p>为各条业务主线配置模型与调用预算，查看探测结果和历史版本。</p></div><div class="grid grid-3">' + data.items.map(row =>
      '<article class="card model-card"><div class="card-heading"><h3>' + E(row.label) + '</h3>' + UI.pill(row.enabled ? "ACTIVE" : "DISABLED") + '</div><p class="model-name">' + E(row.model) + '</p><dl class="kv"><dt>供应商</dt><dd>' + E(row.providerId) + '</dd><dt>版本</dt><dd>v' + E(row.version) + '</dd><dt>凭据</dt><dd>' + (row.credentialConfigured ? "已配置" : "未配置") + '</dd><dt>最近探测</dt><dd>' + (row.probe ? UI.pill(row.probe.status) : "尚未探测") + '</dd></dl><div class="toolbar"><button class="btn btn-primary" data-edit="' + E(row.pipelineKey) + '">编辑配置</button><button class="btn" data-history="' + E(row.pipelineKey) + '">版本历史</button></div></article>').join("") + '</div>';
    ctx.el.querySelectorAll("[data-edit]").forEach(button => button.onclick = () => edit(data.items.find(row => row.pipelineKey === button.dataset.edit)));
    ctx.el.querySelectorAll("[data-history]").forEach(button => button.onclick = () => history(data.items.find(row => row.pipelineKey === button.dataset.history)));
  });
  function edit(row) {
    const fields = [
      { name: "enabled", label: "启用状态", type: "select", value: String(row.enabled), options: [{value:"true",label:"启用"},{value:"false",label:"停用"}] },
      { name: "providerId", label: "供应商标识", value: row.providerId, required: true },
      { name: "model", label: "模型名称", value: row.model, required: true },
      { name: "baseUrl", label: "服务地址", value: row.baseUrl, required: true, type: "url" },
      { name: "protocol", label: "协议", type: "select", value: row.protocol, options: ["chat","responses","chat_tool"].map(value => ({value,label:value})) },
      { name: "connectionMode", label: "连接方式", type: "select", value: row.connectionMode, options: [{value:"direct",label:"直连"},{value:"system_proxy",label:"系统代理"}] },
      { name: "temperature", label: "采样温度", type: "number", value: row.parameters.temperature },
      { name: "maxOutputTokens", label: "最大输出 Token", type: "number", value: row.parameters.maxOutputTokens },
      { name: "replacementApiKey", label: "替换 API Key", type: "password", hint: "留空保留现有凭据；密钥不会回显或保存到浏览器存储。" },
      { name: "clearCredential", label: "清除已有凭据", type: "select", options: [{value:"false",label:"保留"},{value:"true",label:"清除"}] },
      { name: "changeReason", label: "变更原因", type: "textarea" },
    ];
    const body = UI.formFields(fields);
    body.querySelector('[name="replacementApiKey"]').autocomplete = "new-password";
    const output = document.createElement("p"); body.appendChild(output);
    const request = () => {
      const f = UI.readForm(body);
      return { expectedVersion: row.version, config: { enabled: f.enabled === "true", providerId: f.providerId, model: f.model, baseUrl: f.baseUrl, protocol: f.protocol, connectionMode: f.connectionMode, faultPolicy: row.faultPolicy, parameters: Object.assign({}, row.parameters, {temperature: Number(f.temperature), maxOutputTokens: Number(f.maxOutputTokens)}) }, replacementApiKey: f.replacementApiKey || null, clearCredential: f.clearCredential === "true", changeReason: f.changeReason || null };
    };
    UI.modal({title: row.label + " · v" + row.version, body, actions: [
      {label: "取消"},
      {label: "探测（会调用模型）", onClick: async () => { const result = await Api.post(base + "/" + encodeURIComponent(row.pipelineKey) + "/probe", request()); output.textContent = result.status + " · " + (result.message || "") + (result.latencyMs == null ? "" : " · " + result.latencyMs + "ms"); return false; }},
      {label: "保存新版本", primary:true, onClick: async () => { await Api.put(base + "/" + encodeURIComponent(row.pipelineKey), request()); body.querySelector('[name="replacementApiKey"]').value = ""; UI.toast("配置已保存", "ok"); HOS.router.resolve(); }},
    ]});
  }
  async function history(row) {
    try {
      const data = await Api.get(base + "/" + encodeURIComponent(row.pipelineKey) + "/versions");
      const body = document.createElement("div");
      body.innerHTML = UI.table([{key:"version",label:"版本"},{key:"model",label:"模型"},{key:"updatedBy",label:"修改人"},{key:"changeReason",label:"原因"},{key:"updatedAt",label:"时间",render:UI.date},{key:"action",label:"操作",render:(_, item) => item.version === row.version ? "当前版本" : '<button class="btn btn-sm" data-rollback="' + item.version + '">回滚</button>'}], data.items);
      UI.modal({title: row.label + " · 版本历史", body});
      body.querySelectorAll("[data-rollback]").forEach(button => button.onclick = () => UI.modal({title:"回滚配置", body:"将以所选历史配置创建新版本，历史记录会保留。", actions:[{label:"取消"},{label:"确认回滚",primary:true,onClick:async()=>{await Api.post(base + "/" + encodeURIComponent(row.pipelineKey) + "/rollback", {expectedVersion:row.version,targetVersion:Number(button.dataset.rollback)}); UI.toast("已创建回滚版本","ok"); HOS.router.resolve();}}]}));
    } catch (e) { UI.toastErr(e); }
  }
})(window);
