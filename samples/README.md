# 样例数据

用于冒烟与自检的小样本，都可以直接喂给接口。

| 文件 | 用途 |
| --- | --- |
| `sample-tcm.md` | 纯文本典籍样例（Markdown）。走**文本层**抽取路径，用来跑通「上传 → 切分 → 抽取实体关系 → 问答」全链路 |
| `scanned-classic.pdf` | **扫描件** PDF（无文本层），用来验证 OCR 路径。生成脚本见 `tools/ocr-service/make_scanned_pdf.py` |
| `scan-page.png` | 上面那个扫描件的首页位图，直接 POST 给 OCR 服务做单图自检 |

```bash
# 文本层：上传 Markdown
curl -b /tmp/ck.txt -H 'Idempotency-Key: demo-1' \
  -F 'file=@samples/sample-tcm.md' -F 'title=四君子汤与六味地黄丸' \
  http://127.0.0.1:18100/api/v1/classics

# 扫描件：上传 PDF（需先起 OCR 服务，见 tools/README.md）
curl -b /tmp/ck.txt -H 'Idempotency-Key: demo-2' \
  -F 'file=@samples/scanned-classic.pdf' -F 'title=扫描件样例' \
  http://127.0.0.1:18100/api/v1/classics

# 单图 OCR 自检
curl -s --noproxy '*' -F 'file=@samples/scan-page.png;type=image/png' \
  http://127.0.0.1:8868/ocr
```

重新生成扫描件：

```bash
python tools/ocr-service/make_scanned_pdf.py samples/scanned-classic.pdf samples/scan-page.png
```
