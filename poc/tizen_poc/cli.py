"""Linha de comando da Fase 0: ``python -m tizen_poc <comando>``."""

from __future__ import annotations

import argparse
import getpass
import json
import sys
import threading
from pathlib import Path

from . import discovery, installer, wgt
from .sdb import SdbClient, SdbError


def _trace(direction: str, msg) -> None:
    print(f"  {direction} {msg.describe()}", file=sys.stderr)


def cmd_discover(args: argparse.Namespace) -> int:
    cidr = args.subnet or f"{discovery.local_ipv4()}/24"
    print(f"Varrendo {cidr}…")
    tvs = discovery.scan(cidr, timeout=args.timeout)
    if not tvs:
        print("Nenhuma TV encontrada.")
        return 1
    for tv in tvs:
        ports = ", ".join(p for p, ok in (("SDB 26101", tv.sdb_open), ("ADB 5555", tv.adb_open)) if ok) or "nenhuma"
        print(f"{tv.ip}  {tv.platform}  {tv.name or '-'}  modelo={tv.model_name or tv.model or '-'}  ano={tv.year or '-'}")
        print(f"    DUID(api/v2)={tv.duid or '-'}  modo dev={tv.developer_mode}  IP dev={tv.developer_ip or '-'}  portas: {ports}")
    return 0


def cmd_info(args: argparse.Namespace) -> int:
    tv = discovery.fetch_samsung_info(args.ip)
    print("== GET :8001/api/v2/ ==")
    print(json.dumps(tv.__dict__, indent=2, ensure_ascii=False) if tv else "sem resposta")
    print("== SDB ==")
    try:
        with SdbClient(args.ip, trace=_trace if args.trace else None) as client:
            print(f"banner: {client.banner.raw if client.banner else ''}")
            print(f"versão: {client.device_version:#x}  maxdata: {client.max_payload}")
            for key, value in client.capability().items():
                print(f"  {key}: {value}")
            for command in ("0 getduid",):
                print(f"$ {command}")
                print("  " + client.shell(command, timeout=20).strip().replace("\n", "\n  "))
    except SdbError as exc:
        print(f"falha: {exc}")
        return 1
    return 0


def cmd_install(args: argparse.Namespace) -> int:
    if not Path(args.wgt).is_file():
        print(f"Arquivo não encontrado: {args.wgt}")
        return 1
    report = installer.install_wgt(
        args.ip,
        args.wgt,
        expected_sha256=args.sha256,
        install_id=args.install_id,
        launch=not args.no_launch,
        trace=_trace if args.trace else None,
        permit_profile=args.permit,
    )
    print(("OK: " if report.ok else "FALHA: ") + (report.message or report.stage))
    if args.json:
        print(json.dumps({**report.__dict__, "diagnosis": report.diagnosis and report.diagnosis.__dict__}, default=str, indent=2, ensure_ascii=False))
    return 0 if report.ok else 1


def cmd_permit(args: argparse.Namespace) -> int:
    if not Path(args.profile_xml).is_file():
        print(f"Arquivo não encontrado: {args.profile_xml}")
        return 1
    try:
        with SdbClient(args.ip) as client:
            installer.permit_install(client, args.profile_xml)
    except SdbError as exc:
        print(f"falha: {exc}")
        return 1
    print("OK: perfil de aparelho enviado.")
    return 0


def cmd_shell(args: argparse.Namespace) -> int:
    try:
        with SdbClient(args.ip, trace=_trace if args.trace else None) as client:
            print(client.shell(args.command, timeout=args.timeout))
    except SdbError as exc:
        print(f"falha: {exc}")
        return 1
    return 0


def cmd_make_test_wgt(args: argparse.Namespace) -> int:
    info = wgt.make_test_wgt(args.out, profile=args.profile, unpacked=args.unpacked)
    print(f"Criado {args.out} (não assinado, perfil {args.profile}): pacote {info.package_id}, app {info.app_id}")
    if args.unpacked:
        print(f"Arquivos soltos em {args.unpacked}")
    return 0


def cmd_wgt_info(args: argparse.Namespace) -> int:
    info = wgt.read_wgt(args.wgt)
    print(json.dumps({**info.__dict__, "sha256": wgt.sha256_file(args.wgt)}, indent=2, ensure_ascii=False))
    return 0


def cmd_proxy(args: argparse.Namespace) -> int:
    from . import proxy

    out = open(args.log, "a", encoding="utf-8") if args.log else sys.stdout
    print(f"Proxy em 127.0.0.1:{args.listen} → {args.tv}:26101. No PC: sdb connect 127.0.0.1:{args.listen}")
    try:
        proxy.serve(args.tv, 26101, args.listen, out, stop=threading.Event())
    except KeyboardInterrupt:
        pass
    finally:
        if out is not sys.stdout:
            out.close()
    return 0


def _password(args: argparse.Namespace, confirm: bool) -> str:
    if args.password_file:
        return Path(args.password_file).read_text(encoding="utf-8").strip()
    first = getpass.getpass("Senha dos .p12: ")
    if confirm and first != getpass.getpass("Repita a senha: "):
        raise SystemExit("as senhas não conferem")
    return first


def cmd_cert(args: argparse.Namespace) -> int:
    from cryptography.hazmat.primitives import serialization

    from . import samsung_cert as sc

    out = Path(args.out)
    first_run = not any((out / name).exists() for name in ("author.p12", "author-key.pem"))
    password = _password(args, confirm=first_run)

    def login() -> sc.SamsungAuth:
        print("Abrindo o login da Samsung no navegador…")
        return sc.login_with_browser()

    result = sc.build_profile(
        out,
        password,
        args.duid,
        login,
        author_name=args.author_name,
        privilege=args.privilege,
        ca_dir=Path(args.ca_dir) if args.ca_dir else None,
        author_ca=args.author_ca,
        distributor_ca=args.distributor_ca,
        allow_missing_ca=args.allow_missing_ca,
    )
    for kind, leaf in result.issued.items():
        print(f"{kind}: {leaf.subject.rfc4514_string()}")
        print(f"  emissor: {leaf.issuer.rfc4514_string()}")
        print(f"  validade: {leaf.not_valid_before_utc:%Y-%m-%d} a {leaf.not_valid_after_utc:%Y-%m-%d}")
        if kind == "distributor":
            print(f"  DUIDs: {', '.join(sc.certificate_duids(leaf)) or '(nenhum no SAN)'}")
    if result.distributor:
        (out / "distributor.pem").write_bytes(b"".join(c.public_bytes(serialization.Encoding.PEM) for c in result.distributor))
    if result.missing_ca and args.allow_missing_ca:
        print(
            f"\nPerfil EXPERIMENTAL em {out}: .p12 só com o certificado folha (sem a CA intermediária).\n"
            "Serve para testar se a TV aceita assim. Se a instalação falhar com install failed[118, -12],\n"
            "a CA é necessária: instale a Samsung Certificate Extension e rode cert sem --allow-missing-ca."
        )
        return 0
    if result.missing_ca:
        ca_dir = Path(args.ca_dir) if args.ca_dir else out / "ca"
        print("\nFaltam as CAs intermediárias da Samsung (certificados públicos):")
        for kind, issuer in result.missing_ca.items():
            print(f"  {kind}: {issuer}")
        print(
            f"Copie os arquivos vd_tizen_dev_*.cer/.crt da Samsung Certificate Extension para {ca_dir}\n"
            "e rode o mesmo comando de novo: as chaves e certificados já emitidos serão reaproveitados."
        )
        return 2
    print(f"\nPerfil completo em {out}: author.p12 e distributor.p12.")
    return 0


def cmd_find_ca(args: argparse.Namespace) -> int:
    from . import ca_search

    found = []
    for root in args.paths:
        print(f"Procurando em {root}…")
        for origin, cert in ca_search.scan(Path(root)):
            print(f"  {cert.subject.rfc4514_string()}  (até {cert.not_valid_after_utc:%Y-%m-%d})\n    {origin}")
            found.append(cert)
    if not found:
        print("Nenhuma CA da Samsung encontrada.")
        return 1
    if args.to:
        for path in ca_search.export(found, Path(args.to)):
            print(f"Copiado: {path}")
    return 0


def cmd_sign(args: argparse.Namespace) -> int:
    from .signer import check_digests, load_p12, sign_wgt

    profile = Path(args.profile) if args.profile else None
    author = Path(args.author_p12) if args.author_p12 else profile / "author.p12"
    distributor = Path(args.distributor_p12) if args.distributor_p12 else profile / "distributor.p12"
    for path, label in ((Path(args.source), "pacote de origem"), (author, "author.p12"), (distributor, "distributor.p12")):
        if not path.is_file():
            hint = " Rode o comando cert até aparecer 'Perfil'." if path.suffix == ".p12" else ""
            print(f"Arquivo não encontrado ({label}): {path}.{hint}")
            return 1
    password = _password(args, confirm=False)
    sign_wgt(
        args.source,
        args.target,
        load_p12(author.read_bytes(), password),
        load_p12(distributor.read_bytes(), password),
    )
    problems = check_digests(args.target)
    if problems:
        print("\n".join(problems))
        return 1
    print(f"Assinado: {args.target}  SHA-256 {wgt.sha256_file(args.target)}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="tizen_poc", description="Ferramentas da Fase 0 (Samsung Tizen).")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("discover", help="procura TVs na rede local")
    p.add_argument("--subnet", help="rede a varrer, ex.: 192.168.0.0/24 (padrão: a deste PC)")
    p.add_argument("--timeout", type=float, default=0.4)
    p.set_defaults(func=cmd_discover)

    p = sub.add_parser("info", help="lê /api/v2/, banner SDB, capability e DUID")
    p.add_argument("ip")
    p.add_argument("--trace", action="store_true", help="mostra cada mensagem SDB")
    p.set_defaults(func=cmd_info)

    p = sub.add_parser("install", help="envia, instala e abre um .wgt já assinado")
    p.add_argument("ip")
    p.add_argument("wgt")
    p.add_argument("--sha256", help="hash esperado do arquivo")
    p.add_argument("--install-id", choices=("package", "app"), default="package", help="ID passado ao vd_appinstall")
    p.add_argument("--no-launch", action="store_true")
    p.add_argument("--permit", metavar="DEVICE_PROFILE_XML", help="envia o device-profile.xml antes de instalar")
    p.add_argument("--trace", action="store_true")
    p.add_argument("--json", action="store_true")
    p.set_defaults(func=cmd_install)

    p = sub.add_parser("permit", help='"Permit to install": envia o device-profile.xml do perfil para a TV')
    p.add_argument("ip")
    p.add_argument("profile_xml", help="ex.: ..\\..\\perfil-teste\\device-profile.xml")
    p.set_defaults(func=cmd_permit)

    p = sub.add_parser("shell", help='executa um comando "0 ..." do sdbd (ex.: "0 applist")')
    p.add_argument("ip")
    p.add_argument("command")
    p.add_argument("--timeout", type=float, default=60)
    p.add_argument("--trace", action="store_true")
    p.set_defaults(func=cmd_shell)

    p = sub.add_parser("make-test-wgt", help="gera um app de teste mínimo (.wgt não assinado)")
    p.add_argument("out", nargs="?", default="app-teste.wgt")
    p.add_argument("--profile", default="tv-samsung", help='<tizen:profile name> (TVs 2016: "tv")')
    p.add_argument("--unpacked", help="também grava os arquivos soltos nesta pasta")
    p.set_defaults(func=cmd_make_test_wgt)

    p = sub.add_parser("wgt-info", help="mostra IDs, versão, assinaturas e SHA-256 de um .wgt")
    p.add_argument("wgt")
    p.set_defaults(func=cmd_wgt_info)

    p = sub.add_parser("proxy", help="registra o tráfego SDB do Tizen Studio com a TV")
    p.add_argument("--tv", required=True)
    p.add_argument("--listen", type=int, default=26199)
    p.add_argument("--log", help="arquivo de log (padrão: tela)")
    p.set_defaults(func=cmd_proxy)

    p = sub.add_parser("cert", help="Estratégia A: gera certificados com a conta Samsung")
    p.add_argument("--duid", action="append", required=True, help="DUID da TV (repita para várias)")
    p.add_argument("--out", required=True, help="pasta do perfil (fora do repositório)")
    p.add_argument("--author-name", default="InstaladorTV")
    p.add_argument("--privilege", choices=("public", "partner"), default="public")
    p.add_argument("--allow-missing-ca", action="store_true", help="experimento: monta os .p12 só com a folha")
    p.add_argument("--ca-dir", help="pasta com as CAs intermediárias da Samsung (padrão: <out>\\ca)")
    p.add_argument("--author-ca", help="CA intermediária de autor da Samsung, se a resposta não trouxer")
    p.add_argument("--distributor-ca", help="CA intermediária de distribuidor da Samsung, se a resposta não trouxer")
    p.add_argument("--password-file")
    p.set_defaults(func=cmd_cert)

    p = sub.add_parser("find-ca", help="procura as CAs da Samsung (também dentro de .jar) e copia para o perfil")
    p.add_argument("paths", nargs="+", help="pastas a varrer, ex.: C:\\tizen-studio")
    p.add_argument("--to", help="pasta de destino, ex.: ..\\..\\perfil-teste\\ca")
    p.set_defaults(func=cmd_find_ca)

    p = sub.add_parser("sign", help="reassina um .wgt com author.p12 + distributor.p12")
    p.add_argument("source")
    p.add_argument("target")
    p.add_argument("--profile", help="pasta gerada pelo comando cert")
    p.add_argument("--author-p12")
    p.add_argument("--distributor-p12")
    p.add_argument("--password-file")
    p.set_defaults(func=cmd_sign)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "sign" and not (args.profile or (args.author_p12 and args.distributor_p12)):
        raise SystemExit("informe --profile ou --author-p12 e --distributor-p12")
    return args.func(args)
