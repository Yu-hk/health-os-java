"""生成一份「没有文本层」的 PDF，用来验证扫描件 OCR 链路。

做法是把文字画成位图再存成 PDF：PDFBox 抽取文本层时得到的是空字符串，
从而必然走到 OCR 分支——这正是线上扫描件的特征。
"""

import os
import sys

from PIL import Image, ImageDraw, ImageFont

FONT_CANDIDATES = [
    # 实测 wqy-microhei 对 chi_sim 的识别率最高（"四君子汤/益气健脾/面色萎白"全对），
    # 明体（uming）与思源宋体反而更容易把"汤"认成"淘/沟/六"
    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
]

PAGES = [
    [
        "四君子汤",
        "出处：《太平惠民和剂局方》",
        "组成：人参、白术、茯苓、甘草各等分。",
        "功效：益气健脾。",
        "主治：脾胃气虚证，面色萎白，语声低微，",
        "四肢乏力，食少便溏，舌淡苔白，脉虚弱。",
    ],
    [
        "六味地黄丸",
        "出处：《小儿药证直诀》",
        "组成：熟地黄、山茱萸、山药、泽泻、牡丹皮、茯苓。",
        "功效：滋补肝肾。",
        "主治：肝肾阴虚证，腰膝酸软，头晕目眩，",
        "耳鸣耳聋，盗汗遗精，舌红少苔，脉沉细。",
    ],
]


def load_font(size: int) -> ImageFont.FreeTypeFont:
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    raise SystemExit("找不到中文字体，请安装 fonts-wqy-zenhei")


def render_page(lines, width=1654, height=2339, margin=150) -> Image.Image:
    """按 A4@200DPI 渲染，正文 48px、标题 68px。

    192 DPI 是经验最优区间：Tesseract 的 LSTM 会把行归一化到固定高度再送网络，
    同一段文字 48px 能全对，72px 就把"四君子汤"认成"四娩子交"。
    尺寸与 Java 端 healthos.ocr.dpi=200 对齐——位图在 PDF 里被再次缩放只会掉识别率。
    """
    image = Image.new("RGB", (width, height), "white")
    draw = ImageDraw.Draw(image)
    title_font = load_font(68)
    body_font = load_font(48)
    y = margin
    for index, line in enumerate(lines):
        font = title_font if index == 0 else body_font
        draw.text((margin, y), line, fill="black", font=font)
        y += 130 if index == 0 else 92
    return image


def main(output_path: str, png_path: str | None = None) -> None:
    pages = [render_page(lines) for lines in PAGES]
    pages[0].save(output_path, "PDF", resolution=200.0, save_all=True, append_images=pages[1:])
    print(f"wrote {output_path} ({len(pages)} pages, 无文本层)")
    if png_path:
        # 单独留一张首页位图，方便直接 curl 自检 OCR 服务（服务只吃图片，不吃 PDF）
        pages[0].save(png_path, "PNG")
        print(f"wrote {png_path} (第 1 页位图)")


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "/tmp/scanned-classic.pdf"
    png = sys.argv[2] if len(sys.argv) > 2 else None
    main(target, png)
