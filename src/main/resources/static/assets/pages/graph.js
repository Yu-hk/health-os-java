(function(global){
  "use strict";
  const HOS=global.HOS,{Api,UI}=HOS,E=UI.escapeHtml;
  HOS.router.register("/classics/:documentId/graph",async ctx=>{
    const id=encodeURIComponent(ctx.params.documentId),base="/api/v1/classics/"+id+"/graph";
    HOS.app.setHead("医学典籍知识图谱",'<a class="btn" href="#/classics/'+id+'">返回典籍详情</a>');
    ctx.el.innerHTML='<div class="card"><div class="toolbar"><label>邻域深度<select id="graph-depth"><option value="1">1 跳关系</option><option value="2">2 跳关系</option><option value="3">3 跳关系</option></select></label><button class="btn" id="graph-reset">查看完整图谱</button><span class="muted">点击实体查看邻域，表格中可核对关系证据。</span></div><div id="graph-view"></div></div><div class="card"><h3>关系与证据</h3><div id="graph-evidence"></div></div>';
    async function load(entityId){
      const graph=await Api.get(base+(entityId?"/neighbors?entityId="+encodeURIComponent(entityId)+"&depth="+ctx.el.querySelector("#graph-depth").value:""));if(!ctx.isActive())return;
      const names=new Map(graph.nodes.map(n=>[n.id,n.name]));
      ctx.el.querySelector("#graph-evidence").innerHTML=UI.table([{key:"sourceId",label:"来源实体",render:v=>E(names.get(v)||v)},{key:"type",label:"关系"},{key:"targetId",label:"目标实体",render:v=>E(names.get(v)||v)},{key:"confidence",label:"置信度",render:v=>Math.round(v*100)+"%"},{key:"evidenceQuote",label:"原文证据",cls:"wrap"},{key:"pageFrom",label:"页码"}],graph.edges,"暂无关系。请确认典籍解析已完成。");
      const mount=ctx.el.querySelector("#graph-view");mount.innerHTML="";
      if(!graph.nodes.length){mount.innerHTML='<div class="empty">暂无图谱实体</div>';return;}
      const visible=graph.nodes.slice(0,80),positions=new Map(),ns="http://www.w3.org/2000/svg";
      const svg=document.createElementNS(ns,"svg");svg.setAttribute("viewBox","0 0 900 620");svg.setAttribute("role","img");svg.setAttribute("aria-label","典籍实体关系图，点击节点查看邻域");svg.classList.add("knowledge-graph");
      visible.forEach((node,i)=>{const a=2*Math.PI*i/visible.length;positions.set(node.id,{x:450+Math.cos(a)*330,y:310+Math.sin(a)*230});});
      graph.edges.forEach(edge=>{const a=positions.get(edge.sourceId),b=positions.get(edge.targetId);if(!a||!b)return;const line=document.createElementNS(ns,"line");Object.entries({x1:a.x,y1:a.y,x2:b.x,y2:b.y}).forEach(([k,v])=>line.setAttribute(k,v));svg.appendChild(line);});
      visible.forEach(node=>{const p=positions.get(node.id),group=document.createElementNS(ns,"g"),circle=document.createElementNS(ns,"circle"),text=document.createElementNS(ns,"text");group.setAttribute("transform","translate("+p.x+","+p.y+")");group.setAttribute("tabindex","0");group.setAttribute("role","button");group.setAttribute("aria-label",node.name+"，查看邻域");circle.setAttribute("r","22");text.setAttribute("y","39");text.setAttribute("text-anchor","middle");text.textContent=node.name;group.append(circle,text);group.onclick=()=>load(node.id).catch(UI.toastErr);group.onkeydown=event=>{if(event.key==="Enter"||event.key===" "){event.preventDefault();group.onclick();}};svg.appendChild(group);});
      mount.appendChild(svg);const count=document.createElement("p");count.className="muted";count.textContent=graph.nodes.length+" 个实体 · "+graph.edges.length+" 条关系"+(graph.nodes.length>80?" · 图中展示前 80 个实体，可在下方核对全部关系":"");mount.appendChild(count);
    }
    ctx.el.querySelector("#graph-reset").onclick=()=>load().catch(UI.toastErr);await load();
  });
})(window);
