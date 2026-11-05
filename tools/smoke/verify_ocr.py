#!/usr/bin/env python3
"""OCR 链路的可复跑断言（契约 + 探活 + 端到端）。

为什么单独一个脚本，而不是塞进 verify_all.py：
本脚本要**独立对着 OCR 服务**打两发请求（不经过应用），verify_all.py 的夹具是
一次真实业务链路留下的数据，混进去会让"OCR 挂没挂"和"夹具还在不在"纠缠在一起。

验的是三件容易写错、写错了又看不出来的事：

  A. **服务契约**：PaddleOCR 的 hubserving 没有 `/health`，`GET /predict/ocr_system`
     回 405（路由在、方法不对）才是"在线"。按"2xx 才在线"写就会把好服务报成挂了。
  B. **应用侧探活**：`/health/dependencies` 的 `ocr` 必须是 `ok` 且整体 `status=ready`。
     这条专门盯 B 类缺陷——服务明明在线，健康检查却说 unavailable。
  C. **端到端**：上传仓库自带的扫描件，202 → 轮询到终态 → 分片抽出了字段。
     同一份文件重跑会被 sha256 幂等复用（`reused=true`），不会堆记录。

不验的事（说清楚，免得被当成"认可识别质量"）：
  - **识别准确率**。Paddle 与 Tesseract 谁准要靠人眼看，
    `samples/scan-page.png` 上 Paddle 6 行里 5 行逐字正确（第 6 行漏「溏」）、Tesseract 错 4 处；
    脚本只断言"有字、不是空"。
  - **配置层拒错**（provider/protocol 矛盾、base-url 带路径必须启动失败）。
    那属于启动期行为，由 `OcrConfigTest` 单测覆盖，脚本没法重启应用去验。

  HEALTHOS_BASE=http://127.0.0.1:18100 \
  HEALTHOS_OCR_BASE=http://127.0.0.1:8868 \
  HEALTHOS_OCR_PROVIDER=paddle \
  python tools/smoke/verify_ocr.py

跑完会在库里留下**一份文档（title=`scanned-classic`）与一张录入单**。
重跑不会重复堆：文档带固定 `Idempotency-Key`、录入单按 sha256 幂等复用。
想清干净（脚本刻意不连数据库，删除请手工确认后再执行）：

  DELETE FROM evidence/chunk_vector/document_chunk/graph_entity/graph_relation/
              extraction_checkpoint/pipeline_node/artifact/classic_job
    WHERE document_id IN (SELECT id FROM classic_document WHERE title='scanned-classic');
  DELETE FROM classic_document WHERE title='scanned-classic';
  DELETE FROM patient_intake_part WHERE exam_id = '<examId>';
  DELETE FROM patient_intake_exam WHERE id = '<examId>';
"""
import base64
import json
import mimetypes
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("HEALTHOS_BASE", "http://127.0.0.1:18100")
OCR_BASE = os.environ.get("HEALTHOS_OCR_BASE", "http://127.0.0.1:8868")
# paddle → /predict/ocr_system（base64 JSON）；generic → /ocr（multipart）
PROVIDER = os.environ.get("HEALTHOS_OCR_PROVIDER", "paddle").strip().lower()
USERNAME = os.environ.get("HEALTHOS_USER", "admin")
PASSWORD = os.environ.get("HEALTHOS_PASSWORD", "admin123")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SAMPLE_PDF = os.path.join(ROOT, "samples", "scanned-classic.pdf")
SAMPLE_PNG = os.path.join(ROOT, "samples", "scan-page.png")

PASS, FAIL = [], []


def check(name, cond, detail=""):
    if cond:
        PASS.append(name)
        print(f"PASS {name}")
    else:
        FAIL.append(name)
        print(f"FAIL {name} :: {detail}")


def jbody(text):
    try:
        return json.loads(text)
    except Exception:
        return {}


def raw(url, method="GET", data=None, headers=None, timeout=120):
    """不打 Cookie 的裸请求，用于直连 OCR 服务。"""
    request = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")


# ----------------------------------------------------------------- A. 服务契约

def probe_service():
    """直连 OCR 服务：先验"在线"的判据，再验一次真实识别。"""
    image = SAMPLE_PNG if os.path.exists(SAMPLE_PNG) else SAMPLE_PDF
    check("夹具存在（scan-page.png / scanned-classic.pdf）", os.path.exists(image), image)
    if not os.path.exists(image):
        return None

    if PROVIDER == "paddle":
        # 405 = 路由存在但方法不收 GET。这是 hubserving 唯一可靠的在线信号。
        status, _ = raw(OCR_BASE + "/predict/ocr_system", "GET")
        check("GET /predict/ocr_system 回 405（在线信号，不是失败）", status == 405,
              f"status={status}；若为 404 说明路由都没注册，服务是桩或版本不对")

        payload = json.dumps({"images": [base64.b64encode(open(image, "rb").read()).decode()]}).encode()
        status, body = raw(OCR_BASE + "/predict/ocr_system", "POST", payload,
                           {"Content-Type": "application/json"})
        check("POST /predict/ocr_system 回 200", status == 200, f"status={status} body={body[:200]}")
        results = (jbody(body).get("results") or [{}])[0]
        # hubserving 的 text 是**按行的字符串数组**（个别版本拼成一个 str），两种都要吃下
        rawtext = results.get("text")
        lines = rawtext if isinstance(rawtext, list) else str(rawtext or "").splitlines()
        lines = [str(line).strip() for line in lines if str(line).strip()]
        text = "\n".join(lines)
        check("识别结果非空", bool(text), f"results[0]={str(results)[:200]}")
        # 置信度也是逐行的数组；有值就说明走的是真模型而不是固定桩
        conf = results.get("confidence")
        check("带逐行置信度（说明真跑了模型，不是固定桩）",
              isinstance(conf, list) and len(conf) > 0, f"confidence={conf}")
        print(f"     识别 {len(lines)} 行，首行：{lines[0][:40] if lines else '(空)'}")
        return text

    # generic：multipart 上传，字段名 file
    boundary = "----verifyocr" + uuid.uuid4().hex
    with open(image, "rb") as handle:
        content = handle.read()
    mime = mimetypes.guess_type(image)[0] or "application/octet-stream"
    body = b"".join([
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{os.path.basename(image)}"\r\n'.encode(),
        f"Content-Type: {mime}\r\n\r\n".encode(),
        content,
        f"\r\n--{boundary}--\r\n".encode(),
    ])
    status, text_body = raw(OCR_BASE + "/ocr", "POST", body,
                            {"Content-Type": f"multipart/form-data; boundary={boundary}"})
    check("POST /ocr 回 200", status == 200, f"status={status} body={text_body[:200]}")
    lines = jbody(text_body).get("lines") or []
    check("返回带坐标的文本行", bool(lines) and all("text" in line for line in lines),
          f"lines={len(lines)}")
    return "\n".join(line.get("text", "") for line in lines)


# --------------------------------------------------------- B/C. 应用侧探活与端到端

class App:
    def __init__(self):
        self.cookie = None

    def req(self, path, method="GET", body=None, data=None, headers=None, timeout=180):
        final = {"Cookie": self.cookie} if self.cookie else {}
        if body is not None:
            data = json.dumps(body).encode()
            final["Content-Type"] = "application/json"
        if headers:
            final.update(headers)
        request = urllib.request.Request(BASE + path, data=data, method=method, headers=final)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, response.read().decode(), dict(response.headers)
        except urllib.error.HTTPError as error:
            return error.code, error.read().decode(), dict(error.headers)

    def login(self):
        status, _, headers = self.req("/api/v1/auth/login", "POST",
                                      {"username": USERNAME, "password": PASSWORD})
        if status == 200:
            self.cookie = headers.get("Set-Cookie", "").split(";")[0]
        return status


def multipart_file(path):
    boundary = "----verifyocr" + uuid.uuid4().hex
    with open(path, "rb") as handle:
        content = handle.read()
    mime = mimetypes.guess_type(path)[0] or "application/octet-stream"
    payload = b"".join([
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{os.path.basename(path)}"\r\n'.encode(),
        f"Content-Type: {mime}\r\n\r\n".encode(),
        content,
        f"\r\n--{boundary}--\r\n".encode(),
    ])
    return payload, {"Content-Type": f"multipart/form-data; boundary={boundary}"}


def check_app(ocr_text):
    """应用侧：探活 + 两条端到端路径（典籍文档 / 资料录入）。

    ocr_text 是 A 段直连识别得到的文本，用来跟库里的 chunk 对账——
    "服务能识别"和"识别结果进了库"是两件事，中间任何一环断了都会得到
    "上传成功、内容为空"这种假成功。
    """
    app = App()
    check("登录", app.login() == 200, "登录失败，后面的应用侧断言都跑不了")
    if not app.cookie:
        return

    status, body, _ = app.req("/health/dependencies")
    deps = jbody(body)
    ocr_state = str(deps.get("ocr") or "")
    check("依赖体检可读", status == 200 and "ocr" in deps, f"status={status}")
    # 本脚本的核心断言：服务在线时这里不能是 unavailable / disabled
    check("应用侧 ocr 状态为 ok", ocr_state.startswith("ok"),
          f"ocr={ocr_state}；为 unavailable 说明探活判据写错了，为 disabled 说明 provider=none")
    check("整体 status=ready", deps.get("status") == "ready", f"status={deps.get('status')}")

    if not os.path.exists(SAMPLE_PDF):
        check("夹具 samples/scanned-classic.pdf 存在", False, SAMPLE_PDF)
        return

    # ---------------------------------------------------------- 端到端 1：典籍文档
    payload, headers = multipart_file(SAMPLE_PDF)
    # 固定 Idempotency-Key：脚本可以反复跑，重跑复用同一份文档而不是每次堆一条。
    # 夹具名进了 key，换夹具不会误复用。
    headers["Idempotency-Key"] = "verify-ocr-" + os.path.basename(SAMPLE_PDF)
    status, body, _ = app.req("/api/v1/classics", "POST", data=payload, headers=headers)
    uploaded = jbody(body)
    check("上传扫描件到典籍回 202", status == 202, f"status={status} body={body[:200]}")
    document_id = uploaded.get("documentId")
    check("拿到 documentId", bool(document_id), str(uploaded)[:200])
    if not document_id:
        return

    detail = {}
    # 两页扫描件要走 OCR + tcm_extract（会调模型，是最慢的一环）+ 索引 + 图谱，
    # 实测单次 60–150s，故给 300s 上限；探到终态立刻停，不空转。
    # 文档级终态是 READY（不是节点级的 SUCCEEDED）——两者混用会让这条断言永远假失败。
    for _ in range(200):
        time.sleep(1.5)
        _, body, _ = app.req(f"/api/v1/classics/{document_id}")
        detail = jbody(body)
        if str((detail.get("document") or {}).get("status") or "").upper() in (
                "READY", "SUCCEEDED", "FAILED"):
            break
    document = detail.get("document") or {}
    check("文档跑到终态 READY", str(document.get("status") or "").upper() in ("READY", "SUCCEEDED"),
          f"status={document.get('status')} error={document.get('errorCode')} "
          f"{document.get('errorMessage')}")

    nodes = detail.get("nodes") or []
    print("     节点：" + "，".join(
        f"{n.get('code')}={n.get('status')}" for n in nodes))
    # 不断言具体节点 code（改了流水线就会假失败），只断言"有节点留下了 provider 痕迹"
    dump = json.dumps(nodes, ensure_ascii=False)
    check(f"节点 detail 里能看到 provider（{PROVIDER}）", PROVIDER in dump.lower(),
          dump[:300])

    status, body, _ = app.req(f"/api/v1/classics/{document_id}/chunks?page=1&size=50")
    chunks = jbody(body).get("items") or jbody(body).get("records") or []
    check("chunk 接口可读", status == 200 and bool(chunks), f"status={status} n={len(chunks)}")
    joined = "\n".join(str(c.get("content") or "") for c in chunks)
    check("chunk 正文非空", bool(joined.strip()), f"n={len(chunks)}")

    # 逐行对账：直连识别出来的行，应当能在库里找到。
    # 不对整段做 ==（分片会合并/切分行），但每一条对上的行都是"文本真落库"的硬证据。
    expect = [line for line in (ocr_text or "").splitlines() if len(line.strip()) >= 4]
    matched = [line for line in expect if line.strip() in joined]
    check("OCR 首行原样出现在 chunk 里", bool(expect) and expect[0].strip() in joined,
          f"期望含 {expect[0] if expect else '(无)'}；chunk 前 200 字={joined[:200]}")
    if expect:
        print(f"     对账：{len(matched)}/{len(expect)} 行在库中逐字命中")
    check("多数识别行都能在库中对上（≥50%）",
          bool(expect) and len(matched) * 2 >= len(expect),
          f"{len(matched)}/{len(expect)}")

    # ---------------------------------------------------------- 端到端 2：资料录入
    payload, headers = multipart_file(SAMPLE_PDF)
    status, body, _ = app.req("/api/v1/patient-intakes", "POST", data=payload, headers=headers)
    submitted = jbody(body)
    check("上传扫描件到资料录入回 202", status == 202, f"status={status} body={body[:200]}")
    exam_id = submitted.get("examId")
    check("拿到 examId", bool(exam_id), str(submitted)[:200])
    if not exam_id:
        return
    print(f"     examId={exam_id} reused={submitted.get('reused')}")

    intake = {}
    for _ in range(40):
        time.sleep(1.5)
        _, body, _ = app.req(f"/api/v1/patient-intakes/{exam_id}")
        intake = jbody(body)
        # UPLOADED → EXTRACTING → EXTRACTED → CONFIRMED；EXTRACTED 是"抽完了、等人签字"的合法终态
        if str(intake.get("status") or "").upper() in ("EXTRACTED", "CONFIRMED", "FAILED"):
            break
    terminal = str(intake.get("status") or "").upper()
    check("录入单抽取完成（EXTRACTED/CONFIRMED）", terminal in ("EXTRACTED", "CONFIRMED"),
          f"status={terminal} error={intake.get('errorCode')} {intake.get('errorMessage')}")

    parts = intake.get("parts") or []
    check("分片非空", bool(parts), f"parts={len(parts)}")
    check("所有分片都成功", bool(parts) and all(
        str(p.get("status") or "").upper() == "SUCCEEDED" for p in parts),
        str([f"{p.get('partKey')}={p.get('status')}" for p in parts])[:300])
    print(f"     extractor={intake.get('extractor')} parts={len(parts)}")
    # 这份夹具是典籍正文（四君子汤/六味地黄丸），**本来就没有化验指标**，
    # 所以不能断言 fields/metrics 非空——那会逼着测试去用一份"体检单扫描件"当夹具。
    # 要验字段抽取，请另外上传一张体检单扫描件，看 aggregate.fields。
    check("提取器标识存在", bool(intake.get("extractor")), str(intake.get("extractor")))


def main():
    print(f"OCR 服务 {OCR_BASE}  provider={PROVIDER}")
    print(f"应用      {BASE}\n")
    print("-- A. 服务契约（直连，不经过应用）")
    ocr_text = probe_service()
    print("\n-- B/C. 应用侧探活与端到端")
    check_app(ocr_text)
    print("\n结果：" + f"pass={len(PASS)} fail={len(FAIL)}")
    if FAIL:
        print("失败项：" + "，".join(FAIL))
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
