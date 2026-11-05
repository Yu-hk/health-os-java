#!/usr/bin/env node
/*
 * patient-business MCP Server（STDIO，零依赖）。
 *
 * 这是一个**真的** MCP 服务进程：由 Java 侧用 ProcessBuilder 拉起，
 * 通过 stdin/stdout 走 JSON-RPC 2.0（MCP stdio transport：一行一条消息，非 LSP 的
 * Content-Length 分帧）。它自己声明工具清单（tools/list），调用时把请求转给
 * 父进程的内部只读端点（tools/call）——数据始终由 Java 侧按 owner 锁定后读取，
 * 子进程拿不到数据库凭据，也不解析任何业务规则。
 *
 * 为什么"声明"不读 Java 的目录：两边独立声明才能发现漂移。
 * 如果子进程的清单是从父进程传进来的，那"发现集等于授权集"就变成自己跟自己比，
 * 校验也就没有意义了。
 *
 * 环境变量（由父进程设置）：
 *   MCP_TOOL_ENDPOINT   内部工具端点基址，如 http://127.0.0.1:18100/internal/agent-lab/tools
 *   MCP_INTERNAL_TOKEN  内部令牌；端点侧会校验，空值直接拒绝
 *   MCP_GRANT           本次操作的短命凭证。父进程按登录态签发，本进程只负责回传——
 *                       它**不携带** owner/patientId，服务端查表才还原身份，
 *                       所以子进程没有能力指定"读谁的数据"
 *   MCP_SCOPES          本次授权的范围（逗号分隔，如 memory,report）。父进程只递范围，
 *                       不递工具清单——本文件自己维护 SCOPE_TOOLS 映射再裁剪声明。
 */
'use strict';

const http = require('http');
const https = require('https');

const SERVER_NAME = process.env.MCP_SERVER_NAME || 'patient-business';
const SERVER_VERSION = process.env.MCP_SERVER_VERSION || 'agent-lab-m6-v1';
const PROTOCOL_VERSION = '2024-11-05';
const TOOL_ENDPOINT = (process.env.MCP_TOOL_ENDPOINT || '').replace(/\/+$/, '');
const TOKEN = process.env.MCP_INTERNAL_TOKEN || '';
const GRANT = process.env.MCP_GRANT || '';
const CALL_TIMEOUT_MS = Number(process.env.MCP_CALL_TIMEOUT_MS || 20000);
const SCOPES = (process.env.MCP_SCOPES || '')
  .split(',')
  .map((item) => item.trim())
  .filter((item) => item.length > 0);

/** 与 Java 侧 AgentTool 枚举一一对应；这里独立声明，用于比对漂移。 */
const INPUT_SCHEMA = {
  type: 'object',
  properties: {
    resource_id: {
      type: 'string',
      description: '可选资源标识；患者由服务端按登录态锁定'
    }
  },
  additionalProperties: false
};

/**
 * 范围 → 工具名。这份映射是**本文件手写的**，不从父进程传入。
 *
 * 父进程另有一张独立的授权表（AgentLabContract.AUTHORIZED_TOOLS）。两边漂移
 * （谁新增了工具、谁漏登记）会在握手期被 AGENT_DISCOVERY_MISMATCH 挡住，
 * 这正是"发现集等于授权集"这条校验存在的意义。
 */
const SCOPE_TOOLS = {
  memory: ['read_patient_memory'],
  report: ['read_patient_report'],
  pipeline: ['get_pipeline_status'],
  plan: ['get_plan_progress'],
  statistics: ['get_patient_statistics', 'get_report_statistics']
};

const ALL_TOOLS = [
  { name: 'read_patient_memory', description: '读取患者长效记忆' },
  { name: 'read_patient_report', description: '读取当前签发报告' },
  { name: 'get_pipeline_status', description: '查询患者业务任务状态' },
  { name: 'get_plan_progress', description: '查询生效计划与打卡进度' },
  { name: 'get_patient_statistics', description: '统计当前账号已建档患者数量' },
  { name: 'get_report_statistics', description: '统计当前账号正式报告数量及状态' }
].map((tool) => ({ ...tool, inputSchema: INPUT_SCHEMA }));

/** 本次会话可见的工具：按 MCP_SCOPES 裁剪；未给范围则一个都不声明。 */
const TOOLS = ALL_TOOLS.filter((tool) =>
  SCOPES.some((scope) => (SCOPE_TOOLS[scope] || []).includes(tool.name))
);

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function respond(id, result) {
  send({ jsonrpc: '2.0', id: id, result: result });
}

function respondError(id, code, message) {
  send({ jsonrpc: '2.0', id: id, error: { code: code, message: message } });
}

/** 把 tools/call 转给父进程的内部端点；返回 structuredContent。 */
function forwardCall(tool, args) {
  return new Promise((resolve, reject) => {
    if (!TOOL_ENDPOINT) {
      reject(new Error('MCP_TOOL_ENDPOINT 未配置'));
      return;
    }
    let url;
    try {
      url = new URL(TOOL_ENDPOINT + '/' + encodeURIComponent(tool));
    } catch (error) {
      reject(new Error('MCP_TOOL_ENDPOINT 非法: ' + error.message));
      return;
    }
    const body = JSON.stringify({ grant: GRANT, arguments: args || {} });
    const transport = url.protocol === 'https:' ? https : http;
    const request = transport.request({
      protocol: url.protocol,
      hostname: url.hostname,
      port: url.port,
      path: url.pathname + url.search,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(body),
        'X-Internal-Token': TOKEN
      },
      timeout: CALL_TIMEOUT_MS
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        if (response.statusCode < 200 || response.statusCode >= 300) {
          reject(new Error('工具端点返回 ' + response.statusCode + ': ' + text.slice(0, 200)));
          return;
        }
        let parsed;
        try {
          parsed = JSON.parse(text);
        } catch (error) {
          reject(new Error('工具端点返回的不是 JSON'));
          return;
        }
        // 端点约定：{structuredContent: {...}}；缺失即视为输出非法
        if (!parsed || typeof parsed.structuredContent !== 'object' || parsed.structuredContent === null) {
          reject(new Error('工具输出缺少 structuredContent'));
          return;
        }
        resolve(parsed.structuredContent);
      });
    });
    request.on('timeout', () => request.destroy(new Error('工具调用超时')));
    request.on('error', reject);
    request.write(body);
    request.end();
  });
}

async function handle(message) {
  const { id, method, params } = message;
  const isNotification = id === undefined || id === null;

  switch (method) {
    case 'initialize':
      respond(id, {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: { tools: { listChanged: false } },
        serverInfo: { name: SERVER_NAME, version: SERVER_VERSION }
      });
      return;

    case 'notifications/initialized':
      // 通知没有 id，不需要响应
      return;

    case 'ping':
      respond(id, {});
      return;

    case 'tools/list':
      respond(id, { tools: TOOLS });
      return;

    case 'tools/call': {
      const name = params && params.name;
      const args = (params && params.arguments) || {};
      if (!TOOLS.some((tool) => tool.name === name)) {
        // 与父进程语义一致：未知工具是协议层错误，不是"返回空"
        respondError(id, -32602, '未知工具: ' + name);
        return;
      }
      try {
        const structured = await forwardCall(name, args);
        respond(id, {
          content: [{ type: 'text', text: JSON.stringify(structured) }],
          structuredContent: structured,
          isError: false
        });
      } catch (error) {
        respond(id, {
          content: [{ type: 'text', text: String(error && error.message ? error.message : error) }],
          isError: true
        });
      }
      return;
    }

    default:
      if (isNotification) {
        return;
      }
      respondError(id, -32601, '未知方法: ' + method);
  }
}

let buffer = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buffer += chunk;
  let index = buffer.indexOf('\n');
  while (index >= 0) {
    const line = buffer.slice(0, index).trim();
    buffer = buffer.slice(index + 1);
    index = buffer.indexOf('\n');
    if (!line) {
      continue;
    }
    let message;
    try {
      message = JSON.parse(line);
    } catch (error) {
      // 分帧错误直接回 -32700，然后继续服务（不退出：一条坏消息不该带走整个会话）
      send({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Parse error' } });
      continue;
    }
    Promise.resolve(handle(message)).catch((error) => {
      if (message && message.id !== undefined && message.id !== null) {
        respondError(message.id, -32603, String(error && error.message ? error.message : error));
      }
    });
  }
});

process.stdin.on('end', () => process.exit(0));
