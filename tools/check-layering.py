#!/usr/bin/env python3
"""分层依赖校验：把 README §5.3 的依赖方向规则变成可执行的检查。

用法：
    python tools/check-layering.py            # 只打印违规，退出码 0/1
    python tools/check-layering.py --verbose  # 同时打印包规模与聚合依赖矩阵

为什么要有它：包结构看着整齐不等于依赖方向正确。一次"顺手 import 一下"
就能让 business 层依赖具体实现，而这种错编译器完全不报——
它只会在某天要换实现时，才以"改不动"的形式暴露出来。
"""
import os
import re
import sys
import collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.path.join(ROOT, "src", "main", "java", "com", "kangheng", "healthos")

# 禁止的依赖方向：(来源角色, 目标角色, 为什么不允许)
FORBIDDEN = [
    ("service", "provider", "业务层依赖具体实现：换实现就得改业务代码，接口形同虚设"),
    ("spi", "service", "扩展点契约依赖业务实现：契约不该知道谁实现它"),
    ("spi", "provider", "扩展点契约依赖具体实现：方向反了"),
    ("spi", "contract", "扩展点契约依赖对外契约：接口层不该知道 API 长什么样"),
    ("provider", "service", "适配器依赖应用层：适配器只该依赖端口与领域内核，不该反向调业务编排"),
    ("common", "config", "公共内核依赖装配层：换个配置方式就得改内核"),
    ("common", "service", "公共内核依赖业务层：内核会被业务拖着走"),
    ("model", "service", "值对象依赖业务编排：模型不该反过来调业务"),
    ("model", "provider", "值对象依赖具体实现：同上"),
    ("mapper", "service", "数据访问依赖业务层：分层倒置"),
    ("mapper", "provider", "数据访问依赖具体实现：同上"),
]

IMPORT_RE = re.compile(
    r'^\s*import\s+(?:static\s+)?com\.kangheng\.healthos\.([A-Za-z0-9_.]+);', re.M)

# javadoc 里的 {@link com.kangheng.healthos.xxx.Yyy}：不产生编译依赖，但同样会把
# 上层写进下层的注释里——换实现时这份注释就变成新的负担。
JAVADOC_RE = re.compile(
    r'\{@link(?:plain)?\s+com\.kangheng\.healthos\.([A-Za-z0-9_.]+)')


def load_packages():
    """返回 {包路径: [类名...]}。"""
    packages = collections.defaultdict(list)
    for dirpath, _dirnames, filenames in os.walk(BASE):
        for name in filenames:
            if name.endswith(".java"):
                pkg = os.path.relpath(dirpath, BASE).replace("\\", "/")
                packages[pkg].append(name[:-5])
    return packages


def resolve(dotted, packages):
    """把 com.kangheng.healthos.a.b.C 解析成包路径 a/b。"""
    parts = dotted.split(".")
    for i in range(len(parts), 0, -1):
        candidate = "/".join(parts[:i])
        if candidate in packages:
            return candidate
    return None


def collect_edges(packages):
    """返回 ([(来源类全路径, 目标包)], [(来源类全路径, 目标包)]) —— 前者是 import，后者是 javadoc。"""
    edges, doc_edges = [], []
    for pkg, classes in packages.items():
        for cls in classes:
            path = os.path.join(BASE, pkg, cls + ".java")
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            src = "%s/%s" % (pkg, cls)
            for match in IMPORT_RE.finditer(text):
                target = resolve(match.group(1), packages)
                if target and target != pkg:
                    edges.append((src, target))
            for match in JAVADOC_RE.finditer(text):
                target = resolve(match.group(1), packages)
                if target and target != pkg:
                    doc_edges.append((src, target))
    return edges, doc_edges


def role_of(pkg):
    return pkg.split("/")[0]


def find_violations(edges, forbidden):
    """返回 [(来源角色, 目标角色, 原因, [具体引用...])]。"""
    found = []
    for src_role, dst_role, reason in forbidden:
        hits = sorted({
            "%s  ->  %s" % (src, dst)
            for src, dst in edges
            if role_of(src) == src_role and role_of(dst) == dst_role
        })
        if hits:
            found.append((src_role, dst_role, reason, hits))
    return found


def main():
    verbose = "--verbose" in sys.argv
    if not os.path.isdir(BASE):
        print("找不到源码目录: %s" % BASE)
        return 2
    packages = load_packages()
    edges, doc_edges = collect_edges(packages)

    violations = find_violations(edges, FORBIDDEN)
    doc_violations = find_violations(doc_edges, FORBIDDEN)

    if violations or doc_violations:
        if violations:
            print("分层依赖违规（import）:")
            for src_role, dst_role, reason, hits in violations:
                print("\n  %s -> %s   （%s）" % (src_role, dst_role, reason))
                for hit in hits:
                    print("      " + hit)
        if doc_violations:
            print("\n分层依赖违规（javadoc {@link}，同样是方向错误，只是编译器不报）:")
            for src_role, dst_role, reason, hits in doc_violations:
                print("\n  %s -> %s" % (src_role, dst_role))
                for hit in hits:
                    print("      " + hit)
        print("\n共 %d 组违规，%d 处引用。"
              % (len(violations) + len(doc_violations),
                 sum(len(h[3]) for h in violations) + sum(len(h[3]) for h in doc_violations)))
        return 1

    print("分层依赖校验通过：%d 个包、%d 个类、%d 条跨包引用、%d 条 javadoc 引用，禁止方向 0 违规。"
          % (len(packages), sum(len(v) for v in packages.values()), len(edges), len(doc_edges)))

    if verbose:
        print("\n包规模（类数）:")
        for pkg in sorted(packages, key=lambda p: (-len(packages[p]), p)):
            print("  %-28s %d" % (pkg, len(packages[pkg])))

        print("\n跨包依赖聚合（行 import 列）:")
        roles = ["controller", "contract", "service", "mapper", "model",
                 "spi", "provider", "worker", "text", "common", "config"]
        matrix = collections.Counter()
        for src, dst in edges:
            if role_of(src) != role_of(dst):
                matrix[(role_of(src), role_of(dst))] += 1
        present = [r for r in roles if any(role_of(p) == r for p in packages)]
        print("           " + "".join("%10s" % r[:9] for r in present))
        for row in present:
            cells = "".join("%10s" % (matrix.get((row, col), 0) or ".")
                            for col in present)
            print("%-10s " % row + cells)
    return 0


if __name__ == "__main__":
    sys.exit(main())
