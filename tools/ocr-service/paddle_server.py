#!/usr/bin/env python3
"""把 PaddleOCR 包成 hubserving 形状的服务。

为什么不用官方 `deploy/docker/hubserving`：
  1. 那个 Dockerfile 里是 `git clone github.com` + `{link}` 占位符，本机 GitHub 不通；
  2. 它依赖 paddlehub 2.4.0 + paddlepaddle 2.6.2 + numpy 1.26.4 + protobuf 3.20.3
     的版本锁链，装错一个就 import 崩，为一个只用到 `/predict/ocr_system` 的接口不值得。

所以直接用 paddleocr 的 Python API，自己产出和 hubserving **逐字节同构**的响应：
    {"msg":"","status":"0","results":[[{"text":"…","confidence":0.98,"text_region":[[x,y],…×4]}]]}

刻意不实现 `/health`：真 PaddleHub Serving 也没有这个路由。
这一条是有用的——它恰好能验证应用侧 `healthy()` 探 `/health` 会误判。

  python server.py            # 监听 0.0.0.0:8868
"""
import base64
import json
import os
import sys
import traceback

import cv2
import numpy as np
from flask import Flask, jsonify, request
from werkzeug.exceptions import HTTPException
from paddleocr import PaddleOCR

PORT = int(os.environ.get("PORT", "8868"))

app = Flask(__name__)
_engine = None


def engine():
    """懒加载：进程起来就能接请求，模型加载放到第一次识别（约 3-10s）。

    两个参数不是调优，是**为了能跑起来**：

    - `ocr_version="PP-OCRv3"`：PP-OCRv4 的检测模型会让 paddle 2.6.2 在
      `SelfAttentionFusePass`（推理图优化阶段）抛 `SIGILL: Illegal instruction`。
      实测同一张图、同一容器，v4 必崩、v3 正常；与 oneDNN/PIR 开关无关，
      也与 CPU 大小核调度无关（绑 P-core 照样崩）。属未修复的上游问题。
    - `enable_mkldnn=False`：oneDNN 会按 CPU 特性（这颗 13900H 暴露了 `avx_vnni`
      但没有 `avx512`）挑 kernel，保守关掉，避免再撞一次指令集问题。
    """
    global _engine
    if _engine is None:
        _engine = PaddleOCR(use_angle_cls=True, lang="ch", show_log=False,
                            enable_mkldnn=False,
                            ocr_version=os.environ.get("OCR_MODEL_VERSION", "PP-OCRv3"))
        sys.stderr.write("[paddle-ocr] 模型已加载\n")
    return _engine


def decode(b64):
    raw = base64.b64decode(b64)
    arr = np.frombuffer(raw, np.uint8)
    return cv2.imdecode(arr, cv2.IMREAD_COLOR)


def flatten(result):
    """2.x 返回 [[ [box, (text, score)], ... ]]，3.x 是 [{...}]；都拍平成"行列表"。"""
    if not result:
        return []
    first = result[0]
    if isinstance(first, list):
        return first
    # 3.x 的 dict 形态：rec_texts / rec_scores / rec_polys
    if isinstance(first, dict) and "rec_texts" in first:
        texts = first.get("rec_texts") or []
        scores = first.get("rec_scores") or []
        polys = first.get("rec_polys") or []
        rows = []
        for index, text in enumerate(texts):
            rows.append([
                [[int(p[0]), int(p[1])] for p in polys[index]] if index < len(polys) else [],
                (text, float(scores[index]) if index < len(scores) else 1.0),
            ])
        return rows
    return result


@app.post("/predict/ocr_system")
def ocr_system():
    payload = request.get_json(silent=True) or {}
    images = payload.get("images")
    if not isinstance(images, list) or not images:
        return jsonify({"msg": "images 缺失", "status": "1", "results": []}), 400

    results = []
    for b64 in images:
        image = decode(b64)
        if image is None:
            return jsonify({"msg": "图片解码失败", "status": "1", "results": []}), 400
        rows = flatten(engine().ocr(image, cls=True))
        page = {"text": [], "confidence": [], "text_region": []}
        for row in rows:
            try:
                region, (text, score) = row[0], row[1]
            except (TypeError, IndexError, ValueError):
                continue
            if not text or not str(text).strip():
                continue
            page["text"].append(str(text))
            page["confidence"].append(float(score))
            page["text_region"].append([[int(p[0]), int(p[1])] for p in region])
        results.append(page)

    return jsonify({"msg": "", "status": "0", "results": results})


@app.errorhandler(Exception)
def on_error(ex):
    # HTTPException（405/404/400…）是语义明确的响应，不是故障，必须原样放行。
    # 直接吞成 500 会让探活失真：调用方 GET 一次本路由本该拿到 405（说明服务在线），
    # 结果拿到 500（看起来是故障），于是把一台正常的机器报成 unavailable。
    if isinstance(ex, HTTPException):
        return ex
    traceback.print_exc()
    return jsonify({"msg": f"{type(ex).__name__}: {ex}", "status": "1", "results": []}), 500


if __name__ == "__main__":
    sys.stderr.write(f"[paddle-ocr] 监听 0.0.0.0:{PORT}（只有 POST /predict/ocr_system）\n")
    app.run(host="0.0.0.0", port=PORT, threaded=False)
