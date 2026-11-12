"""Regras de compatibilidade: plataforma, faixa de anos e faixa de versão do sistema."""

from __future__ import annotations

import re
from collections.abc import Iterable

from .models import Compatibility


def parse_version(value: str | None) -> tuple[int, ...] | None:
    if not value:
        return None
    parts = re.findall(r"\d+", value)
    return tuple(int(p) for p in parts) if parts else None


def guess_year(model: str | None) -> int | None:
    """O campo ``model`` do /api/v2/ da Samsung começa com o ano: ``18_KANTM2_QTV`` → 2018."""
    match = re.match(r"^(\d{2})_", model or "")
    if not match:
        return None
    year = 2000 + int(match.group(1))
    return year if 2014 <= year <= 2040 else None


def _version_ok(actual: tuple[int, ...] | None, low: str | None, high: str | None) -> bool:
    if actual is None:
        return True  # TV sem versão informada: o cliente confirma o modelo no app
    low_v, high_v = parse_version(low), parse_version(high)
    if low_v and actual < low_v:
        return False
    if high_v and actual[: len(high_v)] > high_v:
        return False
    return True


def rule_matches(rule: Compatibility, platform: str, year: int | None, os_version: str | None) -> bool:
    if rule.platform != platform:
        return False
    if year is not None:
        if rule.min_year is not None and year < rule.min_year:
            return False
        if rule.max_year is not None and year > rule.max_year:
            return False
    return _version_ok(parse_version(os_version), rule.min_os, rule.max_os)


def is_compatible(rules: Iterable[Compatibility], platform: str, year: int | None, os_version: str | None) -> bool:
    rules = list(rules)
    if not rules:
        return True
    return any(rule_matches(rule, platform, year, os_version) for rule in rules)


def describe(rule: Compatibility) -> str:
    parts = []
    if rule.min_year or rule.max_year:
        parts.append(f"TVs {rule.min_year or '…'}–{rule.max_year or '…'}")
    if rule.min_os or rule.max_os:
        label = "Tizen" if rule.platform == "tizen" else "Sistema"
        parts.append(f"{label} {rule.min_os or '…'}–{rule.max_os or '…'}")
    return ", ".join(parts) or "toda a plataforma"
