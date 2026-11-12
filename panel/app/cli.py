"""``python -m app.cli create-admin <usuário>``: cria ou redefine um admin."""

from __future__ import annotations

import argparse
import getpass

from sqlalchemy import select
from sqlalchemy.orm import Session

from .config import load_settings
from .database import Base, make_engine
from .models import AdminUser
from .security import hash_password


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="app.cli")
    sub = parser.add_subparsers(dest="command", required=True)
    create = sub.add_parser("create-admin", help="cria um admin ou troca a senha de um existente")
    create.add_argument("username")
    args = parser.parse_args(argv)

    password = getpass.getpass("Senha (10+ caracteres): ")
    if len(password) < 10 or password != getpass.getpass("Repita: "):
        print("Senha curta ou diferente da confirmação.")
        return 1
    settings = load_settings()
    engine = make_engine(settings.database_url)
    Base.metadata.create_all(engine)
    with Session(engine) as db:
        admin = db.scalar(select(AdminUser).where(AdminUser.username == args.username))
        if admin is None:
            db.add(AdminUser(username=args.username, password_hash=hash_password(password)))
            print(f"Admin {args.username} criado.")
        else:
            admin.password_hash = hash_password(password)
            print(f"Senha de {args.username} redefinida.")
        db.commit()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
