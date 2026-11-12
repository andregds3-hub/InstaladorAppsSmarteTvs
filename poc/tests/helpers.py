from __future__ import annotations

import zipfile
from pathlib import Path

CONFIG_XML = b"""<?xml version="1.0" encoding="UTF-8"?>
<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets"
        id="http://example.com/player" version="1.2.3" viewmodes="maximized">
    <tizen:application id="AbCdEf1234.Player" package="AbCdEf1234" required_version="2.4"/>
    <name>Player</name>
</widget>
"""


def make_wgt(path: Path, signed: bool = False, extra: dict[str, bytes] | None = None, config: bytes = CONFIG_XML) -> Path:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("config.xml", config)
        zf.writestr("index.html", b"<html><body>ok</body></html>")
        zf.writestr("js/", b"")
        zf.writestr("js/main.js", b"console.log('ok');\n")
        for name, data in (extra or {}).items():
            zf.writestr(name, data)
        if signed:
            zf.writestr("author-signature.xml", b"<Signature/>")
            zf.writestr("signature1.xml", b"<Signature/>")
    return path
