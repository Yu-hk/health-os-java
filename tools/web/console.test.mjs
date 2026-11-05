import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { Window } from 'happy-dom';
const assets = path.resolve(import.meta.dirname, '../../src/main/resources/static/assets');
function setup(t) {
  const window = new Window({url:'http://healthos.test/'});
  window.document.body.innerHTML='<div id="page"></div><nav id="sidebar-nav"></nav><div id="toast-host"></div><div id="modal-host" class="hidden"></div>';
  const load = file => window.eval(fs.readFileSync(path.join(assets,file),'utf8'));
  load('api.js'); load('ui.js');
  t.after(()=>window.happyDOM.close());
  return {window,document:window.document,Api:window.HOS.Api,UI:window.HOS.UI,load};
}
const tick = () => new Promise(resolve=>setImmediate(resolve));
test('上传保留幂等请求头、表单和同源凭据',async t=>{
  const {window,Api}=setup(t); let sent;
  window.fetch=async(url,options)=>{sent=options;return new window.Response('{"accepted":true}',{status:202});};
  const form=new window.FormData();form.append('file','synthetic');
  await Api.post('/api/v1/classics',null,{form,headers:{'Idempotency-Key':'stable-key'}});
  assert.equal(sent.headers.get('Idempotency-Key'),'stable-key');
  assert.equal(sent.headers.has('Content-Type'),false);assert.equal(sent.body,form);
  assert.equal(sent.credentials,'same-origin');assert.equal(sent.cache,'no-store');
});
test('下载收到 401 时仍触发会话失效处理',async t=>{
  const {window,Api}=setup(t);let count=0;
  Api.onUnauthorized=()=>count++;
  window.fetch=async()=>new window.Response('{"message":"会话已过期"}',{status:401});
  await assert.rejects(Api.get('/download',{blob:true}),e=>e.status===401&&e.message==='会话已过期');assert.equal(count,1);
});
test('超时有明确错误且释放 busy 操作所依赖的 Promise',async t=>{
  const {window,Api}=setup(t);
  window.fetch=(_,options)=>new Promise((resolve,reject)=>options.signal.addEventListener('abort',()=>reject(new window.DOMException('aborted','AbortError'))));
  await assert.rejects(Api.get('/slow',{timeout:5}),e=>e.code==='TIMEOUT');
});
test('异步弹窗失败后保留输入并恢复按钮',async t=>{
  const {document,UI}=setup(t);let finish;
  const body=document.createElement('div');body.innerHTML='<input value="保留的输入">';
  UI.modal({title:'保存',body,actions:[{label:'提交',onClick:()=>new Promise(resolve=>{finish=resolve;})}]});
  const button=document.querySelector('.modal-foot button');button.click();
  assert.equal(button.disabled,true);assert.equal(document.getElementById('modal-host').classList.contains('hidden'),false);
  finish(false);await tick();assert.equal(button.disabled,false);assert.equal(body.querySelector('input').value,'保留的输入');assert.ok(body.isConnected);
});
test('异步弹窗防止重复提交，成功后关闭',async t=>{
  const {document,UI}=setup(t);let finish,count=0;
  UI.modal({title:'保存',body:'',actions:[{label:'提交',onClick:()=>{count++;return new Promise(resolve=>{finish=resolve;});}}]});
  const button=document.querySelector('.modal-foot button');button.click();button.click();assert.equal(count,1);
  finish(true);await tick();assert.equal(document.getElementById('modal-host').classList.contains('hidden'),true);
});
test('必填校验失败不会提交或关闭弹窗',async t=>{
  const {document,UI}=setup(t);let count=0;
  UI.modal({title:'必填',body:UI.formFields([{name:'name',label:'姓名',required:true}]),actions:[{label:'提交',onClick:()=>{count++;}}]});
  document.querySelector('.modal-foot button').click();await tick();assert.equal(count,0);assert.ok(document.querySelector('.modal'));
});
test('对象字段和标题按文本转义，不注入 HTML',t=>{
  const {document,UI}=setup(t);
  const html=UI.table([{key:'data',label:'数据'}],[{data:{value:'<img src=x onerror=alert(1)>'}}]);
  const mount=document.createElement('div');mount.innerHTML=html;assert.equal(mount.querySelector('img'),null);
  UI.modal({title:'<img src=x>',body:''});assert.equal(document.querySelector('.modal-head img'),null);
});
test('表单保留 0 值和路由传入的患者上下文',t=>{
  const {window,UI}=setup(t);window.HOS.router={query:()=>({patientId:'p-123'})};
  const body=UI.formFields([{name:'temperature',label:'温度',value:0},{name:'patientId',label:'患者',type:'select',options:[{value:'p-123',label:'患者'}]}]);
  assert.equal(body.querySelector('[name=temperature]').value,'0');assert.equal(UI.readForm(body).patientId,'p-123');
});
test('带查询串的详情路由可匹配，重复启动不重复注册',async t=>{
  const {window,load}=setup(t);load('router.js');let count=0,received;
  const router=window.HOS.router;window.HOS.app={user:{username:'test'},setHead:()=>{}};
  router.register('/patients/:id',ctx=>{count++;received=ctx;});
  window.location.hash='#/patients/p-1?patientId=p-1';router.start();await tick();router.start();await tick();
  const before=count;window.dispatchEvent(new window.HashChangeEvent('hashchange'));await tick();
  assert.equal(count,before+1);assert.equal(received.params.id,'p-1');assert.equal(received.query.patientId,'p-1');
});
test('页面离开会中止旧请求、执行清理且允许重新登录',async t=>{
  const {window,load}=setup(t);load('router.js');const router=window.HOS.router;let cleaned=0,oldSignal;
  window.HOS.app={user:{username:'test'},setHead:()=>{}};
  router.register('/a',ctx=>{oldSignal=ctx.signal;ctx.onLeave(()=>cleaned++);});router.register('/b',()=>{});
  window.location.hash='#/a';router.start();await tick();
  window.location.hash='#/b';await router.resolve();assert.equal(oldSignal.aborted,true);assert.ok(cleaned>=1);
  router.stop();assert.equal(router.signal,null);
});
test('首次读取已终止任务时不留下轮询',async t=>{
  const {UI}=setup(t);let calls=0,cleanup;
  UI.poll({isActive:()=>true,onLeave:fn=>{cleanup=fn;}},async()=>{calls++;return false;},1);
  await new Promise(resolve=>setTimeout(resolve,15));assert.equal(calls,1);cleanup();
});
test('HTTP 环境无需 randomUUID 即可生成安全操作编号',t=>{
  const {window,UI}=setup(t);window.crypto.randomUUID=undefined;
  const a=UI.operationId(),b=UI.operationId();assert.match(a,/^op_[0-9a-f]{32}$/);assert.notEqual(a,b);
});
test('真实小写 report 契约显示签发按钮，不会误判为执行计划',async t=>{
  const {window,document,Api,load}=setup(t),routes=new Map();
  window.HOS.router={register:(route,render)=>routes.set(route,render)};
  window.HOS.app={setHead:()=>{}};
  Api.get=async()=>({id:'report-1',kind:'report',status:'DRAFT',complete:true,revision:1,sections:[],tasks:[]});
  load('pages/care.js');await routes.get('/care/:documentId')({params:{documentId:'report-1'},el:document.getElementById('page'),query:{}});
  assert.ok(document.getElementById('sign'));assert.equal(document.getElementById('confirm-plan'),null);
});
test('模型中心未启用时展示明确提示',async t=>{
  const {window,document,Api,load}=setup(t),routes=new Map();
  document.body.insertAdjacentHTML('beforeend','<div id="head-actions"></div>');
  window.HOS.router={register:(route,render)=>routes.set(route,render),resolve:()=>{}};
  window.HOS.app={setHead:()=>{document.getElementById('head-actions').innerHTML='<button id="models-refresh"></button>';}};
  Api.get=async url=>{if(url.endsWith('/permissions'))return {canManageModelRoutes:true};throw new Api.ApiError('MODEL_ROUTING_DISABLED','模型配置中心尚未启用',503);};
  load('pages/models.js');await routes.get('/models')({el:document.getElementById('page')});
  assert.match(document.getElementById('page').textContent,/尚未启用/);assert.doesNotMatch(document.getElementById('page').textContent,/加载失败/);
});
test('执行计划打卡发送真实的 0 版本，不替换成文档版本',async t=>{
  const {window,document,Api,load}=setup(t),routes=new Map();let sent;
  window.HOS.router={register:(route,render)=>routes.set(route,render)};window.HOS.app={setHead:()=>{}};
  Api.get=async()=>({id:'plan-1',kind:'plan',status:'ACTIVE',complete:true,revision:2,sections:[],tasks:[{taskId:'task-1',title:'合成任务',version:0,completed:false}]});
  Api.put=async(url,body)=>{sent=body;};
  load('pages/care.js');await routes.get('/care/:documentId')({params:{documentId:'plan-1'},el:document.getElementById('page'),query:{}});
  document.querySelector('[data-check]').click();await tick();assert.equal(sent.expectedVersion,0);assert.equal(sent.completed,true);
});
