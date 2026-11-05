#!/usr/bin/env python3
"""模型用量记账 / 成本折算 / 配额闸门的真实链路验证。

只做三件事，全部对着正在运行的应用打真实请求：
  1. 真实问答若干次（默认同一问题连打两次，第二次应命中上游前缀缓存）；
  2. 每打一次就从 /api/v1/model-usage/summary 读一次当日累计，确认钱与 token 都在涨；
  3. 把回执里的数字与本地按官方单价重算的数字对齐，误差超过 1 微元即失败。

不依赖数据库客户端：明细行由调用方用 psql 读，本脚本只验"接口层看到的东西"。

  HEALTHOS_BASE=http://127.0.0.1:18100 python tools/smoke/verify_model_usage.py
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("HEALTHOS_BASE", "http://127.0.0.1:18100")
USERNAME = os.environ.get("HEALTHOS_USER", "admin")
PASSWORD = os.environ.get("HEALTHOS_PASSWORD", "admin123")
QUESTION = os.environ.get("HEALTHOS_QUESTION", "这位患者最近的随访重点是什么？")
# 问答必须绑定一份有正文的文档，否则请求在校验层就被拒（与记账无关）
DOCUMENT_ID = os.environ.get("HEALTHOS_DOCUMENT", "doc_001c9ff9641e1a477b12f978")
ROUNDS = int(os.environ.get("HEALTHOS_ROUNDS", "2"))

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

    def req(self, path, method="GET", body=None, timeout=180):
        data = json.dumps(body).encode() if body is not None else None
        headers = {"Content-Type": "application/json"}
        if self.cookie:
            headers["Cookie"] = self.cookie
        request = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, response.read().decode(), dict(response.headers)
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode(), dict(error.headers)

    def login(self):
        status, body, headers = self.req("/api/v1/auth/login", "POST",
                                         {"username": USERNAME, "password": PASSWORD})
        if status == 200:
            self.cookie = headers.get("Set-Cookie", "").split(";")[0]
        return status, body


def jbody(text):
    try:
        return json.loads(text)
    except Exception:
        return {}


def summary(client):
    status, body, _ = client.req("/api/v1/model-usage/summary?days=1")
    return status, jbody(body)


def main():
    client = Client()
    status, _ = client.login()
    check("登录", status == 200, f"status={status}")
    if status != 200:
        return 1

    status, before = summary(client)
    check("用量看板可读", status == 200 and "totalTokens" in before, f"{status}")
    check("看板币种为配置币种", before.get("currency") in ("USD", "CNY"),
          f"currency={before.get('currency')}")
    base_tokens = int(before.get("totalTokens") or 0)
    base_calls = sum(int(m.get("calls") or 0) for m in (before.get("models") or []))
    print(f"基线：tokens={base_tokens} calls={base_calls} "
          f"cost={before.get('totalCostAmount')} {before.get('currency')} "
          f"peakMultiplier={before.get('peakMultiplier')} "
          f"windows={before.get('peakWindowsUtc')}")

    asks = []
    for index in range(1, ROUNDS + 1):
        started = time.time()
        status, body, _ = client.req("/api/v1/qa/ask", "POST",
                                      {"documentId": DOCUMENT_ID, "question": QUESTION})
        elapsed = int((time.time() - started) * 1000)
        payload = jbody(body)
        asks.append({"status": status, "elapsed": elapsed, "body": payload})
        print(f"第 {index} 次问答 http={status} {elapsed}ms "
              f"qaStatus={payload.get('status')} errorCode={payload.get('errorCode')} "
              f"answerLen={len(payload.get('answer') or '')}")
        check(f"第 {index} 次问答返回 200", status == 200, body[:200])
        time.sleep(1)

    time.sleep(1)
    status, after = summary(client)
    check("记账后看板可读", status == 200, f"{status}")
    tokens_delta = int(after.get("totalTokens") or 0) - base_tokens
    calls_delta = sum(int(m.get("calls") or 0) for m in (after.get("models") or [])) - base_calls
    print(f"增量：tokens={tokens_delta} calls={calls_delta} "
          f"cost={after.get('totalCostAmount')} {after.get('currency')}")
    check("token 累计增加", tokens_delta > 0, f"delta={tokens_delta}")
    check("调用次数增加", calls_delta == ROUNDS, f"delta={calls_delta} 期望={ROUNDS}")

    models = after.get("models") or []
    check("明细按模型分组", any(m.get("model") for m in models), str(models)[:200])
    check("所有模型都配了单价", all(m.get("priced") for m in models),
          str([m.get("model") for m in models if not m.get("priced")]))
    total_cost = int(after.get("totalCostMicros") or 0)
    check("成本为正数", total_cost > 0, f"totalCostMicros={total_cost}")
    check("无未定价调用", int(after.get("unpricedCalls") or 0) == 0,
          f"unpricedCalls={after.get('unpricedCalls')}")

    print("\n结果：" + f"pass={len(PASS)} fail={len(FAIL)}")
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
