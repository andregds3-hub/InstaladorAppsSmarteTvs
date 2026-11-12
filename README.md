# Instalador de apps para Smart TV

Sistema para instalar **apps próprios do admin** (com direito de distribuição) em TVs Samsung Tizen
(e, depois, Android TV) a partir de um celular Android na mesma rede Wi-Fi, sem o Tizen Studio.

| Pasta | O quê |
|---|---|
| `poc/` | Fase 0 — ferramenta Python para validar no PC: descoberta, SDB, instalação, proxy de captura, certificados Samsung (Estratégia A) e assinatura `.wgt` |
| `android/` | Fase 0 — app Android mínimo (Kotlin/Compose): encontra a TV, testa o SDB, instala um `.wgt` assinado e abre |
| `panel/` | Fase 1 — painel admin + API (FastAPI, SQLAlchemy, Jinja2/Bootstrap) |
| `docs/` | Protocolo SDB, fluxo de certificados e roteiro/relatório da Fase 0 |
| `docker-compose.yml`, `deploy/` | Deploy do painel na VPS com HTTPS (Caddy) |

## Status

| Fase | Situação |
|---|---|
| 0 — Prova de conceito | Código pronto. **Teste em TV real pendente**: siga `docs/fase0-relatorio.md` e preencha o relatório. Se a geração de certificado falhar, pare antes da Fase 2. |
| 1 — Painel + API | Código e testes prontos. |
| 2 — App Android completo | Não iniciada (depende do relatório da Fase 0). |
| 3 — Android TV / webOS | Não iniciada. `android_tv` e `webos` já existem como plataformas no painel e na API. |

## Fase 0 — ferramenta Python (`poc/`)

Requer Python 3.11+.

```
cd poc
python -m pip install -r requirements.txt          # requirements-dev.txt para os testes
python -m tizen_poc discover                        # TVs na /24 deste PC
python -m tizen_poc info 192.168.0.50 --trace       # /api/v2/, banner SDB, capability, DUID
python -m tizen_poc install 192.168.0.50 app.wgt    # push + vd_appinstall + abrir
python -m tizen_poc proxy --tv 192.168.0.50 --log captura.log   # captura o Tizen Studio
python -m tizen_poc cert --duid <DUID> --out ../../perfil       # Estratégia A (conta Samsung)
python -m tizen_poc sign app.wgt app-assinado.wgt --profile ../../perfil
python -m tizen_poc wgt-info app.wgt
```

O perfil de certificados (`.p12`, senha, respostas da Samsung) fica **fora do repositório**; o
`.gitignore` bloqueia `*.p12`, `*.pem` e `perfil*/` por segurança.

## Fase 0 — app Android (`android/`)

Abra a pasta `android/` no Android Studio (Ladybug ou mais novo, JDK 17). O repositório traz só o
`gradle-wrapper.properties`; o Android Studio baixa o Gradle na primeira sincronização (ou rode
`gradle wrapper` uma vez para gerar `gradlew`). minSdk 24, targetSdk 35.

Testes JVM (sem TV): `./gradlew :app:testDebugUnitTest`.

## Fase 1 — painel e API (`panel/`)

### Variáveis de ambiente

Crie um arquivo `.env` na raiz (ele está no `.gitignore`). Gere segredos com
`python -c "import secrets; print(secrets.token_urlsafe(48))"`.

| Variável | Obrigatória | Uso |
|---|---|---|
| `PANEL_DOMAIN` | sim (compose) | domínio do painel; o Caddy emite o certificado HTTPS |
| `PANEL_SECRET_KEY` | sim | assina o cookie de sessão (32+ caracteres) |
| `PANEL_APP_KEY` | sim | chave que o app Android envia em `X-App-Key` (16+ caracteres) |
| `PANEL_ADMIN_USER`, `PANEL_ADMIN_PASSWORD` | na 1ª vez | cria o primeiro admin se não existir nenhum |
| `PANEL_DATABASE_URL` | não | padrão SQLite em `/data/panel.db`; PostgreSQL: `postgresql+psycopg://user:senha@db:5432/panel` |
| `PANEL_MAX_UPLOAD_MB` | não | padrão 200 |
| `PANEL_TOKEN_DAYS` | não | validade do token do app, padrão 30 |
| `PANEL_API_RATE`, `PANEL_AUTH_RATE` | não | requisições/minuto por IP (padrão 120 e 10) |
| `PANEL_REQUIRE_HTTPS`, `PANEL_COOKIE_SECURE` | não | `false` só em desenvolvimento local |

### Deploy na VPS

```
# DNS de PANEL_DOMAIN apontando para a VPS, portas 80/443 abertas
docker compose up -d --build
docker compose exec panel python -m app.cli create-admin admin   # ou use PANEL_ADMIN_*
```

Arquivos enviados e o banco ficam no volume `panel-data` (`/data`). Faça backup dele.

### Desenvolvimento local

```
cd panel
python -m pip install -r requirements-dev.txt
# PowerShell: $env:PANEL_SECRET_KEY="..."; $env:PANEL_APP_KEY="..."; $env:PANEL_REQUIRE_HTTPS="false"; $env:PANEL_COOKIE_SECURE="false"
uvicorn app.asgi:app --reload
python -m pytest
```

### API (`/api/v1`, só HTTPS)

| Método e rota | Uso |
|---|---|
| `POST /auth` | cabeçalho `X-App-Key`; corpo `{"access_code": "..."}` quando "Exigir código de acesso" está ligado. Devolve `{"token", "expires_at"}` |
| `GET /apps?platform=tizen&model=18_KANTM2_QTV&year=&os_version=` | apps ativos com versão atual compatível (ano deduzido do `model` da Samsung quando possível) |
| `GET /apps/{id}/download` | arquivo da versão atual; cabeçalhos `X-Content-SHA256` e `X-App-Version` |
| `POST /devices` | registra/atualiza a TV (casa por plataforma + DUID; sem DUID, por modelo + IP) |
| `POST /installs` | resultado da instalação (`success`/`failure`, etapa, código e mensagem da TV) |

Todas exceto `/auth` exigem `Authorization: Bearer <token>`. O app **precisa** conferir o SHA-256
do arquivo baixado antes de instalar.

## Segurança

- Conexão com a TV só na rede local; nunca pedir para abrir portas no roteador nem expor SDB/ADB.
- Senhas de admin com bcrypt (truncadas a 72 bytes), cookie de sessão `Secure`/`HttpOnly`/`SameSite=Lax`,
  CSRF em todos os formulários, rate limit no login e na API, HTTPS obrigatório (HSTS).
- Tokens da API e códigos de acesso guardados só como hash; o código é mostrado uma vez.
- Segredos só em variáveis de ambiente/volume; nada de token, senha ou chave nos logs.
- IDs de pacote validados (`[A-Za-z0-9_.]+`) antes de irem para comandos SDB.

## Limitações conhecidas (mostrar ao usuário no app)

- O Modo Desenvolvedor precisa ser ativado manualmente na TV.
- Se o IP do celular mudar, é preciso atualizar o IP no Modo Desenvolvedor.
- Atualização de firmware ou reset da TV pode desativar o Modo Desenvolvedor e exigir reinstalação.
- Os certificados expiram e têm limite de aparelhos.
- A TV aceita uma conexão SDB por vez: feche o Tizen Studio no PC antes de usar o celular.
