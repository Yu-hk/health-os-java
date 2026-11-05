(function(global){
  "use strict";
  const HOS=global.HOS,{Api,UI}=HOS,E=UI.escapeHtml;
  const scenarios={progress:{label:"患者进展",scopes:["memory","pipeline"]},execution:{label:"计划执行",scopes:["pipeline","plan"]},statistics:{label:"患者统计",scopes:["statistics"]}};
  HOS.router.register("/agents",async ctx=>{
    HOS.app.setHead("Agent 与 MCP 服务实验室",'<button class="btn" id="agent-refresh">刷新</button>');
    const [catalog,runs,patients]=await Promise.all([Api.get("/api/v1/agent-lab/catalog"),Api.get("/api/v1/collaborations?limit=20"),Api.get("/api/v1/patients?size=200")]);
    ctx.el.innerHTML='<div class="page-intro"><h1>协同分析与工具验证</h1><p>按患者授权范围执行协同，查看角色交接和工具调用回执。</p></div><div class="grid grid-3">'+catalog.agents.map(agent=>'<div class="card"><h3>'+E(agent.name)+'</h3><p>'+E(agent.description)+'</p><span class="muted">'+E(agent.executionStatus)+' · '+agent.tools.length+' 个工具</span></div>').join("")+'</div><div class="grid grid-2"><div class="card"><h3>发起患者协同</h3><div id="agent-form"></div></div><div class="card"><h3>MCP 服务与工具</h3>'+catalog.services.map(service=>'<p><b>'+E(service.name)+'</b> '+UI.pill(service.status)+' <span class="muted">'+E(service.transport)+'</span></p>').join("")+'<div id="tool-form"></div><div id="tool-result" role="status"></div></div></div><div class="card"><h3>最近协同运行</h3>'+UI.table([{key:"scenario",label:"场景"},{key:"patientId",label:"患者编号",cls:"code"},{key:"status",label:"状态",render:UI.pill},{key:"createdAt",label:"创建时间",render:UI.date},{key:"op",label:"操作",render:(_,row)=>'<a class="btn btn-sm" href="#/agents/run/'+encodeURIComponent(row.id)+'">查看运行</a>'}],runs.items,"暂无运行")+'</div>';
    document.getElementById("agent-refresh").onclick=HOS.router.resolve;
    const options=[{value:"",label:"请选择患者"}].concat(patients.items.map(p=>({value:p.id,label:p.name+" · "+p.id})));
    const form=UI.formFields([{name:"patientId",label:"患者",type:"select",required:true,options},{name:"scenario",label:"协同场景",type:"select",options:Object.entries(scenarios).map(([value,item])=>({value,label:item.label}))},{name:"instruction",label:"分析任务",type:"textarea",required:true,placeholder:"请基于现有档案说明患者当前进展"}]);
    ctx.el.querySelector("#agent-form").appendChild(form);
    const submit=document.createElement("button");submit.className="btn btn-primary";submit.textContent="发起协同";form.appendChild(submit);
    let operationId;
    submit.onclick=async()=>{
      if(!UI.valid(form))return;
      submit.disabled=true;
      try{const f=UI.readForm(form);operationId=operationId||UI.operationId();const result=await Api.post("/api/v1/patients/"+encodeURIComponent(f.patientId)+"/collaborations",{scenario:f.scenario,scopes:scenarios[f.scenario].scopes,instruction:f.instruction,operationId});HOS.router.go("/agents/run/"+result.runId);}catch(e){UI.toastErr(e);}finally{submit.disabled=false;}
    };
    form.addEventListener("input",()=>{operationId=null;});
    const toolForm=UI.formFields([{name:"patientId",label:"患者",type:"select",required:true,options},{name:"tool",label:"只读工具",type:"select",options:catalog.configuredTools.map(t=>({value:t.name,label:t.title}))}]);
    ctx.el.querySelector("#tool-form").appendChild(toolForm);
    const actions=document.createElement("div");actions.className="toolbar";actions.innerHTML='<button class="btn" data-probe>握手与发现</button><button class="btn btn-primary" data-call>调用所选工具</button>';toolForm.appendChild(actions);
    async function operate(kind){
      if(!UI.valid(toolForm))return;
      actions.querySelectorAll("button").forEach(b=>b.disabled=true);
      try{const f=UI.readForm(toolForm),tool=catalog.configuredTools.find(t=>t.name===f.tool);const body={patientId:f.patientId,scopes:[tool.scope]};if(kind==="call"){body.tool=f.tool;body.arguments={};}const result=await Api.post("/api/v1/agent-lab/services/"+encodeURIComponent(catalog.serviceId)+"/"+kind,body);ctx.el.querySelector("#tool-result").innerHTML='<p>执行回执 '+UI.pill(result.receipt.status)+'</p><pre class="pre">'+E(JSON.stringify(result.output||result.receipt.detail,null,2))+'</pre>';}catch(e){UI.toastErr(e);}finally{actions.querySelectorAll("button").forEach(b=>b.disabled=false);}
    }
    actions.querySelector("[data-probe]").onclick=()=>operate("probe");actions.querySelector("[data-call]").onclick=()=>operate("call");
  });
  HOS.router.register("/agents/run/:runId",async ctx=>{
    const id=encodeURIComponent(ctx.params.runId);
    HOS.app.setHead("协同运行详情",'<a class="btn" href="#/agents">返回实验室</a><button class="btn" id="agent-run-refresh">刷新</button>');
    document.getElementById("agent-run-refresh").onclick=HOS.router.resolve;
    ctx.el.innerHTML='<div id="agent-run"></div>';
    UI.poll(ctx,async()=>{
      const run=await Api.get("/api/v1/collaborations/"+id);if(!ctx.isActive())return false;
      ctx.el.querySelector("#agent-run").innerHTML='<div class="card"><div class="card-heading"><h3>'+E(run.scenario)+'</h3>'+UI.pill(run.status)+'</div><p>'+E(run.instruction)+'</p>'+(run.error?'<p class="notice danger">'+E(run.error)+'</p>':"")+(run.answer?'<h3>协同结论</h3><p class="answer-text">'+E(run.answer.summary)+'</p><ul>'+(run.answer.progress||[]).map(item=>'<li>'+E(item)+'</li>').join("")+'</ul>':"")+'<div class="toolbar" id="agent-controls"></div></div><div class="card"><h3>角色与执行节点</h3>'+UI.table([{key:"name",label:"节点"},{key:"role",label:"角色"},{key:"status",label:"状态",render:UI.pill},{key:"summary",label:"摘要"},{key:"error",label:"错误"}],run.nodes)+'</div>';
      const controls=ctx.el.querySelector("#agent-controls");
      if(["QUEUED","RUNNING","FAILED"].includes(run.status)){const action=run.status==="FAILED"?"retry":"cancel",button=document.createElement("button");button.className="btn";button.textContent=action==="retry"?"重试失败运行":"取消运行";button.onclick=async()=>{button.disabled=true;try{await Api.post("/api/v1/collaborations/"+id+"/"+action,{});HOS.router.resolve();}catch(e){UI.toastErr(e);button.disabled=false;}};controls.appendChild(button);}
      return ["QUEUED","RUNNING"].includes(run.status);
    });
  });
})(window);
