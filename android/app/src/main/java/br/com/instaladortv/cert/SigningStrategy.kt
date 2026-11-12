package br.com.instaladortv.cert

import java.io.File

/** TV de destino da assinatura: o certificado de distribuidor precisa conter este DUID. */
data class SigningTarget(val duid: String, val modelName: String)

/**
 * Como o .wgt fica assinado para uma TV. Isolado para poder trocar a estratégia:
 * - [PreSignedPackage]: Fase 0, o pacote já vem assinado com um certificado que contém o DUID.
 * - Estratégia A (conta Samsung do cliente) e B (conta do admin na VPS): Fase 2, depois do
 *   relatório da Fase 0 (ver docs/certificados-tizen.md).
 */
interface SigningStrategy {
    val name: String

    /** Devolve o arquivo pronto para instalar nesta TV. */
    suspend fun prepare(packageFile: File, target: SigningTarget): File
}

object PreSignedPackage : SigningStrategy {
    override val name = "Pacote já assinado"
    override suspend fun prepare(packageFile: File, target: SigningTarget): File = packageFile
}
