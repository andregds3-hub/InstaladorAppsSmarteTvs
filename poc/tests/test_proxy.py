import io
import socket
import threading

from fake_sdbd import FakeSdbd
from tizen_poc import proxy
from tizen_poc.sdb import A_OKAY, A_WRTE, Message, SdbClient


def test_decoder_handles_split_and_merged_chunks():
    stream = Message(A_WRTE, 1, 2, b"hello").pack() + Message(A_OKAY, 2, 1).pack()
    decoder = proxy.FrameDecoder()
    messages = []
    for i in range(0, len(stream), 7):
        messages += decoder.feed(stream[i:i + 7])
    assert [m.data for m in messages] == [b"hello", b""]


def test_sync_data_is_summarised():
    msg = Message(A_WRTE, 1, 2, b"DATA" + (5).to_bytes(4, "little") + b"12345")
    assert "sync DATA 5 bytes" in proxy.describe(msg)


def test_proxy_logs_traffic():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    out = io.StringIO()
    ready, stop = threading.Event(), threading.Event()
    with FakeSdbd(shell_replies={"0 applist": "apps"}) as tv:
        thread = threading.Thread(target=proxy.serve, args=("127.0.0.1", tv.port, port, out), kwargs={"ready": ready, "stop": stop}, daemon=True)
        thread.start()
        assert ready.wait(5)
        with SdbClient("127.0.0.1", port=port) as client:
            assert client.shell("0 applist") == "apps"
        stop.set()
        thread.join(5)
    log = out.getvalue()
    assert "PC->TV CNXN" in log
    assert "TV->PC CNXN" in log
    assert "shell:0 applist" in log
