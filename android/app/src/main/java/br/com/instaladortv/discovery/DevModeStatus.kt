package br.com.instaladortv.discovery

/** Situação do Modo Desenvolvedor da TV em relação a este celular. */
enum class DevModeStatus(val label: String) {
    READY("Pronta para instalar"),
    OTHER_DEVICE("Liberada para outro aparelho"),
    OFF("Modo Desenvolvedor desligado"),
    NEEDS_RESTART("Reinicie a TV"),
    UNKNOWN("Verificar Modo Desenvolvedor"),
    ;

    companion object {
        /**
         * A TV só aceita SDB do IP configurado no Modo Desenvolvedor; com outro IP ela derruba a
         * conexão sem explicar. Por isso o app compara antes de conectar (lição da Fase 0, etapa D).
         */
        fun of(tv: TvInfo, phoneIp: String?): DevModeStatus = when {
            tv.developerMode == false -> OFF
            tv.developerIp.isNotBlank() && phoneIp != null && tv.developerIp != phoneIp -> OTHER_DEVICE
            tv.developerIp == phoneIp && !tv.sdbOpen -> NEEDS_RESTART
            tv.sdbOpen && (tv.developerIp.isBlank() || tv.developerIp == phoneIp) -> READY
            else -> UNKNOWN
        }
    }
}
