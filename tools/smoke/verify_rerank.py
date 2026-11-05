#!/usr/bin/env python3
"""cross-encoder 重排契约验证（无需 GPU / 模型）。

做法：本地起一个 stub 重排服务（tools/rerank-service/server.py 的 StubReranker，
用 query/passage 二元组覆盖率打分），验证：
1. GET /health -> 200（模型"已加载"语义）
2. POST /rerank -> 按相关性降序返回，且 index 正确映射回 passages
3. 对照：把"最相关"的 passage 放在中间位置，重排后它必须排到第 1——
   这正是"融合序可能排错、cross-encoder 能纠正"的缩影

真实模式（bge-reranker-v2-m3）在没模型的环境里跑不了，但契约是一模一样的；
本脚本验证契约 + 应用侧降级链路（应用侧由 LocalKnowledgeBaseTest / HttpRerankerTest 覆盖）。
"""
import json
import os
import sys
import threading
import urllib.request
from http.server import ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "rerank-service"))
import server as rerank_server  # noqa: E402


def main():
    reranker = rerank_server.StubReranker()
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), rerank_server.make_handler(reranker))
    port = httpd.server_address[1]
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    base = f"http://127.0.0.1:{port}"

    # 1) /health
    with urllib.request.urlopen(base + "/health", timeout=5) as r:
        assert r.status == 200, f"/health 期望 200，实际 {r.status}"
    print("[1] GET /health -> 200 OK")

    # 2) /rerank 契约 + 重排对照
    query = "补肾的药材有哪些"
    # 位置 0 弱相关、位置 1 含查询二元组"补肾"（强相关）、位置 2 弱相关；
    # stub 用 (query, passage) 字符二元组覆盖率打分，强相关段必须命中"补肾"才能排第 1
    passages = [
        "黄芪补气固表，用于脾胃气虚",          # 弱相关（与查询无相邻二元组重叠）
        "杜仲补肾强筋骨，常用于肾虚腰痛",      # 强相关（含"补肾"，应排第 1）
        "当归补血活血，调经止痛",              # 弱相关
    ]
    body = json.dumps({"query": query, "passages": passages}).encode()
    req = urllib.request.Request(base + "/rerank", data=body,
                                headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as r:
        resp = json.loads(r.read())
    print("[2] POST /rerank ->", resp)

    results = resp["results"]
    ranked_idx = [item["index"] for item in results]
    assert ranked_idx == sorted(ranked_idx, key=lambda i: -results[ranked_idx.index(i)]["score"]), \
        "返回未按 score 降序"
    # 强相关的 passage（index 1）必须排到第 1
    assert results[0]["index"] == 1, f"强相关段未排第1：{results}"
    assert [item["index"] for item in results] == [1, 0, 2], \
        f"期望顺序 [1,0,2]，实际 {[item['index'] for item in results]}"

    print("[3] 对照成立：融合/原始序里第 2 位的强相关段，被重排顶到第 1（顺序 = [1,0,2]）")
    httpd.shutdown()
    print("PASS: 重排契约正确（指数映射 + 降序 + 纠正排序）")


if __name__ == "__main__":
    main()
