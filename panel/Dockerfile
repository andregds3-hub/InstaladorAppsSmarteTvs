FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PANEL_DATA_DIR=/data

WORKDIR /srv
RUN useradd --create-home --uid 10001 panel
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
RUN mkdir -p /data && chown panel /data
USER panel

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz', timeout=3)"
# Um worker: o rate limit fica em memória. A porta 8000 só é alcançável pelo Caddy (rede do compose),
# por isso os cabeçalhos X-Forwarded-* são aceitos de qualquer origem dessa rede.
CMD ["uvicorn", "app.asgi:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*", "--workers", "1"]
