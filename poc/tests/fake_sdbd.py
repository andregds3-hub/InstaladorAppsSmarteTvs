"""sdbd falso para testes: fala CNXN/OPEN/WRTE/CLSE e o sync, sem TV real."""

from __future__ import annotations

import socket
import struct
import threading

from tizen_poc.sdb import A_CLSE, A_CNXN, A_OKAY, A_OPEN, A_WRTE, Message, SdbError, read_message

DEVICE_VERSION = 0x02000000


class FakeSdbd:
    def __init__(
        self,
        capability: dict[str, str] | None = None,
        shell_replies: dict[str, str] | None = None,
        refuse: set[str] | None = None,
        maxdata: int = 0x3FF9C,
        banner: bytes = b"device::QE55Q60::0\x00",
        sync_fail: str | None = None,
        fail_dirs: tuple[str, ...] = (),
    ):
        self.capability = capability if capability is not None else {
            "cpu_arch": "armv7",
            "platform_version": "6.5",
            "sdk_toolpath": "/home/owner/share/tmp/sdk_tools",
        }
        self.shell_replies = shell_replies or {}
        self.refuse = refuse or set()
        self.maxdata = maxdata
        self.banner = banner
        self.sync_fail = sync_fail
        self.fail_dirs = fail_dirs
        self.commands: list[str] = []
        self.files: dict[str, tuple[int, bytes]] = {}
        self.host_cnxn: Message | None = None
        self.max_wrte = 0
        self._server = socket.create_server(("127.0.0.1", 0))
        self.port = self._server.getsockname()[1]
        self._thread = threading.Thread(target=self._serve, daemon=True)

    def __enter__(self) -> FakeSdbd:
        self._thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self._server.close()

    def _serve(self) -> None:
        try:
            while True:
                conn, _ = self._server.accept()
                with conn:
                    try:
                        self._handle(conn)
                    except (SdbError, OSError):
                        pass
        except OSError:
            return

    def _send(self, conn: socket.socket, msg: Message) -> None:
        conn.sendall(msg.pack())

    def _handle(self, conn: socket.socket) -> None:
        self.host_cnxn = read_message(conn)
        self._send(conn, Message(A_CNXN, DEVICE_VERSION, self.maxdata, self.banner))
        next_id = 100
        syncs: dict[int, dict] = {}
        while True:
            msg = read_message(conn)
            if msg.command == A_OPEN:
                service = msg.data.rstrip(b"\x00").decode()
                host_id = msg.arg0
                if service in self.refuse:
                    self._send(conn, Message(A_CLSE, 0, host_id))
                    continue
                my_id = next_id
                next_id += 1
                self._send(conn, Message(A_OKAY, my_id, host_id))
                if service == "capability:":
                    body = "".join(f"{k}:{v}\n" for k, v in self.capability.items()).encode()
                    self._send(conn, Message(A_WRTE, my_id, host_id, struct.pack("<H", len(body)) + body))
                    self._send(conn, Message(A_CLSE, my_id, host_id))
                elif service.startswith("shell:"):
                    command = service[len("shell:"):]
                    self.commands.append(command)
                    reply = self.shell_replies.get(command, "").encode()
                    half = len(reply) // 2
                    for part in (reply[:half], reply[half:]):
                        if part:
                            self._send(conn, Message(A_WRTE, my_id, host_id, part))
                    self._send(conn, Message(A_CLSE, my_id, host_id))
                elif service == "sync:":
                    syncs[host_id] = {"id": my_id, "buf": bytearray(), "path": "", "mode": 0, "data": bytearray()}
            elif msg.command == A_WRTE and msg.arg0 in syncs:
                self.max_wrte = max(self.max_wrte, len(msg.data))
                state = syncs[msg.arg0]
                self._send(conn, Message(A_OKAY, state["id"], msg.arg0))
                state["buf"] += msg.data
                self._sync(conn, msg.arg0, state)
            elif msg.command == A_CLSE:
                syncs.pop(msg.arg0, None)

    def _sync(self, conn: socket.socket, host_id: int, state: dict) -> None:
        buf = state["buf"]
        while len(buf) >= 8:
            kind = bytes(buf[:4])
            (size,) = struct.unpack_from("<I", buf, 4)
            if kind in (b"DONE", b"QUIT"):
                del buf[:8]
                if kind == b"QUIT":
                    self._send(conn, Message(A_CLSE, state["id"], host_id))
                    return
                if self.sync_fail or state["path"].startswith(self.fail_dirs or ("\x00",)):
                    text = (self.sync_fail or "").encode()
                    reply = b"FAIL" + struct.pack("<I", len(text)) + text
                else:
                    self.files[state["path"]] = (state["mode"], bytes(state["data"]))
                    reply = b"OKAY" + struct.pack("<I", 0)
                self._send(conn, Message(A_WRTE, state["id"], host_id, reply))
                continue
            if len(buf) < 8 + size:
                return
            payload = bytes(buf[8:8 + size])
            del buf[:8 + size]
            if kind == b"SEND":
                path, mode = payload.decode().rsplit(",", 1)
                state["path"], state["mode"] = path, int(mode)
            elif kind == b"DATA":
                state["data"] += payload
