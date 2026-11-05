(function(global) {
  "use strict";
  const HOS=global.HOS, {Api,UI}=HOS, E=UI.escapeHtml;
  HOS.router.register("/traces", async ctx => {
    HOS.app.setHead("全流程推导链路追踪", '<button class="btn" id="trace-refresh">刷新</button>');
    ctx.el.innerHTML='<div class="page-intro"><h1>每次运行，都有迹可循</h1><p>按业务和状态筛选轨迹，查看节点耗时、失败原因与事件记录。</p></div><div class="grid grid-4" id="trace-overview"></div><div class="card"><form class="toolbar" id="trace-filter"><label>业务类型<input name="businessType" placeholder="全部业务" /></label><label>状态<select name="status"><option value="">全部状态</option><option>RUNNING</option><option>SUCCEEDED</option><option>FAILED</option></select></label><label>业务编号<input name="businessId" /></label><button class="btn btn-primary">查询</button></form><div id="trace-table"></div><div class="toolbar" id="trace-pager"></div></div>';
    let page=1;
    const form=ctx.el.querySelector("#trace-filter");
    async function load(){
      const params=new URLSearchParams(new FormData(form)); params.set("page",page); params.set("size",20);
      const data=await Api.get("/api/v1/traces?"+params);
      if(!ctx.isActive())return;
      ctx.el.querySelector("#trace-table").innerHTML=UI.table([{key:"businessType",label:"业务"},{key:"businessId",label:"业务编号",cls:"code"},{key:"status",label:"状态",render:UI.pill},{key:"durationMs",label:"耗时",render:v=>v==null?"运行中":E(v)+"ms"},{key:"spanCount",label:"节点"},{key:"acceptedAt",label:"开始时间",render:UI.date},{key:"op",label:"操作",render:(_,r)=>'<a class="btn btn-sm" href="#/traces/'+encodeURIComponent(r.traceId)+'">查看链路</a>'}],data.items,"暂无轨迹。完成资料录入或问答后可在这里追踪。");
      const pager=ctx.el.querySelector("#trace-pager");
      pager.innerHTML='<span class="muted">共 '+E(data.total)+' 条 · 第 '+page+' 页</span><div class="spacer"></div><button class="btn" data-prev '+(page<=1?"disabled":"")+'>上一页</button><button class="btn" data-next '+(page*20>=data.total?"disabled":"")+'>下一页</button>';
      pager.querySelector("[data-prev]").onclick=()=>{page--;load().catch(UI.toastErr);}; pager.querySelector("[data-next]").onclick=()=>{page++;load().catch(UI.toastErr);};
    }
    form.onsubmit=e=>{e.preventDefault();page=1;load().catch(UI.toastErr);};
    document.getElementById("trace-refresh").onclick=()=>load().catch(UI.toastErr);
    await load();
    const overview=await Api.get("/api/v1/traces/overview");
    if(!ctx.isActive())return;
    ctx.el.querySelector("#trace-overview").innerHTML=[["轨迹总数",overview.traces],["运行中",overview.runningTraces],["失败轨迹",overview.failedTraces],["执行节点",overview.spans]].map(([label,value])=>'<div class="stat"><div class="label">'+label+'</div><div class="value">'+E(value)+'</div></div>').join("");
  });
  HOS.router.register("/traces/:traceId", async ctx=>{
    const id=encodeURIComponent(ctx.params.traceId), data=await Api.get("/api/v1/traces/"+id);
    HOS.app.setHead("运行链路详情", '<a class="btn" href="#/traces">返回轨迹列表</a><button class="btn" id="trace-export">导出轨迹</button>');
    ctx.el.innerHTML='<div class="card"><div class="card-heading"><h3>'+E(data.trace.businessType)+'</h3>'+UI.pill(data.trace.status)+'</div><p class="code">'+E(data.trace.traceId)+'</p>'+ (data.trace.errorMessage?'<div class="notice danger">'+E(data.trace.errorMessage)+'</div>':"")+(data.warnings||[]).map(w=>'<p class="notice">'+E(w)+'</p>').join("")+'</div><div class="card"><h3>执行节点</h3>'+spans(data.spans||[])+'</div><div class="card"><h3>运行事件</h3>'+UI.table([{key:"name",label:"事件"},{key:"createdAt",label:"时间",render:UI.date},{key:"detail",label:"详情"}],data.events,"暂无事件")+'</div>';
    document.getElementById("trace-export").onclick=()=>Api.download("/api/v1/traces/"+id+"/export","trace-"+ctx.params.traceId+".json").catch(UI.toastErr);
  });
  function spans(items){return items.map(s=>'<details class="trace-span" open><summary><strong>'+E(s.name)+'</strong> '+UI.pill(s.status)+' <span class="muted">'+E(s.service||"")+' · '+E(s.durationMs==null?"运行中":s.durationMs+"ms")+'</span></summary>'+(s.errorMessage?'<p class="notice danger">'+E(s.errorMessage)+'</p>':"")+'<dl class="kv">'+Object.entries(s.attributes||{}).map(([key,value])=>'<dt>'+E(key)+'</dt><dd>'+UI.fmt(value)+'</dd>').join("")+'</dl>'+spans(s.children||[])+'</details>').join("")||'<p class="muted">暂无节点</p>';}
})(window);
