"""RAGFlow HTTP API v1 的最小实现，用于在没有真实 RAGFlow 实例时验证客户端集成。

它实现的是**契约**，不是检索算法：
  - 响应信封统一为 {"code":0,"message":"success","data":...}，业务错误返回非 0 的 code 而 HTTP 仍为 200；
    这一点是 RAGFlow 的真实行为，客户端如果只看 HTTP 状态码会漏判。
  - 解析是异步的：触发 /chunks 后先 RUNNING，约 1.2 秒后变 DONE，逼真复现轮询路径。
  - 检索用中文二元组重合度打分（与项目本地检索同算法），只用于验证"调用链通了、结果能解析"。

⚠️ 这不是 RAGFlow 的替代品：不包含 DeepDOC 版面分析、向量检索、重排与 GraphRAG。
   生产必须指向真实实例（healthos.retrieval.ragflow.base-url）。
"""

import math
import os
import time
import uuid

from flask import Flask, jsonify, request

app = Flask(__name__)

API_KEY = os.environ.get("RAGFLOW_API_KEY", "ragflow-local-test-key")
PARSE_DURATION = float(os.environ.get("RAGFLOW_PARSE_SECONDS", "1.2"))

DATASETS = {}
DOCUMENTS = {}
CHUNKS = {}


def envelope(data=None, code=0, message="success"):
    body = {"code": code, "message": message}
    if data is not None:
        body["data"] = data
    return jsonify(body)


def unauthorized():
    return envelope(code=109, message="Authentication error: API key is invalid")


def require_auth():
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer ") or header[7:].strip() != API_KEY:
        return False
    return True


def new_id():
    return uuid.uuid4().hex


# ---------------------------------------------------------------- 数据集

@app.post("/api/v1/datasets")
def create_dataset():
    if not require_auth():
        return unauthorized()
    body = request.get_json(silent=True) or {}
    name = (body.get("name") or "").strip()
    if not name:
        return envelope(code=101, message="Field: <name> is required.")
    if body.get("parse_type") not in (1, 2, None):
        return envelope(code=101, message="Field: <parse_type> must be 1 or 2.")
    dataset_id = new_id()
    DATASETS[dataset_id] = {
        "id": dataset_id,
        "name": name,
        "language": body.get("language", "English"),
        "parser_id": body.get("parser_id", "naive"),
        "parse_type": body.get("parse_type", 1),
        "embedding_model": body.get("embedding_model", "stub"),
        "chunk_count": 0,
        "document_count": 0,
        "create_time": int(time.time() * 1000),
    }
    return envelope(DATASETS[dataset_id])


@app.get("/api/v1/datasets")
def list_datasets():
    if not require_auth():
        return unauthorized()
    page = int(request.args.get("page", 1))
    page_size = int(request.args.get("page_size", 30))
    items = list(DATASETS.values())
    start = (page - 1) * page_size
    # 真实 RAGFlow v1 的 data 就是数组（不像 documents 那样包一层 docs）。
    # 桩必须跟真实契约一致，否则"按桩开发"的代码接真服务就崩。
    return envelope(items[start:start + page_size])


@app.delete("/api/v1/datasets")
def delete_datasets():
    if not require_auth():
        return unauthorized()
    body = request.get_json(silent=True) or {}
    ids = body.get("ids") or []
    if not ids:
        return envelope(code=102, message="`ids` is required.")
    removed = 0
    for dataset_id in ids:
        if DATASETS.pop(dataset_id, None) is not None:
            removed += 1
        for doc_id in [k for k, v in DOCUMENTS.items() if v["dataset_id"] == dataset_id]:
            DOCUMENTS.pop(doc_id, None)
            CHUNKS.pop(doc_id, None)
    return envelope({"removed": removed})


# ---------------------------------------------------------------- 文档

@app.post("/api/v1/datasets/<dataset_id>/documents")
def upload_document(dataset_id):
    if not require_auth():
        return unauthorized()
    if dataset_id not in DATASETS:
        return envelope(code=102, message=f"You don't own the dataset {dataset_id}.")
    upload = request.files.get("file")
    if upload is None:
        return envelope(code=101, message="No file part!")

    text = upload.read().decode("utf-8", "ignore")
    doc_id = new_id()
    DOCUMENTS[doc_id] = {
        "id": doc_id,
        "dataset_id": dataset_id,
        "name": upload.filename or "unnamed.md",
        "location": upload.filename or "unnamed.md",
        "size": len(text.encode("utf-8")),
        "type": "doc",
        "run": "UNSTART",
        "progress": 0.0,
        "progress_msg": "",
        "chunk_count": 0,
        "chunk_method": DATASETS[dataset_id].get("parser_id", "naive"),
        "source_type": "local",
        "text": text,
        "triggered_at": None,
        "create_time": int(time.time() * 1000),
    }
    DATASETS[dataset_id]["document_count"] += 1
    return envelope([{k: v for k, v in DOCUMENTS[doc_id].items() if k != "text"}])


@app.get("/api/v1/datasets/<dataset_id>/documents")
def list_documents(dataset_id):
    if not require_auth():
        return unauthorized()
    if dataset_id not in DATASETS:
        return envelope(code=102, message=f"You don't own the dataset {dataset_id}.")
    doc_id = request.args.get("id")
    docs = [d for d in DOCUMENTS.values() if d["dataset_id"] == dataset_id]
    if doc_id:
        docs = [d for d in docs if d["id"] == doc_id]
    payload = []
    for doc in docs:
        advance(doc)
        payload.append({k: v for k, v in doc.items() if k != "text"})
    return envelope({"docs": payload, "total_datasets": len(payload)})


@app.post("/api/v1/datasets/<dataset_id>/chunks")
def trigger_parse(dataset_id):
    if not require_auth():
        return unauthorized()
    body = request.get_json(silent=True) or {}
    ids = body.get("document_ids") or []
    if not ids:
        return envelope(code=102, message="`document_ids` is required")
    for doc_id in ids:
        doc = DOCUMENTS.get(doc_id)
        if doc is None or doc["dataset_id"] != dataset_id:
            return envelope(code=102, message=f"Document {doc_id} not found in dataset {dataset_id}")
        doc["run"] = "RUNNING"
        doc["progress"] = 0.1
        doc["progress_msg"] = "stub: parsing"
        doc["triggered_at"] = time.time()
    return envelope()


def advance(doc):
    """把 RUNNING 按时间推进到 DONE，并在这里切片——模拟真实解析的异步性。"""
    if doc["run"] != "RUNNING" or doc["triggered_at"] is None:
        return
    elapsed = time.time() - doc["triggered_at"]
    if elapsed < PARSE_DURATION:
        doc["progress"] = round(min(0.9, 0.1 + 0.8 * elapsed / PARSE_DURATION), 2)
        return
    chunks = split_chunks(doc["text"])
    CHUNKS[doc["id"]] = chunks
    doc["chunk_count"] = len(chunks)
    doc["progress"] = 1.0
    doc["run"] = "DONE"
    doc["progress_msg"] = "stub: done"
    DATASETS[doc["dataset_id"]]["chunk_count"] += len(chunks)


def split_chunks(text, size=200, overlap=20):
    chunks = []
    index = 0
    start = 0
    while start < len(text):
        end = min(len(text), start + size)
        content = text[start:end].strip()
        if content:
            chunks.append({"id": new_id(), "content": content, "index": index})
            index += 1
        if end >= len(text):
            break
        start = max(end - overlap, start + 1)
    return chunks


# ---------------------------------------------------------------- 检索

@app.post("/api/v1/retrieval")
def retrieval():
    if not require_auth():
        return unauthorized()
    body = request.get_json(silent=True) or {}
    question = (body.get("question") or "").strip()
    dataset_ids = body.get("dataset_ids") or []
    if not question:
        return envelope(code=102, message="`question` is required.")
    if not dataset_ids:
        return envelope(code=102, message="`dataset_ids` is required.")

    document_ids = body.get("document_ids") or []
    page_size = int(body.get("page_size", 30))
    threshold = float(body.get("similarity_threshold", 0.0))
    # 与 RAGFlow /retrieval 的混合检索口径一致：最终分 = 向量分 * w + 词面分 * (1-w)。
    # stub 没有真向量，用两种近似分模拟权重的**方向性**——调大 vector 权重分数会上升。
    # 这只保证"参数确实透传并被使用"，不代表真实召回质量。
    keywords_weight = float(body.get("keywords_similarity_weight",
                                     1.0 - float(body.get("vector_similarity_weight", 0.7))))
    rerank_id = body.get("rerank_id") or ""
    # 打到 stdout，方便用 docker logs 核对调用方到底传了什么（契约对账用，不是业务日志）
    print(f"[retrieval] question={question[:24]!r} page_size={page_size} "
          f"threshold={threshold} keywords_weight={round(keywords_weight, 4)} rerank_id={rerank_id!r}",
          flush=True)

    query_tokens = tokens(question)
    scored = []
    for doc_id, chunks in CHUNKS.items():
        doc = DOCUMENTS.get(doc_id)
        if doc is None or doc["dataset_id"] not in dataset_ids:
            continue
        if document_ids and doc_id not in document_ids:
            continue
        for chunk in chunks:
            chunk_tokens = tokens(chunk["content"])
            if not chunk_tokens:
                continue
            matched = len(query_tokens & chunk_tokens)
            if matched == 0:
                continue
            coverage = matched / max(1, len(query_tokens))
            penalty = 1.0 / (1.0 + math.log1p(len(chunk_tokens)) / 10.0)
            term_similarity = round(coverage * (0.9 + 0.1 * penalty), 4)
            vector_similarity = round(coverage, 4)
            score = round(vector_similarity * (1.0 - keywords_weight)
                          + term_similarity * keywords_weight, 4)
            if score < threshold:
                continue
            scored.append({
                "id": chunk["id"],
                "content": chunk["content"],
                "document_id": doc_id,
                "document_keyword": doc["name"],
                "dataset_id": doc["dataset_id"],
                "similarity": score,
                "term_similarity": term_similarity,
                "vector_similarity": vector_similarity,
                "positions": [],
            })
    scored.sort(key=lambda item: item["similarity"], reverse=True)
    top = scored[:page_size]
    return envelope({"chunks": top, "total": len(top), "doc_aggs": [],
                     "echo": {"keywords_similarity_weight": keywords_weight,
                              "similarity_threshold": threshold,
                              "rerank_id": rerank_id}})


def tokens(text):
    """中文二元组 + 英文/数字词，与项目本地检索保持同一口径，便于对比降级效果。"""
    result = set()
    ascii_buffer = []
    cjk_buffer = []

    def flush_cjk():
        if len(cjk_buffer) == 1:
            result.add(cjk_buffer[0])
        else:
            for i in range(len(cjk_buffer) - 1):
                result.add(cjk_buffer[i] + cjk_buffer[i + 1])
        cjk_buffer.clear()

    def flush_ascii():
        if len(ascii_buffer) >= 2:
            result.add("".join(ascii_buffer))
        ascii_buffer.clear()

    for ch in text or "":
        if ch.isascii() and (ch.isalnum() or ch == "_"):
            flush_cjk()
            ascii_buffer.append(ch.lower())
            continue
        flush_ascii()
        if "\u4e00" <= ch <= "\u9fff":
            cjk_buffer.append(ch)
        else:
            flush_cjk()
    flush_ascii()
    flush_cjk()
    return result


@app.get("/health")
def health():
    return jsonify({"status": "ok", "engine": "ragflow-stub", "datasets": len(DATASETS)})


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "9380")))
