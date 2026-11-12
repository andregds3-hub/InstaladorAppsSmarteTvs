"""Cliente SDB mínimo, falando direto com o sdbd da TV pela rede local.

O SDB é derivado do ADB: mesmo cabeçalho de 24 bytes e as mensagens
CNXN/OPEN/OKAY/WRTE/CLSE. O que foi confirmado e o que ainda depende de
captura em TV real está em docs/sdb-protocolo.md.
"""

from __future__ import annotations

import os
import socket
import struct
import time
from dataclasses import dataclass, field
from typing import Callable

SDB_PORT = 26101

A_CNXN = 0x4E584E43
A_OPEN = 0x4E45504F
A_OKAY = 0x59414B4F
A_CLSE = 0x45534C43
A_WRTE = 0x45545257

# Valores que o sdb 4.2.36 (Tizen Studio 10) envia, segundo captura publicada
# em kromatv/react-native-tizen. A TV responde com a própria versão e maxdata.
HOST_VERSION = 0x00100000
HOST_MAXDATA = 0x40000
HOST_BANNER = b"host::\x00"

SYNC_CHUNK = 64 * 1024
DEFAULT_SDK_TOOLPATH = "/home/owner/share/tmp/sdk_tools"

_HEADER = struct.Struct("<6I")

Progress = Callable[[int, int], None]
Trace = Callable[[str, "Message"], None]


class SdbError(Exception):
    """Falha de comunicação com o sdbd."""


class SdbServiceRefused(SdbError):
    """A TV fechou o serviço sem aceitá-lo (CLSE em resposta a OPEN)."""


def checksum(data: bytes) -> int:
    return sum(data) & 0xFFFFFFFF


def command_name(command: int) -> str:
    return struct.pack("<I", command).decode("ascii", errors="replace")


@dataclass(frozen=True)
class Message:
    command: int
    arg0: int
    arg1: int
    data: bytes = b""

    def pack(self) -> bytes:
        header = _HEADER.pack(
            self.command,
            self.arg0,
            self.arg1,
            len(self.data),
            checksum(self.data),
            self.command ^ 0xFFFFFFFF,
        )
        return header + self.data

    def describe(self, preview: int = 64) -> str:
        shown = self.data[:preview]
        text = shown.decode("ascii", errors="replace").replace("\x00", "\\0")
        more = f"…(+{len(self.data) - preview})" if len(self.data) > preview else ""
        return (
            f"{command_name(self.command)} arg0={self.arg0:#x} arg1={self.arg1:#x} "
            f"len={len(self.data)} {text!r}{more}"
        )


HEADER_SIZE = _HEADER.size


def unpack_header(raw: bytes) -> tuple[int, int, int, int, int]:
    """Devolve (command, arg0, arg1, length, checksum) e valida o magic."""
    command, arg0, arg1, length, crc, magic = _HEADER.unpack(raw)
    if magic != command ^ 0xFFFFFFFF:
        raise SdbError(f"cabeçalho SDB inválido (magic {magic:#010x} para comando {command:#010x})")
    return command, arg0, arg1, length, crc


def recv_exact(sock: socket.socket, size: int) -> bytes:
    buf = bytearray()
    while len(buf) < size:
        chunk = sock.recv(size - len(buf))
        if not chunk:
            raise SdbError("a TV fechou a conexão")
        buf += chunk
    return bytes(buf)


def read_message(sock: socket.socket) -> Message:
    command, arg0, arg1, length, crc = unpack_header(recv_exact(sock, HEADER_SIZE))
    data = recv_exact(sock, length) if length else b""
    # Versões novas do ADB mandam checksum 0; só conferimos quando vier preenchido.
    if crc and checksum(data) != crc:
        raise SdbError(f"checksum inválido em {command_name(command)}")
    return Message(command, arg0, arg1, data)


@dataclass(frozen=True)
class DeviceBanner:
    raw: str
    kind: str
    model: str
    properties: dict[str, str] = field(default_factory=dict)


def parse_banner(data: bytes) -> DeviceBanner:
    """Interpreta o banner do CNXN da TV, por exemplo ``device::<modelo>::0``."""
    text = data.split(b"\x00", 1)[0].decode("utf-8", errors="replace")
    parts = text.split("::")
    kind = parts[0] if parts else ""
    model = parts[1] if len(parts) > 1 and "=" not in parts[1] else ""
    properties: dict[str, str] = {}
    for part in parts[1:]:
        for item in part.split(";"):
            if "=" in item:
                key, value = item.split("=", 1)
                properties[key.strip()] = value.strip()
    return DeviceBanner(text, kind, model, properties)


def parse_capability(data: bytes) -> dict[str, str]:
    """Interpreta a resposta do serviço ``capability:`` (linhas ``chave:valor``).

    A captura publicada mostra um prefixo uint16 com o tamanho; aceitamos também
    a resposta sem prefixo ou com o prefixo hexadecimal de 4 dígitos do ADB.
    """
    body = data
    if len(body) >= 2:
        (size_le,) = struct.unpack_from("<H", body)
        (size_be,) = struct.unpack_from(">H", body)
        if len(body) - 2 in (size_le, size_be):
            body = body[2:]
    if len(body) >= 4:
        head = body[:4].decode("ascii", errors="replace")
        if all(c in "0123456789abcdefABCDEF" for c in head) and int(head, 16) == len(body) - 4:
            body = body[4:]
    result: dict[str, str] = {}
    for line in body.decode("utf-8", errors="replace").replace("\x00", "").splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            result[key.strip()] = value.strip()
    return result


class SdbStream:
    """Um serviço aberto na conexão (shell:, sync:, capability:)."""

    def __init__(self, client: SdbClient, local_id: int, remote_id: int, service: str):
        self._client = client
        self.local_id = local_id
        self.remote_id = remote_id
        self.service = service
        self.closed = False
        self._pending = bytearray()

    def write(self, data: bytes) -> None:
        step = self._client.max_payload
        for offset in range(0, len(data), step):
            if self.closed:
                raise SdbError(f"a TV fechou o canal {self.service!r} durante o envio")
            self._client.send(Message(A_WRTE, self.local_id, self.remote_id, data[offset:offset + step]))
            self._wait_okay()

    def _wait_okay(self) -> None:
        while True:
            msg = self._client.receive_for(self.local_id)
            if msg.command == A_OKAY:
                return
            if msg.command == A_WRTE:
                self._accept(msg)
                continue
            if msg.command == A_CLSE:
                self.closed = True
                raise SdbError(f"a TV fechou o canal {self.service!r} durante o envio")

    def _accept(self, msg: Message) -> None:
        self._pending += msg.data
        self._client.send(Message(A_OKAY, self.local_id, self.remote_id))

    def _fill(self) -> bool:
        """Lê a próxima mensagem do canal; devolve False quando a TV fechou."""
        if self.closed:
            return False
        while True:
            msg = self._client.receive_for(self.local_id)
            if msg.command == A_WRTE:
                self._accept(msg)
                return True
            if msg.command == A_CLSE:
                self.closed = True
                return False
            # OKAY atrasado de um WRTE nosso: nada a fazer.

    def read_exact(self, size: int) -> bytes:
        while len(self._pending) < size:
            if not self._fill():
                raise SdbError(f"o canal {self.service!r} fechou antes da resposta completa")
        data = bytes(self._pending[:size])
        del self._pending[:size]
        return data

    def read_all(self) -> bytes:
        while self._fill():
            pass
        data = bytes(self._pending)
        self._pending.clear()
        return data

    @property
    def pending(self) -> bytes:
        return bytes(self._pending)

    def close(self) -> None:
        if not self.closed:
            self.closed = True
            try:
                self._client.send(Message(A_CLSE, self.local_id, self.remote_id))
            except OSError:
                pass


class SdbClient:
    """Conexão SDB com uma TV. Um serviço por vez, como o instalador precisa."""

    def __init__(self, host: str, port: int = SDB_PORT, timeout: float = 15.0, trace: Trace | None = None):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.trace = trace
        self.max_payload = 4096
        self.device_version = 0
        self.banner: DeviceBanner | None = None
        self._sock: socket.socket | None = None
        self._next_id = 1

    def __enter__(self) -> SdbClient:
        self.connect()
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def connect(self) -> DeviceBanner:
        try:
            self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        except OSError as exc:
            raise SdbError(f"não foi possível abrir {self.host}:{self.port}: {exc}") from exc
        self._sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.send(Message(A_CNXN, HOST_VERSION, HOST_MAXDATA, HOST_BANNER))
        msg = self.receive()
        if msg.command != A_CNXN:
            raise SdbError(f"resposta inesperada ao CNXN: {msg.describe()}")
        self.device_version = msg.arg0
        self.max_payload = max(256, min(msg.arg1 or 4096, HOST_MAXDATA))
        self.banner = parse_banner(msg.data)
        return self.banner

    def close(self) -> None:
        if self._sock is not None:
            try:
                self._sock.close()
            finally:
                self._sock = None

    def send(self, msg: Message) -> None:
        if self._sock is None:
            raise SdbError("conexão SDB não está aberta")
        if self.trace:
            self.trace(">>", msg)
        self._sock.sendall(msg.pack())

    def receive(self) -> Message:
        if self._sock is None:
            raise SdbError("conexão SDB não está aberta")
        try:
            msg = read_message(self._sock)
        except socket.timeout as exc:
            raise SdbError("a TV não respondeu a tempo") from exc
        except ConnectionError as exc:
            raise SdbError(f"a TV encerrou a conexão: {exc}") from exc
        if self.trace:
            self.trace("<<", msg)
        return msg

    def receive_for(self, local_id: int) -> Message:
        """Próxima mensagem endereçada ao canal ``local_id``; descarta restos de canais antigos."""
        while True:
            msg = self.receive()
            if msg.arg1 == local_id:
                return msg

    def open(self, service: str) -> SdbStream:
        local_id = self._next_id
        self._next_id += 1
        self.send(Message(A_OPEN, local_id, 0, service.encode("utf-8") + b"\x00"))
        while True:
            msg = self.receive_for(local_id)
            if msg.command == A_OKAY:
                return SdbStream(self, local_id, msg.arg0, service)
            if msg.command == A_CLSE:
                raise SdbServiceRefused(f"a TV recusou o serviço {service!r}")

    def _with_timeout(self, timeout: float | None):
        sock = self._sock
        previous = sock.gettimeout() if sock else None
        if sock and timeout is not None:
            sock.settimeout(timeout)
        return sock, previous

    def capability(self) -> dict[str, str]:
        stream = self.open("capability:")
        return parse_capability(stream.read_all())

    def shell(self, command: str, timeout: float | None = None) -> str:
        sock, previous = self._with_timeout(timeout)
        try:
            stream = self.open(f"shell:{command}")
            return stream.read_all().decode("utf-8", errors="replace")
        finally:
            if sock and timeout is not None:
                sock.settimeout(previous)

    def push(self, local_path: str, remote_path: str, mode: int = 0o755, progress: Progress | None = None) -> None:
        """Envia um arquivo pelo protocolo sync (SEND/DATA/DONE), como ``sdb push``."""
        total = os.path.getsize(local_path)
        stream = self.open("sync:")
        try:
            spec = f"{remote_path},{mode}".encode("utf-8")
            try:
                stream.write(b"SEND" + struct.pack("<I", len(spec)) + spec)
                sent = 0
                with open(local_path, "rb") as fh:
                    while chunk := fh.read(SYNC_CHUNK):
                        stream.write(b"DATA" + struct.pack("<I", len(chunk)) + chunk)
                        sent += len(chunk)
                        if progress:
                            progress(sent, total)
                stream.write(b"DONE" + struct.pack("<I", int(time.time())))
            except SdbError:
                failure = _sync_failure(stream.pending)
                if failure:
                    raise SdbError(f"a TV recusou o arquivo: {failure}") from None
                raise
            reply = stream.read_exact(8)
            status, size = reply[:4], struct.unpack("<I", reply[4:])[0]
            if status == b"FAIL":
                raise SdbError(f"a TV recusou o arquivo: {stream.read_exact(size).decode('utf-8', 'replace')}")
            if status != b"OKAY":
                raise SdbError(f"resposta inesperada do sync: {reply!r}")
            try:
                stream.write(b"QUIT" + struct.pack("<I", 0))
            except SdbError:
                pass
        finally:
            stream.close()


def _sync_failure(pending: bytes) -> str | None:
    if len(pending) >= 8 and pending[:4] == b"FAIL":
        (size,) = struct.unpack_from("<I", pending, 4)
        return pending[8:8 + size].decode("utf-8", errors="replace")
    return None
