"""极简 OCR 服务：符合本项目 healthos.ocr.protocol=generic 契约。

为什么用 Tesseract 而不是 PaddleOCR：PaddleOCR 的 serving 镜像动辄数 GB 且依赖 Paddle 运行时，
本服务的目标是"让 Java 侧的 OCR 链路能被真实验证"，Tesseract + 中文语言包约 80MB 即可跑通，
且识别结果是真实文字，不是 mock。

契约：
  GET  /health                 -> {"status":"ok"}
  POST /ocr (multipart)        -> {"engine":"tesseract","lines":[{"text","confidence","box":[x1,y1,x2,y2]}]}
       字段：file=图片，language=chi_sim|chi_tra|eng

行级聚合：Tesseract 的 tsv 输出是词级（level=5），这里按 (block,par,line) 聚成视觉行，
并对每一行取词框的外接矩形——这正是 Java 端 OcrTextAssembler 需要的输入。
"""

import io
import os
import subprocess
import tempfile

from flask import Flask, jsonify, request
from PIL import Image

app = Flask(__name__)

DEFAULT_LANG = os.environ.get("OCR_DEFAULT_LANG", "chi_sim")
MAX_SIDE = int(os.environ.get("OCR_MAX_SIDE", "4000"))
# psm 4 = 单列可变大小文本。实测比 psm 6（单一文本块）行序更稳定、不会把标题与正文混行
DEFAULT_PSM = os.environ.get("OCR_PSM", "4")


def _run_tesseract_tsv(image: Image.Image, language: str):
    """调用 tesseract，返回词级 TSV 行。用 stdin 传图，避免临时文件权限问题。"""
    if max(image.size) > MAX_SIDE:
        ratio = MAX_SIDE / max(image.size)
        image = image.resize((int(image.width * ratio), int(image.height * ratio)))

    buffer = io.BytesIO()
    image.convert("RGB").save(buffer, format="PNG")
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as handle:
        handle.write(buffer.getvalue())
        path = handle.name
    try:
        completed = subprocess.run(
            ["tesseract", path, "stdout", "-l", language, "--psm", DEFAULT_PSM, "tsv"],
            capture_output=True,
            timeout=120,
        )
    finally:
        os.unlink(path)

    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.decode("utf-8", "ignore").strip()[:300])
    return completed.stdout.decode("utf-8", "ignore")


def _group_lines(tsv: str):
    """词级 TSV -> 视觉行。"""
    lines = {}
    for index, raw in enumerate(tsv.splitlines()):
        if index == 0 or not raw.strip():
            continue
        cols = raw.split("\t")
        if len(cols) < 12:
            continue
        level, block, par, line = cols[0], cols[2], cols[3], cols[4]
        if level != "5":
            continue
        text = cols[11].strip()
        if not text:
            continue
        # TSV 列序（0-based）：0 level,1 page,2 block,3 par,4 line,5 word,6 left,7 top,8 width,9 height,10 conf,11 text
        # 早期版本把 left 当成 cols[5]，结果所有行的 y 都相同、行序完全错乱——列索引必须逐一对齐
        try:
            conf = float(cols[10])
            left, top, width, height = (int(cols[6]), int(cols[7]), int(cols[8]), int(cols[9]))
        except ValueError:
            continue
        if conf < 0:
            continue
        key = (block, par, line)
        entry = lines.get(key)
        if entry is None:
            lines[key] = {
                "words": [text],
                "confs": [conf],
                "x1": left,
                "y1": top,
                "x2": left + width,
                "y2": top + height,
            }
        else:
            entry["words"].append(text)
            entry["confs"].append(conf)
            entry["x1"] = min(entry["x1"], left)
            entry["y1"] = min(entry["y1"], top)
            entry["x2"] = max(entry["x2"], left + width)
            entry["y2"] = max(entry["y2"], top + height)

    result = []
    # 按行框的纵坐标排序，而不是按 (block, par, line) 编号——
    # psm 6 下 block 编号与阅读顺序不一致，会出来"功效在标题前"这种乱序
    for key, entry in sorted(lines.items(), key=lambda kv: (kv[1]["y1"], kv[1]["x1"])):
        result.append({
            "text": _join_words(entry["words"]),
            "confidence": round(sum(entry["confs"]) / len(entry["confs"]) / 100.0, 4),
            "box": [entry["x1"], entry["y1"], entry["x2"], entry["y2"]],
        })
    return result


def _join_words(words):
    """拼一行内的词。

    中文（含中文标点）紧邻时不加空格——Tesseract 对中文是逐字出词，
    直接 join 空格会得到"四 君 子 汤"，下游抽取与展示都会变形。
    含 ASCII 的词之间保留空格，避免 "BMI28.4" 这类粘连。
    """
    out = []
    for word in words:
        if not out:
            out.append(word)
        elif _is_cjk_like(out[-1][-1]) and _is_cjk_like(word[0]):
            out.append(word)
        else:
            out.append(" " + word)
    return "".join(out)


def _is_cjk_like(ch):
    code = ord(ch)
    # CJK 统一表意文字 / 中文标点 / 全角符号
    return (0x4E00 <= code <= 0x9FFF) or (0x3000 <= code <= 0x303F) or (0xFF00 <= code <= 0xFFEF)


@app.get("/health")
def health():
    completed = subprocess.run(["tesseract", "--version"], capture_output=True)
    version = completed.stdout.decode("utf-8", "ignore").splitlines()[0] if completed.stdout else "unknown"
    return jsonify({"status": "ok", "engine": "tesseract", "version": version, "defaultLanguage": DEFAULT_LANG})


@app.post("/ocr")
def ocr():
    upload = request.files.get("file")
    if upload is None:
        return jsonify({"error": "missing file part"}), 400
    language = request.form.get("language") or DEFAULT_LANG
    try:
        image = Image.open(io.BytesIO(upload.read()))
    except Exception as exc:  # noqa: BLE001 - 任何解码失败都是 4xx
        return jsonify({"error": f"cannot decode image: {exc}"}), 400

    try:
        lines = _group_lines(_run_tesseract_tsv(image, language))
    except subprocess.TimeoutExpired:
        return jsonify({"error": "tesseract timeout"}), 504
    except Exception as exc:  # noqa: BLE001
        return jsonify({"error": str(exc)}), 400

    return jsonify({"engine": "tesseract", "language": language, "lines": lines})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8868")))
