"""Proxy TCP que registra o tráfego SDB entre o Tizen Studio e a TV.

Uso: rode o proxy no PC, faça ``sdb connect 127.0.0.1:<porta>`` e instale pelo
Tizen Studio normalmente. Cada mensagem SDB vai para o log decodificada; o
conteúdo de arquivos (DATA do sync) aparece só como tamanho.
"""

from __future__ import annotations

import socket
import struct
import threading
import time
from typing import Callable, TextIO

from .sdb import HEADER_SIZE, Message, SdbError, command_name, unpack_header


class FrameDecoder:
    """Remonta mensagens SDB a partir de pedaços arbitrários do fluxo TCP."""

    def __init__(self) -> None:
        self._buf = bytearray()

    def feed(self, data: bytes) -> list[Message]:
        self._buf += data
        messages = []
        while len(self._buf) >= HEADER_SIZE:
            command, arg0, arg1, length, _ = unpack_header(bytes(self._buf[:HEADER_SIZE]))
            if len(self._buf) < HEADER_SIZE + length:
                break
            payload = bytes(self._buf[HEADER_SIZE:HEADER_SIZE + length])
            del self._buf[:HEADER_SIZE + length]
            messages.append(Message(command, arg0, arg1, payload))
        return messages


def describe(msg: Message, preview: int = 200) -> str:
    data = msg.data
    if command_name(msg.command) == "WRTE" and data[:4] == b"DATA" and len(data) >= 8:
        (size,) = struct.unpack_from("<I", data, 4)
        return f"WRTE arg0={msg.arg0:#x} arg1={msg.arg1:#x} sync DATA {size} bytes (total {len(data)})"
    return msg.describe(preview)


def _pump(src: socket.socket, dst: socket.socket, label: str, emit: Callable[[str], None]) -> None:
    decoder = FrameDecoder()
    try:
        while chunk := src.recv(65536):
            dst.sendall(chunk)
            try:
                for msg in decoder.feed(chunk):
                    emit(f"{label} {describe(msg)}")
            except SdbError as exc:
                emit(f"{label} fluxo não-SDB: {exc}")
                decoder = FrameDecoder()
    except OSError as exc:
        emit(f"{label} conexão encerrada: {exc}")
    finally:
        for sock in (src, dst):
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def serve(
    tv_host: str,
    tv_port: int,
    listen_port: int,
    out: TextIO,
    listen_host: str = "127.0.0.1",
    ready: threading.Event | None = None,
    stop: threading.Event | None = None,
) -> None:
    lock = threading.Lock()

    def emit(line: str) -> None:
        with lock:
            out.write(f"{time.strftime('%H:%M:%S')} {line}\n")
            out.flush()

    with socket.create_server((listen_host, listen_port)) as server:
        server.settimeout(0.5)
        if ready:
            ready.set()
        emit(f"proxy {listen_host}:{listen_port} -> {tv_host}:{tv_port}")
        while not (stop and stop.is_set()):
            try:
                client, addr = server.accept()
            except socket.timeout:
                continue
            try:
                upstream = socket.create_connection((tv_host, tv_port), timeout=10)
                upstream.settimeout(None)
            except OSError as exc:
                emit(f"não foi possível conectar à TV: {exc}")
                client.close()
                continue
            emit(f"nova conexão de {addr[0]}:{addr[1]}")
            for src, dst, label in ((client, upstream, "PC->TV"), (upstream, client, "TV->PC")):
                threading.Thread(target=_pump, args=(src, dst, label, emit), daemon=True).start()
