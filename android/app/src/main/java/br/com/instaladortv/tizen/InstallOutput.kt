package br.com.instaladortv.tizen

enum class Outcome { SUCCESS, FAILURE, UNKNOWN }

data class Diagnosis(val outcome: Outcome, val code: String?, val message: String)

/**
 * Traduz a saída de `0 vd_appinstall` em mensagens claras. As marcas vêm de saídas reais
 * publicadas; complete com o que as TVs devolverem na Fase 0.
 */
object InstallOutput {
    private val installFailed = Regex("""install failed\[(\d+)(?:,\s*(-?\d+))?]""", RegexOption.IGNORE_CASE)
    private val downloadFailed = Regex("""download failed\[(\d+)]""", RegexOption.IGNORE_CASE)
    private val connectionLost = Regex("transport connection lost|connection reset by peer", RegexOption.IGNORE_CASE)
    private val keyEndFail = Regex("""key\[end]\s*val\[fail]""", RegexOption.IGNORE_CASE)
    private val success = Regex("""installing\[100]|install completed""", RegexOption.IGNORE_CASE)
    private val launchOk = Regex("success|launched", RegexOption.IGNORE_CASE)
    private val launchFail = Regex("fail|error|not found|invalid", RegexOption.IGNORE_CASE)

    private const val NO_SPACE = "A TV está sem espaço livre. Desinstale outros apps e tente de novo."

    fun diagnose(output: String): Diagnosis {
        installFailed.find(output)?.let { return byCode(it.groupValues[1], it.groupValues[2].ifEmpty { null }, output) }
        downloadFailed.find(output)?.let {
            val code = it.groupValues[1]
            return if (code == "116") Diagnosis(Outcome.FAILURE, code, NO_SPACE)
            else Diagnosis(Outcome.FAILURE, code, "A TV não conseguiu receber o pacote (código $code).")
        }
        if (connectionLost.containsMatchIn(output)) {
            return Diagnosis(Outcome.FAILURE, null, "A conexão com a TV caiu durante a instalação. Confira o Wi-Fi e tente de novo.")
        }
        if (keyEndFail.containsMatchIn(output)) return Diagnosis(Outcome.FAILURE, null, "A TV informou falha na instalação.")
        if (success.containsMatchIn(output)) return Diagnosis(Outcome.SUCCESS, null, "App instalado.")
        return Diagnosis(Outcome.UNKNOWN, null, "A TV não confirmou a instalação. Veja a saída completa no log.")
    }

    private fun byCode(code: String, detail: String?, text: String): Diagnosis {
        val full = if (detail != null) "$code,$detail" else code
        val lower = text.lowercase()
        val message = when {
            code == "116" -> NO_SPACE
            (code == "118" && detail == "-12") || code == "118012" -> when {
                "different certificate" in lower ->
                    "Já existe uma versão deste app na TV assinada com outro certificado. Desinstale o app da TV e instale de novo."
                "expired" in lower -> "O certificado que assinou o pacote expirou. Gere um certificado novo."
                "not valid yet" in lower -> "O certificado ainda não é válido. Confira a data e a hora da TV."
                "invalid format" in lower -> "O certificado da assinatura está em formato inválido."
                else -> "A TV recusou a assinatura do pacote. Confira se o certificado de distribuidor contém o DUID desta TV."
            }
            (code == "118" && detail == "-19") || code == "118019" -> "A TV não conseguiu ler o config.xml do pacote."
            code == "118" && detail == "-4" ->
                "A TV não permite este pacote: a versão de Tizen exigida pelo app é maior que a da TV, " +
                    "ou falta permissão (privilégio) no certificado."
            code == "118" && detail == "-22" -> "O ID do pacote está bloqueado ou em conflito com um app de sistema da TV."
            // TVs 2016 (Tizen 2.4) devolvem 118 sem detalhe até para assinatura com cadeia incompleta.
            code == "118" && detail == null ->
                "A TV recusou o pacote (código 118, sem detalhe). Causas comuns: assinatura ou certificado " +
                    "inválido para esta TV, ou o app já instalado com outro certificado (nesse caso, desinstale-o)."
            else -> "A TV recusou a instalação (código $full)."
        }
        return Diagnosis(Outcome.FAILURE, full, message)
    }

    fun diagnoseLaunch(output: String): Outcome = when {
        launchFail.containsMatchIn(output) -> Outcome.FAILURE
        launchOk.containsMatchIn(output) -> Outcome.SUCCESS
        else -> Outcome.UNKNOWN
    }
}
