import struct

import pytest

from fake_sdbd import FakeSdbd
from tizen_poc.sdb import (
    A_CNXN,
    A_WRTE,
    HOST_BANNER,
    HOST_MAXDATA,
    HOST_VERSION,
    Message,
    SdbClient,
    SdbError,
    SdbServiceRefused,
    checksum,
    parse_banner,
    parse_capability,
    unpack_header,
)


def test_message_header_layout():
    msg = Message(A_WRTE, 1, 2, b"abc")
    packed = msg.pack()
    assert len(packed) == 24 + 3
    command, arg0, arg1, length, crc, magic = struct.unpack("<6I", packed[:24])
    assert packed[:4] == b"WRTE"
    assert (arg0, arg1, length) == (1, 2, 3)
    assert crc == checksum(b"abc") == 0x61 + 0x62 + 0x63
    assert magic == command ^ 0xFFFFFFFF


def test_bad_magic_is_rejected():
    raw = bytearray(Message(A_CNXN, 0, 0).pack())
    raw[20] ^= 0xFF
    with pytest.raises(SdbError):
        unpack_header(bytes(raw))


def test_parse_banner_tv_format():
    banner = parse_banner(b"device::QE55Q60::0\x00")
    assert banner.kind == "device"
    assert banner.model == "QE55Q60"


def test_parse_banner_adb_style_properties():
    banner = parse_banner(b"device::ro.product.name=x;ro.product.model=y;\x00")
    assert banner.model == ""
    assert banner.properties == {"ro.product.name": "x", "ro.product.model": "y"}


@pytest.mark.parametrize(
    "prefix",
    [lambda body: struct.pack("<H", len(body)), lambda body: struct.pack(">H", len(body)), lambda body: f"{len(body):04x}".encode(), lambda body: b""],
)
def test_parse_capability_variants(prefix):
    body = b"cpu_arch:armv7\nplatform_version:6.5\nsdk_toolpath:/home/owner/share/tmp/sdk_tools\n"
    caps = parse_capability(prefix(body) + body)
    assert caps["platform_version"] == "6.5"
    assert caps["sdk_toolpath"] == "/home/owner/share/tmp/sdk_tools"


def test_connect_handshake_and_capability():
    with FakeSdbd() as tv, SdbClient("127.0.0.1", port=tv.port) as client:
        assert client.banner.model == "QE55Q60"
        assert client.device_version == 0x02000000
        assert client.max_payload == min(0x3FF9C, HOST_MAXDATA) == 0x3FF9C
        assert client.capability()["cpu_arch"] == "armv7"
    assert tv.host_cnxn.arg0 == HOST_VERSION
    assert tv.host_cnxn.data == HOST_BANNER


def test_device_maxdata_limits_writes(tmp_path):
    source = tmp_path / "f.bin"
    source.write_bytes(bytes(range(256)) * 100)
    with FakeSdbd(maxdata=4096) as tv, SdbClient("127.0.0.1", port=tv.port) as client:
        client.push(str(source), "/tmp/f.bin")
    assert tv.max_wrte <= 4096
    assert tv.files["/tmp/f.bin"][1] == source.read_bytes()


def test_shell_collects_all_output():
    replies = {"0 applist": "line one\nline two\n"}
    with FakeSdbd(shell_replies=replies) as tv, SdbClient("127.0.0.1", port=tv.port) as client:
        assert client.shell("0 applist") == "line one\nline two\n"
        assert client.shell("0 other") == ""
    assert tv.commands == ["0 applist", "0 other"]


def test_refused_service():
    with FakeSdbd(refuse={"shell:0 forbidden"}) as tv, SdbClient("127.0.0.1", port=tv.port) as client:
        with pytest.raises(SdbServiceRefused):
            client.shell("0 forbidden")
        assert client.shell("0 ok") == ""


def test_push_large_file_with_progress(tmp_path):
    source = tmp_path / "app.wgt"
    source.write_bytes(b"x" * (300 * 1024 + 7))
    seen = []
    with FakeSdbd() as tv, SdbClient("127.0.0.1", port=tv.port) as client:
        client.push(str(source), "/home/owner/share/tmp/sdk_tools/tmp/app.wgt", progress=lambda s, t: seen.append((s, t)))
    mode, data = tv.files["/home/owner/share/tmp/sdk_tools/tmp/app.wgt"]
    assert mode == 0o755
    assert data == source.read_bytes()
    assert seen[-1] == (len(data), len(data))


def test_push_failure_message(tmp_path):
    source = tmp_path / "app.wgt"
    source.write_bytes(b"abc")
    with FakeSdbd(sync_fail="No space left on device") as tv, SdbClient("127.0.0.1", port=tv.port) as client:
        with pytest.raises(SdbError, match="No space left"):
            client.push(str(source), "/tmp/app.wgt")


def test_connection_refused_is_sdb_error():
    with pytest.raises(SdbError):
        SdbClient("127.0.0.1", port=1, timeout=1).connect()
