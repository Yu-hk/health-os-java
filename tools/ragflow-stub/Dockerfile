FROM docker.m.daocloud.io/library/ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-flask \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY server.py /app/server.py

EXPOSE 9380
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s \
    CMD python3 -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:9380/health').read()" || exit 1

CMD ["python3", "/app/server.py"]
