"""Descoberta de TVs na rede local: Samsung (8001 e SDB 26101) e Android TV (ADB 5555)."""

from __future__ import annotations

import ipaddress
import json
import re
import socket
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

SAMSUNG_INFO_PORT = 8001
SDB_PORT = 26101
ADB_PORT = 5555

# Versão de Tizen de fábrica por ano de modelo. A TV pode ter sido atualizada:
# a versão real vem do ``platform_version`` do SDB.
TIZEN_BY_YEAR = {
    2015: "2.3",
    2016: "2.4",
    2017: "3.0",
    2018: "4.0",
    2019: "5.0",
    2020: "5.5",
    2021: "6.0",
    2022: "6.5",
    2023: "7.0",
    2024: "8.0",
    2025: "9.0",
}


@dataclass(frozen=True)
class TvInfo:
    ip: str
    platform: str
    name: str = ""
    model: str = ""
    model_name: str = ""
    year: int | None = None
    firmware: str = ""
    os: str = ""
    duid: str = ""
    developer_mode: bool | None = None
    developer_ip: str = ""
    sdb_open: bool = False
    adb_open: bool = False

    @property
    def factory_tizen(self) -> str:
        return TIZEN_BY_YEAR.get(self.year or 0, "")


def guess_year(model: str) -> int | None:
    """O campo ``model`` do /api/v2/ começa com o ano: ``18_KANTM2_QTV`` → 2018."""
    match = re.match(r"^(\d{2})_", model or "")
    if not match:
        return None
    year = 2000 + int(match.group(1))
    return year if 2014 <= year <= 2040 else None


def parse_samsung_info(ip: str, payload: dict) -> TvInfo:
    device = payload.get("device") or {}
    mode = device.get("developerMode")
    model = str(device.get("model", ""))
    return TvInfo(
        ip=ip,
        platform="tizen",
        name=str(device.get("name") or payload.get("name") or ""),
        model=model,
        model_name=str(device.get("modelName", "")),
        year=guess_year(model),
        firmware=str(device.get("firmwareVersion", "")),
        os=str(device.get("OS", "")),
        duid=str(device.get("duid", "")),
        developer_mode=None if mode is None else str(mode) == "1",
        developer_ip=str(device.get("developerIP", "")),
    )


def port_open(ip: str, port: int, timeout: float) -> bool:
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            return True
    except OSError:
        return False


def fetch_samsung_info(ip: str, timeout: float = 2.0) -> TvInfo | None:
    url = f"http://{ip}:{SAMSUNG_INFO_PORT}/api/v2/"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            payload = json.loads(resp.read(256 * 1024).decode("utf-8", errors="replace"))
    except (OSError, ValueError):
        return None
    if not isinstance(payload, dict):
        return None
    return parse_samsung_info(ip, payload)


def local_ipv4() -> str:
    """IP da interface que sai para a rede local (UDP connect não envia pacotes)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.connect(("10.255.255.255", 1))
        return sock.getsockname()[0]


def subnet_hosts(cidr: str) -> list[str]:
    network = ipaddress.ip_network(cidr, strict=False)
    if network.num_addresses > 1024:
        raise ValueError("rede grande demais para varrer; use no máximo /22")
    return [str(host) for host in network.hosts()]


def probe(ip: str, timeout: float = 0.4) -> TvInfo | None:
    info_open = port_open(ip, SAMSUNG_INFO_PORT, timeout)
    sdb_open = port_open(ip, SDB_PORT, timeout)
    adb_open = port_open(ip, ADB_PORT, timeout)
    if info_open or sdb_open:
        info = fetch_samsung_info(ip) if info_open else None
        info = info or TvInfo(ip=ip, platform="tizen")
        return TvInfo(**{**info.__dict__, "sdb_open": sdb_open, "adb_open": adb_open})
    if adb_open:
        return TvInfo(ip=ip, platform="android_tv", adb_open=True)
    return None


def scan(cidr: str | None = None, timeout: float = 0.4, workers: int = 64) -> list[TvInfo]:
    cidr = cidr or f"{local_ipv4()}/24"
    hosts = subnet_hosts(cidr)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        found = [tv for tv in pool.map(lambda ip: probe(ip, timeout), hosts) if tv]
    return sorted(found, key=lambda tv: ipaddress.ip_address(tv.ip))
