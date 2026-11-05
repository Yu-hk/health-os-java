"""真实链路验证脚本：一条命令跑完 #65～#68 的 45 项断言。

前置条件（缺一不可）：
  1. PostgreSQL 已起，且 schema.sql 已执行；
  2. 应用已启动（默认 18100 端口，可用 HEALTHOS_BASE 覆盖）；
  3. 库里已存在下面这些夹具（一次真实上传/问答留下的数据），ID 可用环境变量覆盖。

跑法：
  python tools/smoke/verify_all.py
  HEALTHOS_BASE=http://127.0.0.1:18100 HEALTHOS_PAT=pat_xxx python tools/smoke/verify_all.py

脚本只读 + 只新增（收藏、诊断、草稿、摘录都是新增行），不改既有业务数据。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("HEALTHOS_BASE", "http://127.0.0.1:18100")
PAT = os.environ.get("HEALTHOS_PAT", "pat_24d974bc2dc1ef73023d5245")
EXAM = os.environ.get("HEALTHOS_EXAM", "pie_eebeee4af3aed992411da438")
BINDING = os.environ.get("HEALTHOS_BINDING", "qkb_d73bf8ea66ed51bbea3865d0")
MESSAGE = os.environ.get("HEALTHOS_MESSAGE", "qms_0533db23b5e210c3d682d5d6")

PASS, FAIL = [], []


def check(name, cond, detail=""):
    if cond:
        PASS.append(name)
        print(f"PASS {name}")
    else:
        FAIL.append(name)
        print(f"FAIL {name} :: {detail}")


class Client:
    def __init__(self):
        self.cookie = None

    def req(self, path, method="GET", body=None, headers=None):
        data = json.dumps(body).encode() if body is not None else None
        h = {"Content-Type": "application/json"}
        if self.cookie:
            h["Cookie"] = self.cookie
        if headers:
            h.update(headers)
        request = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                return response.status, response.read().decode(), dict(response.headers)
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode(), dict(error.headers)

    def login(self):
        status, body, headers = self.req("/api/v1/auth/login", "POST",
                                         {"username": "admin", "password": "admin123"})
        if status == 200:
            self.cookie = headers.get("Set-Cookie", "").split(";")[0]
        return status, body


def jbody(body):
    try:
        return json.loads(body)
    except Exception:
        return {}


def wait_terminal(client, path, statuses, timeout=25):
    deadline = time.time() + timeout
    last = {}
    while time.time() < deadline:
        _, body, _ = client.req(path)
        last = jbody(body)
        if last.get("status") in statuses:
            return last
        time.sleep(0.5)
    return last


def main():
    client = Client()
    status, _ = client.login()
    check("登录", status == 200, f"status={status}")
    if status != 200:
        return

    # ---------------------------------------------------------------- #65
    status, body, _ = client.req(f"/api/v1/patients/{PAT}/collaborations", "POST", {
        "scenario": "progress",
        "scopes": ["memory", "pipeline", "report", "plan"],
        "instruction": "汇总该患者最近的记忆、任务状态与计划执行情况",
        "operationId": "p1-run-002",
    })
    accepted = jbody(body)
    run_id = accepted.get("runId")
    check("协同提交 202", status == 202 and bool(run_id), f"{status} {body[:200]}")

    finished = wait_terminal(client, f"/api/v1/collaborations/{run_id}", {"SUCCEEDED", "FAILED"})
    check("协同执行结束", finished.get("status") == "SUCCEEDED",
          f"status={finished.get('status')} error={finished.get('error')}")
    check("协同角色节点齐全", len(finished.get("nodes") or []) >= 4,
          f"nodes={len(finished.get('nodes') or [])}")

    status, body, _ = client.req(f"/api/v1/collaborations/{run_id}/events?afterSeq=0&limit=100")
    page = jbody(body)
    events = page.get("items") or []
    types = [item.get("type") for item in events]
    check("事件增量查询 200", status == 200 and len(events) > 0, f"{status} {body[:200]}")
    check("事件含受理/开始/终态",
          all(t in types for t in ("run.queued", "run.started", "run.succeeded")), str(types[:10]))
    check("事件含节点事件", any(t.startswith("node.") for t in types), str(types[:10]))
    check("事件推进 nextSeq 与 eventSeq", page.get("nextSeq", 0) > 0
          and page.get("eventSeq", 0) >= page.get("nextSeq", 0),
          f"{page.get('nextSeq')}/{page.get('eventSeq')}")
    check("事件响应不带 detail", all("detail" not in item for item in events), "")

    if events:
        mid = events[len(events) // 2]["seq"]
        _, body, _ = client.req(f"/api/v1/collaborations/{run_id}/events?afterSeq={mid - 1}&limit=1")
        one = jbody(body)
        check("afterSeq 精确补齐一条", len(one.get("items") or []) == 1
              and one["items"][0]["seq"] == mid, body[:200])

    nodes = finished.get("nodes") or []
    node_id = nodes[0]["id"] if nodes else None
    if node_id:
        _, body, _ = client.req(f"/api/v1/collaborations/{run_id}/nodes/{node_id}?mode=redacted")
        redacted = jbody(body)
        check("节点正文默认脱敏", redacted.get("mode") == "redacted"
              and "正文按权限隐藏" in json.dumps(redacted, ensure_ascii=False), body[:200])
        _, body, _ = client.req(f"/api/v1/collaborations/{run_id}/nodes/{node_id}?mode=full")
        full = jbody(body)
        check("节点正文 full 授权可读", full.get("mode") == "full", body[:200])

    _, body, _ = client.req(f"/api/v1/collaborations/{run_id}/cancel", "POST", {})
    cancelled = jbody(body)
    check("取消终态运行幂等", cancelled.get("idempotent") is True, body[:200])

    status, body, _ = client.req(f"/api/v1/collaborations/{run_id}/cancel", "POST",
                                 {"expectedVersion": 99})
    check("取消版本冲突 409", status == 409 and jbody(body).get("code") == "AGENT_VERSION_CONFLICT",
          f"{status} {body[:200]}")

    status, body, _ = client.req(f"/api/v1/collaborations/{run_id}/retry", "POST", {})
    check("成功运行不可重试 409", status == 409 and jbody(body).get("code") == "AGENT_RUN_STATE",
          f"{status} {body[:200]}")

    _, body, _ = client.req("/api/v1/collaborations?limit=2")
    listing = jbody(body)
    check("运行列表分页", len(listing.get("items") or []) >= 1, body[:200])

    # ---------------------------------------------------------------- #66
    status, body, _ = client.req("/api/v1/agent-lab/catalog")
    catalog = jbody(body)
    check("Agent Lab 目录 200", status == 200 and catalog.get("catalogVersion") == "agent-lab-m6-v1",
          f"{status} {body[:200]}")
    check("目录含 6 工具 / 4 Agent",
          len(catalog.get("configuredTools") or []) == 6
          and len(catalog.get("agents") or []) == 4,
          f"tools={len(catalog.get('configuredTools') or [])} agents={len(catalog.get('agents') or [])}")
    check("目录服务标识正确", catalog.get("serviceId") == "patient-business", str(catalog.get("serviceId")))

    status, body, _ = client.req("/api/v1/agent-lab/services/patient-business/probe", "POST",
                                 {"patientId": PAT, "scopes": ["statistics"]})
    probe = jbody(body)
    check("probe 成功且 output 为空", status == 200
          and (probe.get("receipt") or {}).get("status") == "succeeded"
          and probe.get("output") is None, f"{status} {body[:300]}")

    status, body, _ = client.req("/api/v1/agent-lab/services/patient-business/call", "POST", {
        "patientId": PAT,
        "scopes": ["statistics"],
        "tool": "get_patient_statistics",
        "arguments": {},
    })
    called = jbody(body)
    output = called.get("output") or {}
    content = output.get("structuredContent") if isinstance(output, dict) else None
    check("call 返回结构化输出", status == 200 and bool(content), f"{status} {body[:300]}")
    check("统计口径带总数", isinstance(content, dict) and "totalPatients" in content, str(content)[:200])
    detail = (called.get("receipt") or {}).get("detail") or {}
    check("call 回执含输出哈希", bool(detail.get("outputHash")), str(detail)[:200])

    status, body, _ = client.req("/api/v1/agent-lab/services/patient-business/call", "POST", {
        "patientId": PAT,
        "scopes": ["statistics"],
        "tool": "read_patient_memory",
        "arguments": {},
    })
    rejected = jbody(body)
    rejected_detail = (rejected.get("receipt") or {}).get("detail") or {}
    check("越权工具被拒", status == 200
          and rejected_detail.get("error") == "MCP_TOOL_REJECTED"
          and (rejected.get("receipt") or {}).get("status") == "failed",
          f"{status} {body[:300]}")

    status, body, _ = client.req("/api/v1/agent-lab/services/not-exist/probe", "POST",
                                 {"patientId": PAT, "scopes": ["statistics"]})
    check("未知服务 404", status == 404
          and jbody(body).get("code") == "MCP_SERVICE_NOT_FOUND", f"{status} {body[:200]}")

    # ---------------------------------------------------------------- #67
    status, body, _ = client.req(f"/api/v1/qa/messages/{MESSAGE}/memo", "POST", {})
    memo = jbody(body)
    memo_id = memo.get("memoId")
    check("书签创建", status == 200 and bool(memo_id), f"{status} {body[:300]}")

    status, body, _ = client.req(f"/api/v1/qa/messages/{MESSAGE}/memo", "POST", {})
    check("书签重复点击幂等", jbody(body).get("memoId") == memo_id, body[:200])

    status, body, _ = client.req(f"/api/v1/qa/patients/{PAT}/memos?limit=10")
    memos = jbody(body)
    check("书签列表", status == 200 and len(memos.get("items") or []) >= 1, f"{status} {body[:200]}")

    status, body, _ = client.req(f"/api/v1/qa/memos/{memo_id}")
    memo_detail = jbody(body)
    check("书签详情含引用快照", status == 200
          and isinstance(memo_detail.get("evidenceSnapshot"), list), f"{status} {body[:300]}")

    status, body, _ = client.req(f"/api/v1/qa/bindings/{BINDING}/diagnostics", "POST",
                                 {"question": "该患者血压相关记录", "expectedVersion": 1},
                                 {"Idempotency-Key": "diag-key-002"})
    diagnostic = jbody(body)
    diag_id = diagnostic.get("runId")
    check("诊断提交 202", status == 202 and bool(diag_id), f"{status} {body[:300]}")

    status, body, _ = client.req(f"/api/v1/qa/bindings/{BINDING}/diagnostics", "POST",
                                 {"question": "该患者血压相关记录", "expectedVersion": 1})
    check("缺 Idempotency-Key 422", status == 422
          and jbody(body).get("code") == "IDEMPOTENCY_KEY_REQUIRED", f"{status} {body[:200]}")

    diag = wait_terminal(client, f"/api/v1/qa/diagnostics/{diag_id}", {"SUCCEEDED", "FAILED"})
    check("诊断执行完成", diag.get("status") == "SUCCEEDED",
          f"status={diag.get('status')} error={diag.get('error')}")
    result = diag.get("result") or {}
    check("诊断给出检索结论", result.get("retrievalStatus") in ("hit", "no_hit", "unknown"),
          str(result)[:300])
    check("诊断结果 kind 合法", result.get("resultKind") in
          ("retrieval_only", "no_evidence", "model_unknown_retrieval", "retrieval_answer",
           "model_no_hit"), str(result)[:300])
    check("诊断证据字段存在", isinstance(diag.get("evidence"), list), str(diag)[:200])

    # ---------------------------------------------------------------- #68
    status, body, _ = client.req(f"/api/v1/patient-intakes/exams/{EXAM}/drafts/basic")
    check("草稿初始为空", status == 200 and jbody(body).get("draft") is None, f"{status} {body[:200]}")

    status, body, _ = client.req(f"/api/v1/patient-intakes/exams/{EXAM}/drafts/basic", "PUT", {
        "baseRevision": 2, "expectedVersion": 0,
        "payload": {"basic_info": {"name": "验证患者", "sex": "男"}},
    })
    first = jbody(body)
    check("草稿首次创建 version=1", status == 200
          and (first.get("draft") or {}).get("version") == 1, f"{status} {body[:300]}")

    status, body, _ = client.req(f"/api/v1/patient-intakes/exams/{EXAM}/drafts/basic", "PUT", {
        "baseRevision": 2, "expectedVersion": 0,
        "payload": {"basic_info": {"name": "另一个人"}},
    })
    check("草稿陈旧版本 409", status == 409
          and jbody(body).get("code") == "DRAFT_VERSION_CONFLICT", f"{status} {body[:200]}")

    status, body, _ = client.req(f"/api/v1/patient-intakes/exams/{EXAM}/drafts/basic", "PUT", {
        "baseRevision": 1, "expectedVersion": 1,
        "payload": {"basic_info": {"name": "验证患者"}},
    })
    check("草稿依据修订过期 409", status == 409
          and jbody(body).get("code") == "DRAFT_REVISION_CONFLICT", f"{status} {body[:200]}")

    status, body, _ = client.req(f"/api/v1/patient-intakes/exams/{EXAM}/drafts/basic", "DELETE")
    check("草稿删除幂等", status == 200 and jbody(body).get("draft") is None, f"{status} {body[:200]}")

    status, body, _ = client.req(f"/api/v1/patients/{PAT}/memory/agent-followups", "POST", {
        "operationId": "agent-note-002",
        "instruction": "请摘录本次随访原文并请求刷新记忆",
        "entries": [
            {"category": "症状", "content": "最近一周偶有头晕，下午明显。"},
            {"category": "用药", "content": "按医嘱服用氨氯地平，未漏服。"},
        ],
    })
    note = jbody(body)
    note_id = note.get("noteId")
    candidate_hash = note.get("candidateHash")
    check("摘录候选生成", status == 202 and bool(note_id), f"{status} {body[:300]}")
    check("候选覆盖全部分类", len(note.get("observations") or []) == 2, str(note)[:300])

    status, body, _ = client.req(f"/api/v1/patients/{PAT}/memory/agent-notes")
    check("摘录列表", status == 200 and len(jbody(body).get("items") or []) >= 1, f"{status} {body[:200]}")

    status, body, _ = client.req(f"/api/v1/patients/{PAT}/memory/agent-notes/{note_id}/publish",
                                 "POST", {"expectedCandidateHash": candidate_hash})
    published = jbody(body)
    check("摘录发布", status == 200 and published.get("status") == "PUBLISHED", f"{status} {body[:300]}")

    status, body, _ = client.req(f"/api/v1/patients/{PAT}/memory/agent-notes/{note_id}/publish",
                                 "POST", {"expectedCandidateHash": candidate_hash})
    check("摘录发布幂等", status == 200 and jbody(body).get("status") == "PUBLISHED", f"{status} {body[:200]}")

    print("\n==== RESULT ====")
    print(f"pass={len(PASS)} fail={len(FAIL)}")
    if FAIL:
        print("failed:", FAIL)
        sys.exit(1)


if __name__ == "__main__":
    main()
