#!/usr/bin/env python3
"""PaddleOCR hubserving 契约桩（零依赖，仅标准库）。

存在的理由：`HttpOcrProvider.callPaddle` 打的是 `/predict/ocr_system`，
而 PaddleHub Serving 只暴露 `/predict/{module}` 这一种路由，**没有 `/health`**。
不用真 PaddleOCR 镜像（2.97GB）也能把协议层的几个疑点几分钟验清：

  1. `provider=paddle` 但 `protocol=generic` 时，实际打的是 `/ocr`（不是 paddle 分支）；
  2. `healthy()` 若探 `/health`，桩上拿到 404 → 服务正常但健康检查为 false；
  3. `base-url` 若写成带 `/predict/ocr_system` 的全路径，会拼成重复路径 → 404；
  4. GET `/predict/ocr_system` 回 **405**（与真 hubserving 一致），探活只要认 405 就能判在线。

响应形状照官方文档：`{"status":"000","results":[{"text":[...],"confidence":[...],"text_region":[...]}]}`。
注意 `text` / `confidence` / `text_region` 都是**按行的数组**，不是单个字符串。

  python tools/ocr-service/paddle_stub.py 8899
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

DEFAULT_LINES = [
    ("四君子汤", 0.972, [[120, 260], [320, 258], [322, 300], [118, 302]]),
    ("主治脾胃气虚证，面色萎白，语声低微", 0.918,
     [[120, 320], [520, 318], [522, 360], [118, 362]]),
]

HITS = {"predict": 0, "other": 0}


class Handler(BaseHTTPRequestHandler):

    def _json(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_body(self):
        """必须处理 chunked：RestClient 发大图片 body 时不一定带 Content-Length，
        只按 Content-Length 读会拿到空体，然后误判成"请求非法"。
        （真踩过：桩返回 400，应用侧报 Error writing request body to server，
          看起来像客户端 bug，其实是服务端没读完就回了响应。）"""
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            data = b""
            while True:
                line = self.rfile.readline().strip()
                if not line:
                    continue
                size = int(line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    return data
                data += self.rfile.read(size)
                self.rfile.read(2)
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length else b""

    def do_GET(self):
        # 保真要点：真 hubserving 对 GET /predict/ocr_system 回 **405**（路由在、方法不对），
        # 而不是 404。写成 404 会让这个桩测不出"探活该不该认 405"，
        # 反而把一台正常的服务演成 unavailable —— 桩失真比没有桩更坏。
        if self.path.startswith("/predict/ocr_system"):
            HITS["other"] += 1
            self._json(405, {"error": "method not allowed", "allow": "POST",
                             "hits": HITS})
            return
        # 刻意不实现 /health：PaddleHub Serving 也没有
        HITS["other"] += 1
        self._json(404, {"error": "no such endpoint", "path": self.path,
                         "hits": HITS})

    def do_POST(self):
        raw = self._read_body()
        if not self.path.startswith("/predict/ocr_system"):
            HITS["other"] += 1
            self._json(404, {"error": "no such endpoint", "path": self.path,
                             "hits": HITS})
            return
        try:
            payload = json.loads(raw or b"{}")
        except Exception:
            self._json(400, {"error": "bad json"})
            return
        images = payload.get("images")
        if not isinstance(images, list) or not images:
            self._json(400, {"error": "images 缺失"})
            return
        HITS["predict"] += 1
        # 每张图一项，每项是「行」的列表 —— 与 parsePaddle 读的 results[0].text 一致
        results = [{
            "text": [line[0] for line in DEFAULT_LINES],
            "confidence": [line[1] for line in DEFAULT_LINES],
            "text_region": [line[2] for line in DEFAULT_LINES],
        } for _ in images]
        self._json(200, {"msg": "", "status": "000", "results": results})

    def log_message(self, fmt, *args):
        sys.stderr.write("[paddle-stub] " + fmt % args + "\n")


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8899
    print(f"paddle stub 监听 127.0.0.1:{port}（只有 POST /predict/ocr_system，没有 /health）")
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
