"""OpenAI 兼容桩：只回一条固定答案 + 固定的 usage。

用途：在没有有效上游密钥的机器上验证"用量从响应里读出来 → 记账 → 配额闸门"这条链路。
它不假装是 DeepSeek，也不做任何模型推理——只是把响应形状摆对。
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18999
PROMPT_TOKENS = int(sys.argv[2]) if len(sys.argv) > 2 else 880
COMPLETION_TOKENS = int(sys.argv[3]) if len(sys.argv) > 3 else 42


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        payload = {
            "id": "chatcmpl-stub",
            "object": "chat.completion",
            "model": "deepseek-v4-flash",
            "choices": [{
                "index": 0,
                "message": {"role": "assistant",
                            "content": "【桩】依据原文片段：熟地黄味甘、性微温，归肝肾经，可补血滋阴。"},
                "finish_reason": "stop",
            }],
            "usage": {
                "prompt_tokens": PROMPT_TOKENS,
                "completion_tokens": COMPLETION_TOKENS,
                "total_tokens": PROMPT_TOKENS + COMPLETION_TOKENS,
            },
        }
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
