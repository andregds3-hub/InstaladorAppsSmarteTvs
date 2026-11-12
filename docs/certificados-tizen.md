# Certificados Tizen — o que foi descoberto e como o instalador usa

## Por que o certificado importa

Um `.wgt` só instala numa TV Samsung de varejo se tiver duas assinaturas W3C Widget Digital
Signature válidas:

- `author-signature.xml` — certificado de **autor** emitido pela Samsung (CA de autor da Samsung).
  Atualizações do app precisam do **mesmo** autor; com outro, a TV responde
  `install failed[118, -12] ... same id and different certificate` e o app precisa ser desinstalado.
- `signature1.xml` — certificado de **distribuidor** emitido pela Samsung, com o DUID de cada TV
  autorizada no SubjectAltName: `URN:tizen:deviceid=<DUID>`.

## Fluxo da Samsung Certificate Extension (API não documentada)

Reproduzido pelo projeto open source [Apps2Samsung](https://github.com/Apps2Samsung/Apps2Samsung)
(`Apps2Samsung.Core/Certificate/*`, `Samsung/SamsungOAuth.cs`), que gera certificados no
desktop e no Android sem o Tizen Studio. Implementado na Fase 0 em `poc/tizen_poc/samsung_cert.py`.

1. **Login (OAuth da conta Samsung)**
   - `https://account.samsung.com/accounts/be1dce529476c1a6d407c4c7578c31bd/signInGate`
     `?locale=&clientId=v285zxnl3h&redirect_uri=http://localhost:4794/signin/callback&state=accountcheckdogeneratedstatetext&tokenType=TOKEN`
   - A Samsung devolve um **POST de formulário** para `localhost:4794/signin/callback` com
     `state` e `code`; `code` é um JSON com `access_token`, `userId`, `inputEmailID`,
     `refresh_token`, `api_server_url`, `auth_server_url` etc.
   - Conta sem e-mail cadastrado não serve: o CSR de distribuidor exige e-mail.
2. **Certificado de autor**: `POST https://svdca.samsungqbe.com/apis/v3/authors`,
   multipart com `access_token`, `user_id`, `platform=VD` e `csr` (arquivo `author.csr`).
   CSR RSA 2048 / SHA256withRSA; o subject é livre (CN com o nome do autor).
3. **Certificado de distribuidor**: o mesmo CSR vai para
   - `POST .../apis/v1/distributors` → devolve o **perfil do aparelho** (`device-profile.xml`);
   - `POST .../apis/v3/distributors` → devolve o **certificado**.
   Campos: `access_token`, `user_id`, `privilege_level` (`public` ou `partner`, **minúsculo** —
   `Partner` gera um certificado public sem avisar), `developer_type=Individual`, `platform=VD`,
   `csr` (arquivo `distributor.csr`). CSR com `CN=TizenSDK`, `emailAddress=<e-mail da conta>` e
   SAN `URN:tizen:packageid=` + um `URN:tizen:deviceid=<DUID>` por TV.
4. **Cadeia**: cada `.p12` precisa de `[folha, CA intermediária da Samsung]`. A resposta traz só a
   folha (confirmado no autor); a CA intermediária vem dos arquivos da Samsung Certificate Extension.
5. **PKCS#12** com 3DES/SHA1 e alias `usercertificate`, para o Tizen Studio também abrir.

## Confirmado com conta real (2026-11-11)

- Login OAuth pelo navegador com o `clientId` da Certificate Extension: **funcionou** (callback POST em
  `localhost:4794` com `access_token`, `userId`, `inputEmailID`).
- `POST /apis/v3/authors`: **emitiu o certificado de autor** fora do Tizen Studio. Resposta em PEM com
  **só a folha**; emissor `CN=Samsung VD Author CA, OU=VD, O=Samsung Electronics Co., Ltd.,
  L=Suwon City, ST=Republic of Korea, C=KR`; **validade de 1 ano**; sem extensão AIA (não dá para
  baixar a CA automaticamente).
- A CA intermediária vem dos arquivos públicos da Samsung Certificate Extension:
  `vd_tizen_dev_author_ca.cer` (autor), `vd_tizen_dev_public2.crt` / `vd_tizen_dev_partner2.crt`
  (distribuidor). O `cert` procura em `<perfil>\ca` (ou `--ca-dir`) por emissor e assinatura.
- `POST /apis/v1/distributors` + `/apis/v3/distributors` (privilégio `public`): **emitiu o certificado
  de distribuidor com o DUID no SAN** (`URN:tizen:deviceid=<DUID da TV>`), subject
  `emailAddress=<e-mail da conta>, CN=TizenSDK`, emissor `CN=VD DEVELOPER Public CA Class, OU=VD,
  O=Samsung Electronics Co., Ltd., L=Suwon, ST=South Korea, C=KR`, **validade de 1 ano**, só a folha.
- **Conclusão: a geração dos dois certificados fora do Tizen Studio é viável.**
- A instalação do Tizen Studio contém certificados com extensões malformadas e serial negativo; o
  `find-ca` ignora esses e segue. Sem a Samsung Certificate Extension instalada, não há CA da
  Samsung no Tizen Studio (a extensão grava em `tizen-studio-data/samsung-ca/`).
- Experimento: `.p12` só com a folha → a TV 2016 recusou (`install failed[118]`). **A CA intermediária
  vai no KeyInfo.**
- CAs públicas obtidas do repositório [albinati/OverTV](https://github.com/albinati/OverTV) (`certs/ca/`)
  e **verificadas criptograficamente** contra os certificados emitidos (nome + assinatura):
  - `Samsung VD Author CA` — até 2040-06-19 — SHA-256 `5079196a1c32872a8bce74523fc12ad3557e2d87c56fafba7c6d09dced13b710`
  - `VD DEVELOPER Public CA Class` — até 2029-06-16 — SHA-256 `bf18859eb024cc1d63f008dcddd643077b0de53e958f35993ff0088d1a1d31ae`
  Por serem públicas e verificáveis, podem ir embutidas no app Android/no servidor (Fase 2).
- Assinatura e instalação com esses certificados: pendentes.

## Instalação confirmada (2026-11-11)

Com os dois certificados acima, a assinatura no formato do Tizen CLI **e o "Permit to install"** (push
do `device-profile.xml` da `/apis/v1/distributors` para `/home/developer/`), a UN49KU6300 instalou e
abriu o app. Sem o `device-profile.xml` a TV responde `install failed[118]`. O fluxo no app/servidor
precisa guardar esse arquivo junto com o perfil e enviá-lo antes de cada instalação.

## Formato da assinatura confirmado contra o Tizen CLI (2026-11-11)

Comparado com um pacote assinado por `tizen package` (Tizen Studio no PC do admin): **RSA-SHA512 e
SHA-512** (não SHA-256), base64 em linhas de 76 caracteres (`SignatureValue` e `X509Certificate`
entre quebras de linha; `DigestValue` sem), referências e entradas do zip em ordem alfabética, sem
declaração XML, sem quebra de linha final. Reassinando os mesmos arquivos, o `SignedInfo` gerado pelo
`signer.py` sai **idêntico byte a byte** ao do Tizen (inclusive o digest do `#prop`).
A versão anterior (SHA-256, base64 numa linha) foi recusada pela TV 2016 com `install failed[118]`.

Pendência: o Tizen CLI (Java) recusa o `.p12` gerado pelo `cert` ("Invalid file or password"). Não
afeta a instalação pela nossa ferramenta; só a importação do perfil no Tizen Studio.

## Assinatura do .wgt (implementada, independente do Tizen Studio)

`poc/tizen_poc/signer.py`: XML-DSig com Exclusive C14N no `SignedInfo`, RSA-SHA256, SHA-256 por
arquivo, URIs percent-encoded, objeto `#prop` (Profile, Role `role-author`/`role-distributor`,
Identifier) com C14N 1.1. A assinatura do distribuidor cobre também `author-signature.xml`.
Os testes verificam as duas assinaturas com a canonização do libxml2 (independente do signer).

## Riscos e limites

- **API não documentada e client ID da própria Samsung.** O fluxo usa o `clientId` da Samsung
  Certificate Extension. A Samsung pode mudar ou bloquear isso a qualquer momento, e usar o ID de
  outra aplicação pode violar os termos da conta Samsung. **Decisão sua** antes de levar a
  Estratégia A a clientes. A Estratégia B (Tizen CLI oficial na VPS, com a sua conta) evita usar o
  client ID fora do Tizen Studio, mas depende do mesmo serviço da Samsung por trás.
- **Não contornamos nada da TV**: o pacote continua assinado por certificados emitidos pela
  Samsung para aquele DUID; a TV faz toda a verificação.
- **Limite de DUIDs** por certificado de distribuidor: não publicado pela Samsung. **[validar]**
- **Validade**: a verificar no certificado emitido (`cert` mostra `not_valid_after`). **[validar]**
- **DUID**: o campo `duid` do `/api/v2/` costuma vir como `uuid:...`, que pode **não** ser o DUID
  que o certificado exige. O DUID do Tizen Studio vem pelo SDB; a Fase 0 compara os dois. **[validar]**
- **Certificado de autor por cliente (Estratégia A)**: cada cliente assina com o próprio autor.
  Se perder o `author.p12`, a próxima versão só instala depois de desinstalar o app.

## Estratégias no código

Interface `SigningStrategy` (`android/.../cert/SigningStrategy.kt`):

- `PreSignedPackage` — Fase 0: o pacote já vem assinado para a TV de teste.
- Estratégia A (conta do cliente, assinatura no celular) e B (VPS com Tizen CLI headless) —
  Fase 2, depois do relatório da Fase 0. A lógica de CSR, chamadas e assinatura já está provada
  em Python e é portada para Kotlin (BouncyCastle) nessa fase.
