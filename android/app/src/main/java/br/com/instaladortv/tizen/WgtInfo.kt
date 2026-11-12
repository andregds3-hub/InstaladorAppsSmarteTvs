package br.com.instaladortv.tizen

import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

class WgtException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class WgtInfo(
    val widgetId: String,
    val version: String,
    val packageId: String,
    val appId: String,
    val name: String,
    val requiredVersion: String,
    val hasAuthorSignature: Boolean = false,
    val hasDistributorSignature: Boolean = false,
) {
    val isSigned: Boolean get() = hasAuthorSignature && hasDistributorSignature

    companion object {
        private const val W3C_NS = "http://www.w3.org/ns/widgets"
        private const val TIZEN_NS = "http://tizen.org/ns/widgets"
        private const val MAX_CONFIG_SIZE = 1024 * 1024L

        /** O sdbd só aceita IDs nesse formato; também impede injeção no comando de shell. */
        val TIZEN_ID = Regex("^[A-Za-z0-9_.]+$")
        private val DISTRIBUTOR_SIGNATURE = Regex("^signature\\d+\\.xml$")

        fun parseConfig(input: InputStream): WgtInfo {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            val root = try {
                factory.newDocumentBuilder().parse(input).documentElement
            } catch (e: Exception) {
                throw WgtException("config.xml inválido: ${e.message}", e)
            }
            if (root.namespaceURI != W3C_NS || root.localName != "widget") throw WgtException("config.xml não é um widget W3C")
            val app = root.firstChild(TIZEN_NS, "application") ?: throw WgtException("config.xml sem <tizen:application>")
            val packageId = app.getAttribute("package")
            val appId = app.getAttribute("id")
            if (!TIZEN_ID.matches(packageId)) throw WgtException("<tizen:application package> inválido: '$packageId'")
            if (!TIZEN_ID.matches(appId)) throw WgtException("<tizen:application id> inválido: '$appId'")
            return WgtInfo(
                widgetId = root.getAttribute("id"),
                version = root.getAttribute("version"),
                packageId = packageId,
                appId = appId,
                name = root.firstChild(W3C_NS, "name")?.textContent?.trim().orEmpty(),
                requiredVersion = app.getAttribute("required_version"),
            )
        }

        fun read(file: File): WgtInfo {
            val zip = try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw WgtException("o arquivo não é um .wgt (zip) válido", e)
            }
            zip.use {
                val config = it.getEntry("config.xml") ?: throw WgtException("o pacote não tem config.xml na raiz")
                if (config.size > MAX_CONFIG_SIZE) throw WgtException("config.xml grande demais")
                val info = it.getInputStream(config).use(::parseConfig)
                val names = it.entries().asSequence().map { e -> e.name }.toSet()
                return info.copy(
                    hasAuthorSignature = "author-signature.xml" in names,
                    hasDistributorSignature = names.any { n -> DISTRIBUTOR_SIGNATURE.matches(n) },
                )
            }
        }

        private fun Element.firstChild(ns: String, name: String): Element? {
            val nodes = getElementsByTagNameNS(ns, name)
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node.parentNode == this) return node as Element
            }
            return null
        }
    }
}
