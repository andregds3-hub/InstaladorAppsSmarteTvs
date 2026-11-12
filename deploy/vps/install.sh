#!/usr/bin/env bash
# Instala ou atualiza o painel do Instalador TV numa VPS Ubuntu, em Docker, na porta 92.
#   sudo bash install.sh              # porta 92
#   sudo PORT=9092 bash install.sh    # outra porta, se a 92 estiver ocupada
# Na primeira vez gera deploy/vps/.env com senhas aleatórias (fica só na VPS) e mostra-as uma vez.
set -euo pipefail

PORT="${PORT:-92}"
REPO="${REPO:-https://github.com/andregds3-hub/InstaladorAppsSmarteTvs.git}"
DIR="${DIR:-/opt/instaladortv}"
CONTAINER=instaladortv-panel

[ "$(id -u)" -eq 0 ] || { echo "Rode como root: sudo bash install.sh"; exit 1; }

if ! command -v git >/dev/null || ! command -v curl >/dev/null || ! command -v openssl >/dev/null; then
  apt-get update -qq
  apt-get install -y -qq git curl openssl ca-certificates
fi
if ! command -v docker >/dev/null; then
  echo ">> Instalando o Docker..."
  curl -fsSL https://get.docker.com | sh
fi
if ! docker compose version >/dev/null 2>&1; then
  apt-get install -y -qq docker-compose-plugin
fi

if [ -d "$DIR/.git" ]; then
  echo ">> Atualizando o código em $DIR"
  git -C "$DIR" pull --ff-only
else
  echo ">> Baixando o código para $DIR"
  git clone --depth 1 "$REPO" "$DIR"
fi
cd "$DIR/deploy/vps"

if [ -f .env ]; then
  PORT="$(grep '^PANEL_PORT=' .env | cut -d= -f2)"
fi

# A porta só precisa estar livre se o painel ainda não estiver rodando nela.
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  if ss -ltnH "( sport = :$PORT )" | grep -q .; then
    echo "!! A porta $PORT já está em uso por:"
    ss -ltnp "( sport = :$PORT )"
    echo "!! Rode de novo escolhendo outra porta, por exemplo: sudo PORT=9092 bash install.sh"
    exit 1
  fi
fi

NEW=0
if [ ! -f .env ]; then
  ADMIN_PASSWORD="$(openssl rand -base64 18 | tr -d '/+=' | cut -c1-20)"
  APP_KEY="$(openssl rand -hex 24)"
  umask 077
  cat > .env <<EOF
PANEL_SECRET_KEY=$(openssl rand -hex 32)
PANEL_APP_KEY=$APP_KEY
PANEL_ADMIN_USER=admin
PANEL_ADMIN_PASSWORD=$ADMIN_PASSWORD
PANEL_PORT=$PORT
EOF
  NEW=1
fi

echo ">> Construindo e subindo o contêiner"
docker compose up -d --build

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow "$PORT/tcp" >/dev/null
  echo ">> Firewall (ufw): porta $PORT liberada"
fi

for _ in $(seq 1 30); do
  curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1 && break
  sleep 1
done
if ! curl -fsS "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; then
  echo "!! O painel não respondeu. Logs:"
  docker logs --tail 50 "$CONTAINER"
  exit 1
fi

IP="$(hostname -I | awk '{print $1}')"
echo
echo "=============================================================="
echo " Painel no ar: http://$IP:$PORT/admin"
if [ "$NEW" = 1 ]; then
  echo " Usuário: admin"
  echo " Senha:   $ADMIN_PASSWORD"
  echo " Chave do app (Configurações do app Android): $APP_KEY"
  echo " Guardados em $DIR/deploy/vps/.env (só root lê). Anote agora."
fi
echo " Atenção: modo HTTP (sem HTTPS) - só para teste."
echo "=============================================================="
