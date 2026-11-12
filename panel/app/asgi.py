"""Ponto de entrada do uvicorn: ``uvicorn app.asgi:app``."""

from .main import create_app

app = create_app()
