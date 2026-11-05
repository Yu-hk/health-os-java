#!/usr/bin/env python3
"""cross-encoder 重排服务（bge-reranker-v2-m3）。

为什么需要它：本机 Ollama 0.40 没有 `/api/rerank`，`bge-reranker-v2-m3` 只能当生成模型跑、
拿不到 (query, passage) 的相关性分数。要"真重排"就得有个能打分的端点，这个 Flask 服务就是它。

契约（应用侧 `HttpReranker` 就按这个调）：
    POST /rerank
      req : {"query": "...", "passages": ["文本0", "文本1", ...]}
      resp: {"results": [{"index": 0, "score": 0.92}, ...]}   // score 越大越相关
    GET  /health  -> 200 表示模型已加载；503 表示没加载（应用侧据此降级）

两种运行模式：
    --stub   纯标准库打分（query 二元组在 passage 里的覆盖率），不依赖 torch / 模型。
             用来在没有 GPU/模型的环境里验证"重排契约 + 应用侧降级 + 有无重排对照"，不伪造质量。
    默认     真实模式：从 https://hf-mirror.com 拉 BAAI/bge-reranker-v2-m3。
             装不上（没网 / 没 torch / 没盘）就 /health 返回 503，应用侧自动退回融合序——
             绝不因为重排挂了就让检索报错。

环境变量：HF_ENDPOINT（默认 https://hf-mirror.com）、RERANK_MODEL（默认 BAAI/bge-reranker-v2-m3）。
"""
import argparse
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse


def parse_body(handler):
    length = int(handler.headers.get("Content-Length", "0"))
    if length <= 0:
        return {}
    raw = handler.rfile.read(length)
    try:
        import json
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return {}


def write_json(handler, code, payload):
    import json
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(data)))
    handler.end_headers()
    handler.wfile.write(data)


def tokenize(text):
    # 简单二元组：中文按字符相邻、英文/数字按小写词。够跑 stub 对照，不是真分词。
    text = text.lower()
    grams = set()
    chars = [c for c in text if c.isalnum()]
    for i in range(len(chars) - 1):
        grams.add(chars[i] + chars[i + 1])
    for word in text.split():
        if len(word) >= 2:
            grams.add(word)
    return grams


def stub_score(query, passage):
    q = tokenize(query)
    if not q:
        return 0.0
    p = tokenize(passage)
    return len(q & p) / len(q)


class StubReranker:
    """无模型：用 query/passage 二元组覆盖率当分数。确定性、可复现、不需要 torch。"""

    name = "stub"

    def available(self):
        return True

    def rerank(self, query, passages):
        scored = [(i, stub_score(query, p)) for i, p in enumerate(passages)]
        scored.sort(key=lambda x: x[1], reverse=True)
        return [{"index": i, "score": round(s, 6)} for i, s in scored]


class ModelReranker:
    """真实 bge-reranker-v2-m3。加载失败则 available()=False，不抛启动异常。"""

    def __init__(self, model_name):
        self.name = "model:" + model_name
        self.model = None
        self.tokenizer = None
        try:
            import torch  # noqa: F401
            from transformers import AutoTokenizer, AutoModelForSequenceClassification
            os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
            self.tokenizer = AutoTokenizer.from_pretrained(model_name)
            self.model = AutoModelForSequenceClassification.from_pretrained(model_name)
            self.model.eval()
        except Exception as ex:  # 装不上就降级，绝不阻断应用启动
            sys.stderr.write("[rerank] 模型加载失败，/health 将返回 503: %s\n" % ex)

    def available(self):
        return self.model is not None and self.tokenizer is not None

    def rerank(self, query, passages):
        import torch
        pairs = [(query, p) for p in passages]
        inputs = self.tokenizer(pairs, padding=True, truncation=True,
                                max_length=512, return_tensors="pt")
        with torch.no_grad():
            logits = self.model(**inputs).logits.view(-1).float()
        scores = logits.tolist()
        scored = list(enumerate(scores))
        scored.sort(key=lambda x: x[1], reverse=True)
        return [{"index": i, "score": round(float(s), 6)} for i, s in scored]


def make_handler(reranker):
    class Handler(BaseHTTPRequestHandler):
        def _cors(self):
            self.send_header("Access-Control-Allow-Origin", "*")

        def do_GET(self):
            if urlparse(self.path).path.rstrip("/") in ("", "/health"):
                if reranker.available():
                    write_json(self, 200, {"status": "ok", "model": reranker.name})
                else:
                    write_json(self, 503, {"status": "model_not_loaded", "model": reranker.name})
                return
            write_json(self, 404, {"error": "not_found"})

        def do_POST(self):
            path = urlparse(self.path).path.rstrip("/")
            if path == "/rerank":
                if not reranker.available():
                    write_json(self, 503, {"error": "model_not_loaded"})
                    return
                body = parse_body(self)
                query = body.get("query", "")
                passages = body.get("passages", [])
                if not isinstance(passages, list) or not query:
                    write_json(self, 400, {"error": "need query(str) and passages(list)"})
                    return
                try:
                    results = reranker.rerank(query, [str(p) for p in passages])
                except Exception as ex:
                    write_json(self, 500, {"error": str(ex)})
                    return
                write_json(self, 200, {"results": results})
                return
            write_json(self, 404, {"error": "not_found"})

        def log_message(self, *args):  # 静默默认访问日志
            pass

    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8899)
    ap.add_argument("--stub", action="store_true", help="无模型模式：用二元组覆盖率打分")
    ap.add_argument("--model", default=os.environ.get("RERANK_MODEL", "BAAI/bge-reranker-v2-m3"))
    args = ap.parse_args()

    if args.stub:
        reranker = StubReranker()
        sys.stderr.write("[rerank] stub 模式启动（无模型，仅验证契约/降级/对照）\n")
    else:
        reranker = ModelReranker(args.model)
        if not reranker.available():
            sys.stderr.write("[rerank] 真实模型不可用，/health 返回 503（应用侧会降级）\n")

    httpd = ThreadingHTTPServer((args.host, args.port), make_handler(reranker))
    sys.stderr.write("[rerank] 监听 http://%s:%d\n" % (args.host, args.port))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
