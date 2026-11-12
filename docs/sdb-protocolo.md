# Protocolo SDB (Samsung Tizen) — o que o instalador usa

Status de cada item: **[confirmado-captura]** = visto em tráfego real publicado por terceiros;
**[código-terceiros]** = usado por instaladores open source que funcionam; **[validar]** = ainda
precisa ser confirmado na nossa TV de teste (Fase 0, `docs/fase0-relatorio.md`).

Fontes: captura do sdb 4.2.36 (Tizen Studio 10) contra TV 2024/Tizen 9 publicada em
[kromatv/react-native-tizen PR #5](https://github.com/kromatv/react-native-tizen/pull/5)
(`docs/reference/sdb-protocol.md`); código e testes do
[Apps2Samsung](https://github.com/Apps2Samsung/Apps2Samsung); comandos do
[gist laat](https://gist.github.com/laat/2b7107298593dca650062b7678279386);
[free-my-tv](https://github.com/simbasjungles/free-my-tv).

## Confirmado em TV real (2026-11-11)

Samsung **UN49KU6300** (2016, `model=16_JAZZL_UHD_BASIC`, `platform_version 2.4.0`, `sdbd_version 2.2.31`),
com o cliente deste repositório, sem Tizen Studio:

- Handshake: host `CNXN(0x00100000, 0x40000, "host::")` aceito; TV responde `CNXN(0x02000000, 0x1000, "device::UN49KU6300::0")` — **maxdata 4096** nesta geração.
- `capability:` com prefixo uint16 LE. **Não traz `sdk_toolpath`**. Traz `secure_protocol:enabled`, `filesync_support:push`, `can_launch:tv-samsung-public_-_tv-samsung-partner`. Mesmo com `secure_protocol:enabled`, `capability:` e `shell:` funcionaram sem criptografia.
- `0 getduid` → DUID de 13 caracteres alfanuméricos maiúsculos (a linha vem repetida duas vezes). O `duid` do `/api/v2/` (`uuid:…`) **é outro valor**: o DUID do certificado vem do SDB.
- Push: **`/opt/usr/apps/tmp/` aceito**; `/home/owner/share/tmp/sdk_tools/tmp/` **recusado** (FAIL). O instalador tenta `sdk_toolpath/tmp` (se houver), depois `/opt/usr/apps/tmp`, depois a pasta do sdk_tools.
- `0 vd_appinstall <package ID> /opt/usr/apps/tmp/<pkg>.wgt`: **aceito com o package ID**. Saída em
  Tizen 2.4: `install <pkg>` / `package_path …` / `was_install_app return WAS_TRUE` /
  `app_id[<pkg>] install start` / `install failed[118]` ou sucesso / `spend time for wascmd is [N]ms` / `cmd_ret:0`.
- Pacote assinado **sem a CA intermediária** no KeyInfo → `install failed[118]` **sem subcódigo**
  (Tizen 2.4 não detalha o motivo).
- **Permit to install** (push do `device-profile.xml` para `/home/developer/device-profile.xml`, modo 0644)
  antes do `vd_appinstall`: com ele a instalação terminou em `app_id[<pkg>] install completed`.
- Abertura: `shell:0 execute <appId>` é **recusado** (CLSE em resposta ao OPEN);
  `0 was_execute <appId>` → `launch app …` / `app_id[<pkg>] launch start` / `app_id[<pkg>] launched`.

## Transporte

- TCP na porta **26101** da TV, só na rede local. [confirmado-captura]
- A TV só aceita conexão do IP configurado no Modo Desenvolvedor. [código-terceiros]
- **Uma conexão de host por vez**: com o Tizen Studio/`sdb` do PC conectado, o celular é recusado. [confirmado-captura]
- Sem AUTH/RSA (diferente do ADB moderno). [confirmado-captura]

## Mensagem

Igual ao ADB: cabeçalho de 24 bytes, seis `uint32` little-endian, seguido do payload.

| Campo | Valor |
|---|---|
| command | `CNXN` 0x4E584E43, `OPEN` 0x4E45504F, `OKAY` 0x59414B4F, `WRTE` 0x45545257, `CLSE` 0x45534C43 |
| arg0, arg1 | dependem do comando (ids locais/remotos) |
| data_length | tamanho do payload |
| data_check | soma dos bytes do payload mod 2³² — **precisa ser calculada** [confirmado-captura] |
| magic | `command ^ 0xFFFFFFFF` |

## Handshake

```
PC → TV  CNXN(version=0x00100000, maxdata=0x40000, "host::\0")
TV → PC  CNXN(version=0x02000000, maxdata=0x3ff9c, "device::<modelo>::0\0")
```

[confirmado-captura]. O host usa `min(maxdata da TV, 0x40000)` como tamanho máximo de cada WRTE.
TVs antigas (2016/Tizen 2.4) podem responder maxdata 4096 [validar]; o cliente já se adapta.

## Canais (streams)

```
PC → TV  OPEN(local_id, 0, "<serviço>\0")
TV → PC  OKAY(remote_id, local_id)        # aceito
         CLSE(0, local_id)                # recusado (serviço desconhecido)
TV → PC  WRTE(remote_id, local_id, dados) # o host responde OKAY(local_id, remote_id)
PC → TV  WRTE(local_id, remote_id, dados) # o host espera OKAY antes do próximo WRTE
TV → PC  CLSE(remote_id, local_id)        # fim; a TV não espera o OKAY do último WRTE
```

## Serviços usados

| Serviço | Uso | Status |
|---|---|---|
| `capability:` | linhas `chave:valor` com prefixo uint16 de tamanho; traz `platform_version`, `sdk_toolpath`, `cpu_arch`, `profile_name`, `vendor_name`, `sdbd_version` | [confirmado-captura] |
| `sync:` | envio de arquivo (abaixo) | [confirmado-captura] |
| `shell:0 vd_appinstall <id> <arquivo>` | instala o .wgt | [confirmado-captura] |
| `shell:0 execute <appId>` | abre o app (Tizen recente) | [confirmado-captura] |
| `shell:0 was_execute <appId>` | abre o app (Tizen 2.4/3.0) | confirmado (Tizen 2.4; `execute` é recusado) |
| `sync:` para `/home/developer/device-profile.xml` | "Permit to install" (perfil de aparelho da Samsung) | confirmado: obrigatório |
| `shell:0 vd_appuninstall <pkgId>` | desinstala | [código-terceiros] |
| `shell:0 applist` | lista apps web instalados | [código-terceiros] |
| `shell:0 getduid` | DUID pelo SDB | confirmado (Tizen 2.4) |
| `shell:0 debug <appId>` | abre com Web Inspector | [código-terceiros] (fora do escopo) |

O `0` na frente indica um comando interno do sdbd, não um shell Unix. IDs aceitos: `[A-Za-z0-9_.]+`
— o instalador rejeita qualquer outro valor (também evita injeção de comando).

### Qual ID vai no `vd_appinstall`? (confirmado: package ID)

A captura publicada usa o **package ID** (`AbCdEf1234`); o gist usa o **app ID**
(`AbCdEf1234.Nome`). As duas ferramentas (Python e Android) aceitam as duas formas
(`--install-id package|app` / seletor no app) para o teste da Fase 0.

## Envio de arquivo (sync)

Dentro do canal `sync:`, pacotes `ID(4 bytes ASCII) + uint32 LE`:

```
SEND <len> "<caminho>,<modo>"     # modo 493 = 0755
DATA <len> <até 64 KiB>           # repetido
DONE <mtime>                      # sem payload
← OKAY 0      ou     ← FAIL <len> <mensagem>
QUIT 0
```

Destino do .wgt: `<sdk_toolpath>/tmp/<arquivo>.wgt`, com `sdk_toolpath` vindo do `capability:`
(típico: `/home/owner/share/tmp/sdk_tools`). [confirmado-captura] — bate com a hipótese inicial.
TVs de varejo não permitem `pull`. [código-terceiros]

## Saídas da instalação

| Saída | Significado |
|---|---|
| `installing[100]` / `install completed` | sucesso |
| `install failed[118]` | conflito com versão instalada |
| `install failed[118, -12]` / `[118012]` | certificado: DUID ausente, expirado, ainda não válido, formato inválido, ou "same id and different certificate" |
| `install failed[118, -4]` | versão de API/privilégio não permitido |
| `install failed[118, -19]` / `[118019]` | erro ao ler `config.xml` |
| `install failed[118, -22]` | package ID bloqueado |
| `download failed[116]` | sem espaço |

Implementado em `poc/tizen_poc/install_output.py` e `android/.../tizen/InstallOutput.kt`.

## Própria captura (Fase 0)

`python -m tizen_poc proxy --tv <IP-da-TV> --log captura.log`, depois no PC
`sdb connect 127.0.0.1:26199` e instale qualquer app pelo Tizen Studio. O proxy registra cada
mensagem decodificada (os blocos DATA aparecem só como tamanho). Compare com esta página e
atualize os itens [validar].
