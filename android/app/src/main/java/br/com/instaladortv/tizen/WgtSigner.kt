package br.com.instaladortv.tizen

import java.io.File
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import org.bouncycastle.util.encoders.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class SigningException(message: String) : Exception(message)

/** Chave e cadeia [folha, CA intermediária da Samsung] de um certificado de autor ou distribuidor. */
class SigningIdentity(val key: PrivateKey, val chain: List<X509Certificate>)

/**
 * Assina um .wgt no formato W3C Widget Digital Signature exatamente como o Tizen CLI
 * (mesma lógica de poc/tizen_poc/signer.py, aprovada pela UN49KU6300): RSA-SHA512 e SHA-512,
 * base64 em linhas de 76, referências e entradas do zip em ordem alfabética, sem declaração XML.
 *
 * O XML é escrito já na forma canônica: o texto assinado é o SignedInfo do arquivo com o
 * xmlns do dsig no próprio elemento (Exclusive C14N), e o #prop é o Object com o xmlns herdado (C14N 1.1).
 */
object WgtSigner {
    private const val DSIG_NS = "http://www.w3.org/2000/09/xmldsig#"
    private const val DSP_NS = "http://www.w3.org/2009/xmldsig-properties"
    private const val EXC_C14N = "http://www.w3.org/2001/10/xml-exc-c14n#"
    private const val C14N11 = "http://www.w3.org/2006/12/xml-c14n11"
    private const val RSA_SHA512 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512"
    private const val SHA512 = "http://www.w3.org/2001/04/xmlenc#sha512"
    private const val WIDGET_DIGSIG = "http://www.w3.org/ns/widgets-digsig"

    const val AUTHOR_FILE = "author-signature.xml"
    const val DISTRIBUTOR_FILE = "signature1.xml"
    private val SIGNATURE_FILE = Regex("^(author-signature|signature\\d+)\\.xml$")
    private const val MAX_ENTRY = 200L * 1024 * 1024

    fun sign(source: File, target: File, author: SigningIdentity, distributor: SigningIdentity) {
        val files = contentFiles(source)
        val authorXml = buildSignature(files, author, "author")
        val distributorXml = buildSignature(files + (AUTHOR_FILE to authorXml), distributor, "distributor")
        val entries = (files + (AUTHOR_FILE to authorXml) + (DISTRIBUTOR_FILE to distributorXml)).sortedBy { it.first }
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }

    internal fun contentFiles(source: File): List<Pair<String, ByteArray>> {
        val files = mutableListOf<Pair<String, ByteArray>>()
        ZipFile(source).use { zip ->
            for (entry in zip.entries()) {
                val name = entry.name
                if (entry.isDirectory || SIGNATURE_FILE.matches(name)) continue
                if (name.startsWith("/") || name.split('/').contains("..") || '\\' in name) {
                    throw SigningException("caminho inválido dentro do pacote: $name")
                }
                if (entry.size > MAX_ENTRY) throw SigningException("arquivo grande demais no pacote: $name")
                files += name to zip.getInputStream(entry).use { it.readBytes() }
            }
        }
        if (files.none { it.first == "config.xml" }) throw SigningException("o pacote não tem config.xml na raiz")
        return files
    }

    internal fun buildSignature(files: List<Pair<String, ByteArray>>, identity: SigningIdentity, role: String): ByteArray {
        val signatureId = if (role == "author") "AuthorSignature" else "DistributorSignature"
        val references = files.sortedBy { it.first }.map { (name, data) -> referenceUri(name) to b64Lines(sha512(data)) }
        val prop = propObject(signatureId, role)
        val propDigest = b64Lines(sha512(withDsigNs(prop, "Object").toByteArray(Charsets.UTF_8)))
        val signedInfo = signedInfo(references, propDigest)
        val canonical = withDsigNs(signedInfo, "SignedInfo").toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA512withRSA").run {
            initSign(identity.key)
            update(canonical)
            sign()
        }
        val certs = identity.chain.joinToString("\n") { "<X509Certificate>\n${b64Lines(it.encoded)}\n</X509Certificate>" }
        return listOf(
            "<Signature xmlns=\"$DSIG_NS\" Id=\"$signatureId\">",
            signedInfo,
            "<SignatureValue>\n${b64Lines(signature)}\n</SignatureValue>",
            "<KeyInfo>\n<X509Data>\n$certs\n</X509Data>\n</KeyInfo>",
            prop,
            "</Signature>",
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    private fun signedInfo(references: List<Pair<String, String>>, propDigest: String): String {
        val lines = mutableListOf(
            "<SignedInfo>",
            "<CanonicalizationMethod Algorithm=\"$EXC_C14N\"></CanonicalizationMethod>",
            "<SignatureMethod Algorithm=\"$RSA_SHA512\"></SignatureMethod>",
        )
        for ((uri, digest) in references) {
            lines += "<Reference URI=\"${attr(uri)}\">"
            lines += "<DigestMethod Algorithm=\"$SHA512\"></DigestMethod>"
            lines += "<DigestValue>$digest</DigestValue>"
            lines += "</Reference>"
        }
        lines += "<Reference URI=\"#prop\">"
        lines += "<Transforms>\n<Transform Algorithm=\"$C14N11\"></Transform>\n</Transforms>"
        lines += "<DigestMethod Algorithm=\"$SHA512\"></DigestMethod>"
        lines += "<DigestValue>$propDigest</DigestValue>"
        lines += "</Reference>"
        lines += "</SignedInfo>"
        return lines.joinToString("\n")
    }

    private fun propObject(signatureId: String, role: String): String {
        fun prop(id: String, inner: String) = "<SignatureProperty Id=\"$id\" Target=\"#$signatureId\">$inner</SignatureProperty>"
        return "<Object Id=\"prop\"><SignatureProperties xmlns:dsp=\"$DSP_NS\">" +
            prop("profile", "<dsp:Profile URI=\"$WIDGET_DIGSIG#profile\"></dsp:Profile>") +
            prop("role", "<dsp:Role URI=\"$WIDGET_DIGSIG#role-$role\"></dsp:Role>") +
            prop("identifier", "<dsp:Identifier></dsp:Identifier>") +
            "</SignatureProperties></Object>"
    }

    private fun withDsigNs(xml: String, tag: String) = xml.replaceFirst("<$tag", "<$tag xmlns=\"$DSIG_NS\"")

    private fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    /** Base64 em linhas de 76 caracteres, como o encoder MIME do Java que o Tizen usa. */
    internal fun b64Lines(data: ByteArray): String = Base64.toBase64String(data).chunked(76).joinToString("\n")

    /** Como `urllib.parse.quote(name, safe="/")`: só letras, dígitos, `_.-~` e `/` ficam sem escape. */
    internal fun referenceUri(name: String): String = buildString {
        for (b in name.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF)
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "_.-~/") append(ch)
            else append('%').append("%02X".format(c))
        }
    }

    private fun attr(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")
        .replace("\t", "&#x9;").replace("\n", "&#xA;").replace("\r", "&#xD;")
}
