FROM docker.m.daocloud.io/library/ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# tesseract + 简繁中文语言包 + 中文字体（生成测试扫描件用）+ Flask（HTTP 层）
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-chi-sim \
        tesseract-ocr-chi-tra \
        python3 \
        python3-pil \
        python3-flask \
        fonts-wqy-microhei \
        fonts-wqy-zenhei \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY server.py /app/server.py
COPY make_scanned_pdf.py /app/make_scanned_pdf.py

EXPOSE 8868
HEALTHCHECK --interval=10s --timeout=5s --start-period=10s \
    CMD python3 -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8868/health').read()" || exit 1

CMD ["python3", "/app/server.py"]
