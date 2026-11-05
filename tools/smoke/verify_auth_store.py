#!/usr/bin/env python3
"""会话吊销名单 / 登录失败计数的「重启不丢」验证。

这两件事靠单测证不了：单测里 mock 掉的那一层，正是"重启后还有没有"的关键。
所以这个脚本必须在**真实进程**上跑，并且需要你手动重启一次应用——
脚本自己不去 kill 进程（那会把"验证"变成"捣鼓"，出错时还分不清是谁的问题）。

用法（顺序不能颠倒）：

  # 第一段：登录 → 访问受保护接口 → 登出 → 确认当场 401
  python tools/smoke/verify_auth_store.py before-restart

  # 然后重启应用（Ctrl+C 再起，或 kill 后重新 java -jar）

  # 第二段：拿第一段留下的同一张 Cookie 再访问，必须仍是 401
  #         —— 内存实现到这一步会返回 200，那正是"登出形同没登出"
  python tools/smoke/verify_auth_store.py after-restart

  # 第三段：连续用错口令把探针账号锁住，确认 429
  python tools/smoke/verify_auth_store.py throttle-before

  # 再重启一次应用

  # 第四段：重启后同一账号仍 429（失败计数跨重启）
  python tools/smoke/verify_auth_store.py throttle-after

Cookie 与探针账号存在 .local/ 下（已被 .gitignore 忽略）。
探针账号默认 `lockout-probe`，**故意不是 admin**：验证锁定会把账号锁 15 分钟，
用真账号会让后面所有验证都卡在 429 上。
"""
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("HEALTHOS_BASE", "http://127.0.0.1:18100")
USERNAME = os.environ.get("HEALTHOS_USER", "admin")
PASSWORD = os.environ.get("HEALTHOS_PASSWORD", "admin123")
PROBE_USER = os.environ.get("HEALTHOS_PROBE_USER", "lockout-probe")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
STATE_DIR = os.path.join(ROOT, ".local")
COOKIE_FILE = os.path.join(STATE_DIR, "auth-verify-cookie.txt")

PASS, FAIL = [], []


def check(name, cond, detail=""):
    if cond:
        PASS.append(name)
        print(f"PASS {name}")
    else:
        FAIL.append(name)
        print(f"FAIL {name} :: {detail}")


def req(path, method="GET", body=None, cookie=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if cookie:
        headers["Cookie"] = cookie
    request = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode(), dict(response.headers)
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode(), dict(error.headers)


def deps():
    status, body, _ = req("/health/dependencies")
    return status, (json.loads(body) if status == 200 else {})


def store_posture():
    """从 auth 健康串里抠出 store=xxx（这是"到底走库还是走内存"的唯一外部证据）。"""
    _, body = deps()
    auth = str(body.get("auth") or "")
    for token in auth.replace("(", ",").replace(")", ",").split(","):
        if token.strip().startswith("store="):
            return token.strip().split("=", 1)[1]
    return auth or "(未找到 auth 字段)"


# ---------------------------------------------------------------- before-restart

def before_restart():
    status, body, headers = req("/api/v1/auth/login", "POST",
                                {"username": USERNAME, "password": PASSWORD})
    check("登录成功", status == 200, f"status={status} body={body[:160]}")
    cookie = headers.get("Set-Cookie", "").split(";")[0]
    check("拿到会话 Cookie", bool(cookie), "响应里没有 Set-Cookie")
    if not cookie:
        return

    posture = store_posture()
    print(f"     会话存储姿态：store={posture}")
    check("会话存储走库（不是 memory）", posture.startswith("jdbc"),
          f"store={posture}；memory 模式下本脚本的结论没有意义")

    status, _, _ = req("/api/v1/auth/me", cookie=cookie)
    check("登出前受保护接口可用", status == 200, f"status={status}")

    status, _, _ = req("/api/v1/auth/logout", "POST", {}, cookie=cookie)
    # 204 = 成功且无返回体（RESTful 的常规写法），200 也接受
    check("登出成功", status in (200, 204), f"status={status}")

    status, _, _ = req("/api/v1/auth/me", cookie=cookie)
    check("登出后同一张 Cookie 立即 401", status == 401, f"status={status}")

    os.makedirs(STATE_DIR, exist_ok=True)
    with open(COOKIE_FILE, "w", encoding="utf-8") as handle:
        handle.write(cookie)
    print(f"     已保存旧 Cookie 到 {COOKIE_FILE}")
    print("     现在重启应用，然后跑 after-restart")


# ----------------------------------------------------------------- after-restart

def after_restart():
    if not os.path.exists(COOKIE_FILE):
        check("找到第一段留下的 Cookie", False, f"{COOKIE_FILE} 不存在，先跑 before-restart")
        return
    cookie = open(COOKIE_FILE, encoding="utf-8").read().strip()

    posture = store_posture()
    print(f"     会话存储姿态：store={posture}")
    check("重启后仍走库", posture.startswith("jdbc"), f"store={posture}")

    # 这条是整个验证的核心：新进程的内存名单一定是空的，
    # 能判成 401 就只可能是因为吊销记录在库里。
    status, body, _ = req("/api/v1/auth/me", cookie=cookie)
    check("重启后旧 Cookie 仍被拒（401）", status == 401,
          f"status={status}；返回 200 说明吊销名单没跨进程存活，body={body[:160]}")

    # 同一个令牌的另一面：没被吊销过的会话重启后应该还能用，
    # 否则"登出有效"就可能只是"令牌全都失效"的假象。
    status, _, headers = req("/api/v1/auth/login", "POST",
                             {"username": USERNAME, "password": PASSWORD})
    fresh = headers.get("Set-Cookie", "").split(";")[0]
    status, _, _ = req("/api/v1/auth/me", cookie=fresh)
    check("对照：新登录的会话重启后可用（200）", status == 200,
          f"status={status}；若也 401，说明拒绝与吊销无关，上面的结论不成立")


# --------------------------------------------------------------- throttle-before

def throttle_before():
    # 先把探针账号的残留状态清掉：上一轮验证留下的锁定会让本轮从 429 直接开始
    for attempt in range(1, 9):
        status, body, _ = req("/api/v1/auth/login", "POST",
                              {"username": PROBE_USER, "password": "wrong-" + str(attempt)})
        if status == 429:
            print(f"     第 {attempt} 次尝试被拒（429），说明已进入锁定")
            check("连续错误口令触发 429", True)
            print("     现在重启应用，然后跑 throttle-after")
            return
        if status not in (401, 400, 403):
            check(f"第 {attempt} 次尝试返回了意外状态", False, f"status={status} body={body[:160]}")
            return
    check("连续错误口令触发 429", False, "试了 8 次仍未锁定（阈值配置大于 8？）")


# ---------------------------------------------------------------- throttle-after

def throttle_after():
    status, body, _ = req("/api/v1/auth/login", "POST",
                          {"username": PROBE_USER, "password": "still-wrong"})
    check("重启后失败计数仍生效（429）", status == 429,
          f"status={status} body={body[:160]}；200/401 说明计数没跨进程存活")


PHASES = {
    "before-restart": before_restart,
    "after-restart": after_restart,
    "throttle-before": throttle_before,
    "throttle-after": throttle_after,
}


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in PHASES:
        print(__doc__)
        return 2
    phase = sys.argv[1]
    print(f"阶段 {phase} @ {BASE}\n")
    PHASES[phase]()
    print("\n结果：" + f"pass={len(PASS)} fail={len(FAIL)}")
    if FAIL:
        print("失败项：" + "，".join(FAIL))
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
