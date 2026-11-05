#!/usr/bin/env python3
"""pgvector ANN 真实验证：在跑着 pgvector 的库上，证明应用的检索 SQL 与暴力余弦一致，
且 HNSW 索引能大幅降低查询耗时。

不依赖任何 Python 数据库驱动：向量全部在库内生成（array_to_vector + random()），
通过 docker exec psql 执行。目的是给 README §3.21「为什么上 pgvector」一句句话提供实测依据。

验证方法（踩过的坑都写在这里）：
- 高维随机向量彼此近乎正交，cosine 相似度集中在 0 附近、方差极小，top-10 全是噪声近邻，
  拿"两条路 top-10 是否相同"当判据毫无意义（ANN 与精确都会给出不同的随机近邻）。
- 因此正确性判据改为：**把查询向量设成表里某一行自己的向量**，它的最近邻必然是自己
  （距离 0、相似度 1.0）。ANN 与全表扫描都必须把这一行排到第 1，且相似度≈1.0——
  这是确定性的，能真正证明索引没算错。
- 加速比：同一查询，强制全表扫描 vs 允许走 HNSW，看 Execution Time。
- 查询向量必须是**常量**（字面量或「标量子查询」，后者规划器会当作 InitPlan 常量），
  不能是 JOIN/普通子查询列；否则规划器看不到常量、不会走 HNSW，强制出来的只是全表扫描。
- 生成数据要在 LATERAL 里用 `WHERE g > 0` 这种**关联条件**逼出逐行执行，否则
  `array_agg(random())` 会被规划器当作不相关子查询只算一次，20 万行全是同一个向量。

用法：
    python tools/smoke/verify_pgvector.py [N]
默认 N=200000（万级量级，HNSW 才会被选中）。
"""
import subprocess
import sys

CONTAINER = "healthos-java-pg"
DB = "healthos"
USER = "healthos"


def psql(sql):
    out = subprocess.run(
        ["docker", "exec", CONTAINER, "psql", "-U", USER, "-d", DB, "-A", "-t", "-F", "|", "-c", sql],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        raise RuntimeError("psql failed: " + out.stderr.strip())
    return out.stdout.strip()


def parse_pairs(text):
    pairs = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("-"):
            continue
        parts = line.split("|")
        if len(parts) >= 2:
            try:
                pairs.append((parts[0], float(parts[1])))
            except ValueError:
                pass
    return pairs


def top1(text):
    pairs = parse_pairs(text)
    return (pairs[0][0], pairs[0][1]) if pairs else (None, None)


def exec_time(txt):
    for ln in txt.splitlines():
        if "Execution Time" in ln:
            return float(ln.split(":")[1].strip().replace(" ms", ""))
    return None


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 20000
    print(f"== pgvector 验证：N={n} 维=1024 ==")
    psql("DROP TABLE IF EXISTS t;")
    psql("CREATE TABLE t (id int PRIMARY KEY, v vector(1024));")
    print(f"生成 {n} 条归一化随机向量（库内生成，不落文件）……", flush=True)
    # 逐行生成真正不同的随机向量：LATERAL 里用 WHERE g>0 这种关联条件逼出逐行执行，
    # 否则 array_agg(random()) 会被规划器当作不相关子查询只算一次，N 行全是同一个向量。
    # 代价是每行都要扫 1024 个 random()，N=20000 约 30s，可接受。
    psql(
        f"INSERT INTO t(id, v) SELECT g, array_to_vector(a.arr, 1024, true) "
        f"FROM generate_series(1,{n}) g, "
        f"LATERAL (SELECT array_agg((random()*2-1)::real) "
        f"FROM generate_series(1,1024) gs WHERE g > 0) a(arr);"
    )
    psql("SET maintenance_work_mem='1GB'; CREATE INDEX IF NOT EXISTS ix_t ON t USING hnsw (v vector_cosine_ops);")
    print("HNSW 索引已建。")

    # 查询向量 = 表里 id=1 自己的向量：它的最近邻必然是自己（相似度 1.0）
    # 查询向量 = 表里 id=1 自己的向量（标量子查询，规划器作为常量处理，HNSW 能用）。
    # 不走文本往返：v::text 会丢精度，导致"q 不等于 v_1"的假象。
    print("查询向量 q = 第 1 行的向量（标量子查询，避免文本精度丢失）。")

    base = ("SELECT t.id, 1-(t.v <=> (SELECT v FROM t WHERE id=1)) AS s "
            "FROM t ORDER BY t.v <=> (SELECT v FROM t WHERE id=1) LIMIT 10;")
    # 精确：禁用索引 + 关闭并行，强制串行全表扫描（确定性参考）
    exact_sql = "SET max_parallel_workers_per_gather=0; SET enable_indexscan=off; SET enable_seqscan=on; " + base
    # ANN：默认，规划器自行决定是否走 HNSW
    ann_sql = "SET max_parallel_workers_per_gather=0; SET enable_indexscan=on; SET enable_seqscan=on; " + base

    exact_pairs = parse_pairs(psql(exact_sql))
    ann_pairs = parse_pairs(psql(ann_sql))
    exact_explain = psql("SET max_parallel_workers_per_gather=0; SET enable_indexscan=off; SET enable_seqscan=on; "
                         "EXPLAIN (ANALYZE, TIMING OFF, FORMAT TEXT) " + base)
    ann_explain = psql("SET max_parallel_workers_per_gather=0; SET enable_indexscan=on; SET enable_seqscan=on; "
                       "EXPLAIN (ANALYZE, TIMING OFF, FORMAT TEXT) " + base)

    exact_top1_id, exact_top1_score = top1(psql(exact_sql))
    ann_top1_id, ann_top1_score = top1(psql(ann_sql))
    index_used = "Index Scan" in ann_explain and "Seq Scan on t" not in ann_explain

    exact_ids = [r[0] for r in exact_pairs]
    ann_ids = [r[0] for r in ann_pairs]
    recall = len(set(exact_ids) & set(ann_ids)) / max(1, len(exact_ids))
    # 分数一致性（同 id 在两路里的相似度差）
    exact_score = dict(exact_pairs)
    max_diff = max((abs(sc - exact_score[c]) for c, sc in ann_pairs if c in exact_score), default=0.0)

    print("\n-- 正确性：查询向量 = 第 1 行自身 --")
    print(f"精确 top1: id={exact_top1_id} 相似度={exact_top1_score}")
    print(f"ANN   top1: id={ann_top1_id} 相似度={ann_top1_score}")
    print(f"同 id 最大分数差 = {max_diff:.2e}")
    print(f"recall@10 = {recall:.3f}  ({len(set(exact_ids) & set(ann_ids))}/10 重叠)  [高维随机下仅供参考]")

    print("\n-- 执行计划 --")
    print("精确（串行全表扫描）:"); print(exact_explain)
    print("ANN（HNSW）:"); print(ann_explain)

    et_exact, et_ann = exec_time(exact_explain), exec_time(ann_explain)
    speedup = (et_exact / et_ann) if (et_exact and et_ann and et_ann > 0) else 0
    print(f"\n全表扫描 {et_exact:.2f} ms  vs  HNSW {et_ann:.2f} ms  → 加速 {speedup:.1f}×")
    print(f"HNSW 实际被使用：{index_used}")

    psql("DROP TABLE IF EXISTS t;")

    ok = (
        exact_top1_id == "1" and abs(exact_top1_score - 1.0) < 1e-6
        and ann_top1_id == "1" and abs(ann_top1_score - 1.0) < 1e-6
        and max_diff < 1e-6
        and index_used
    )
    print("\n结论:", "PASS" if ok else "FAIL")
    if not ok:
        if exact_top1_id != "1" or abs(exact_top1_score - 1.0) >= 1e-6:
            print("  - 全表扫描没把查询向量自身排到第 1（参考基准本身就错了）")
        if ann_top1_id != "1" or abs(ann_top1_score - 1.0) >= 1e-6:
            print("  - HNSW 没把查询向量自身排到第 1（索引算错了）")
        if max_diff >= 1e-6:
            print("  - 两路同一 id 的相似度不一致")
        if not index_used:
            print("  - HNSW 未被使用（EXPLAIN 看不到 Index Scan / 仍有 Seq Scan on t）")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
