# Fase 0 — roteiro de teste e relatório

> **Status (2026-11-12): CONCLUÍDA COM SUCESSO, no PC e no celular.** Certificados gerados com a conta
> Samsung fora do Tizen Studio, pacote assinado pelo `signer.py`, enviado, instalado e aberto na TV por
> SDB próprio, sem o Tizen Studio — também pelo app Android (etapa D). Falta só uma segunda TV (mais nova).

## Etapa D — app Android (2026-11-12)

Celular Samsung SM-S721B, Android 17, APK de debug compilado no GitHub Actions (21 testes JVM ok).
Fluxo no app: busca da /24 encontrou a TV → "Testar conexão SDB" (banner, capability, `0 getduid`) →
`.wgt` assinado + `device-profile.xml` escolhidos no celular → Permit → push para `/opt/usr/apps/tmp/` →
`install completed` → `0 execute` recusado → `0 was_execute` → `launched`.

Aprendizados para a interface final:

- Com o IP de desenvolvedor da TV apontando para outro aparelho, a TV derruba a conexão ("TV fechou a
  conexão" / "Connection reset"). O app deve comparar `developerIP` do `/api/v2/` com o IP do celular e
  mostrar "esta TV está liberada para X; troque para Y e reinicie a TV" **antes** de tentar o SDB.
- O campo livre "IP da TV" confundiu (foi digitado o IP do PC). Na interface final, a TV vem da busca;
  IP manual só como opção avançada.
- Escolher arquivos no celular é trabalhoso: na versão final o `.wgt` vem do painel e o perfil
  (certificados + `device-profile.xml`) é gerado/guardado pelo próprio app.
- O DUID exibido deve ser o do SDB (`0 getduid`), não o `uuid:` do `/api/v2/`.

## Resultado

TV: Samsung **UN49KU6300** (2016, `16_JAZZL_UHD_BASIC`, Tizen **2.4.0**, sdbd 2.2.31), na rede local do PC.

| Etapa | Resultado |
|---|---|
| Descoberta | `/api/v2/` ok; `developerMode=1`, `developerIP` = IP do PC; porta 26101 aberta |
| DUID | `0 getduid` → 13 caracteres alfanuméricos maiúsculos. O `duid` do `/api/v2/` (`uuid:…`) **não** é o DUID do certificado |
| Certificado de autor | emitido pela `/apis/v3/authors` (CA "Samsung VD Author CA"), validade **1 ano** |
| Certificado de distribuidor | emitido pela `/apis/v3/distributors` com o DUID no SAN (CA "VD DEVELOPER Public CA Class"), validade **1 ano**; `/apis/v1/distributors` devolveu o `device-profile.xml` |
| CAs intermediárias | não vêm na resposta; arquivos públicos `vd_tizen_dev_*` verificados por assinatura |
| Assinatura | precisa ser **RSA-SHA512/SHA-512** no formato exato do Tizen CLI; SHA-256 → `install failed[118]` |
| **Permit to install** | **obrigatório**: push do `device-profile.xml` para `/home/developer/`. Sem ele → `install failed[118]` |
| Instalação | `0 vd_appinstall <package ID> /opt/usr/apps/tmp/<pkg>.wgt` → `install completed` |
| Abertura | `0 execute` **recusado** (CLSE) no Tizen 2.4; `0 was_execute <app ID>` → `launched` |

Comandos que fecharam o teste:

```
python -m tizen_poc cert --duid <DUID-da-TV> --out ..\..\perfil-teste
python -m tizen_poc sign teste-a.wgt teste-a-assinado.wgt --profile ..\..\perfil-teste
python -m tizen_poc install <IP-da-TV> teste-a-assinado.wgt --permit ..\..\perfil-teste\device-profile.xml
```

Pendências: segunda TV (de preferência Tizen 4+), limite de DUIDs por certificado, `.p12` compatível
com o Tizen Studio (Java).

## Preparação

- PC e celular na mesma rede Wi-Fi da TV.
- `cd poc && python -m pip install -r requirements.txt` (Python 3.11+).
- Na TV: Apps → `12345` → Developer Mode **ON** → IP do PC (etapas A/B) ou do celular (etapa D)
  → reiniciar a TV.
- **Feche o Tizen Studio/`sdb` do PC** antes de testar pelo celular (a TV aceita uma conexão por vez).

## A. Descoberta e DUID (PC)

```
python -m tizen_poc discover
python -m tizen_poc info <IP-da-TV> --trace
```

Anote: banner, `platform_version`, `sdk_toolpath`, saída de `0 getduid`, e o `duid` do `/api/v2/`.
Compare com o DUID que o Device Manager do Tizen Studio mostra.

## B. Captura do Tizen Studio (PC)

```
python -m tizen_poc proxy --tv <IP-da-TV> --log captura-tizen-studio.log
sdb connect 127.0.0.1:26199        # em outro terminal
```

Instale um app pelo Tizen Studio (Run As → Tizen Web Application) no "aparelho" 127.0.0.1:26199.
Confira no log: versão/maxdata do CNXN, serviços abertos, comando exato de instalação (package ou
app ID), destino do push e comando de abertura. Atualize `docs/sdb-protocolo.md`.

## C. Instalação sem Tizen Studio (PC)

1. Com um `.wgt` assinado pelo Tizen Studio para esta TV:
   `python -m tizen_poc install <IP> app-assinado.wgt --trace`
2. Certificado novo sem Tizen Studio (Estratégia A), para uma **segunda TV**:
   ```
   python -m tizen_poc cert --duid <DUID-TV2> --out ../../perfil-teste --privilege public
   python -m tizen_poc sign app.wgt app-tv2.wgt --profile ../../perfil-teste
   python -m tizen_poc install <IP-TV2> app-tv2.wgt
   ```
   Guarde o perfil **fora do repositório**. Se a resposta da Samsung vier só com a folha, passe
   `--author-ca` / `--distributor-ca` com as CAs da Samsung Certificate Extension.

## D. App Android

Abra `android/` no Android Studio, rode no celular, e: Procurar TVs → escolher a TV → Testar conexão
SDB → Escolher arquivo (o `.wgt` assinado) → Instalar e abrir → Copiar relatório (cole abaixo).

## Resultados

| # | TV (modelo / ano) | Firmware / Tizen | DUID api/v2 | DUID SDB | Etapa | Resultado | Saída relevante |
|---|---|---|---|---|---|---|---|
| 1 | | | | | A | | |
| 2 | | | | | B | | |
| 3 | | | | | C1 | | |
| 4 | | | | | C2 | | |
| 5 | | | | | D | | |

### Comandos SDB confirmados

| Item | Esperado | Observado |
|---|---|---|
| CNXN do host | `0x00100000`, `0x40000`, `host::` | |
| CNXN da TV | `0x02000000`, `0x3ff9c`, `device::<modelo>::0` | |
| Destino do push | `<sdk_toolpath>/tmp/<pkg>.wgt` | |
| Instalação | `0 vd_appinstall <package ID> <arquivo>` | |
| Abertura | `0 execute <app ID>` (ou `0 was_execute`) | |
| DUID | `0 getduid` | |

### Limites encontrados

| Item | Valor |
|---|---|
| DUIDs por certificado de distribuidor | |
| Validade do certificado de autor | |
| Validade do certificado de distribuidor | |
| Resposta v3 traz a CA intermediária? | |
| Firmwares/versões de Tizen testados | |
| Comportamento após trocar o IP do celular | |

### Conclusão

- [ ] Geração de certificado fora do Tizen Studio **viável** → seguir para a Fase 2.
- [ ] **Não viável** → parar e reavaliar (motivo: …).
