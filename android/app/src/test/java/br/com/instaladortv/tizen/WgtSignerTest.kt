package br.com.instaladortv.tizen

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.Key
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.crypto.AlgorithmMethod
import javax.xml.crypto.KeySelector
import javax.xml.crypto.KeySelectorResult
import javax.xml.crypto.OctetStreamData
import javax.xml.crypto.URIDereferencer
import javax.xml.crypto.XMLCryptoContext
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.dsig.keyinfo.KeyInfo
import javax.xml.crypto.dsig.keyinfo.X509Data
import javax.xml.parsers.DocumentBuilderFactory

class WgtSignerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun cert(subject: String, keys: KeyPair, issuer: String, issuerKeys: KeyPair, ca: Boolean): X509Certificate {
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$issuer"), BigInteger.valueOf(now), Date(now - 86_400_000), Date(now + 86_400_000L * 365),
            X500Name("CN=$subject"), keys.public,
        ).addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun identity(name: String): SigningIdentity {
        val caKeys = keyPair()
        val leafKeys = keyPair()
        val ca = cert("$name CA", caKeys, "$name CA", caKeys, true)
        return SigningIdentity(leafKeys.private, listOf(cert("$name leaf", leafKeys, "$name CA", caKeys, false), ca))
    }

    private fun unsignedWgt(): java.io.File {
        val file = tmp.newFile("in.wgt")
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = mapOf(
                "config.xml" to CONFIG_XML.toByteArray(),
                "index.html" to "<html></html>".toByteArray(),
                "img/my icon.png" to byteArrayOf(1, 2, 3),
                "author-signature.xml" to "<old/>".toByteArray(),
            )
            entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
            zip.putNextEntry(ZipEntry("js/")); zip.closeEntry()
        }
        return file
    }

    /** Valida com a implementação XML-DSig do JDK (independente do WgtSigner). */
    private fun validate(xml: ByteArray, zip: ZipFile, expectedLeaf: X509Certificate): List<String> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val objects = doc.getElementsByTagNameNS("http://www.w3.org/2000/09/xmldsig#", "Object")
        (objects.item(0) as Element).setIdAttributeNS(null, "Id", true)
        val factory = XMLSignatureFactory.getInstance("DOM")
        val signatureElement = doc.documentElement
        val keySelector = object : KeySelector() {
            override fun select(keyInfo: KeyInfo, purpose: Purpose, method: AlgorithmMethod, context: XMLCryptoContext): KeySelectorResult {
                val data = keyInfo.content.filterIsInstance<X509Data>().first()
                val leaf = data.content.filterIsInstance<X509Certificate>().first()
                assertEquals(expectedLeaf, leaf)
                return KeySelectorResult { leaf.publicKey as Key }
            }
        }
        val context = DOMValidateContext(keySelector, signatureElement).apply {
            setProperty("org.jcp.xml.dsig.secureValidation", false)
            val default = factory.uriDereferencer
            uriDereferencer = URIDereferencer { reference, ctx ->
                val uri = reference.uri
                if (uri.startsWith("#")) default.dereference(reference, ctx)
                else OctetStreamData(zip.getInputStream(zip.getEntry(java.net.URLDecoder.decode(uri, "UTF-8"))))
            }
        }
        val signature = factory.unmarshalXMLSignature(context)
        assertTrue("SignatureValue inválido", signature.signatureValue.validate(context))
        val uris = signature.signedInfo.references.map { (it as javax.xml.crypto.dsig.Reference).let { ref ->
            assertTrue("digest de ${ref.uri}", ref.validate(context)); ref.uri } }
        assertTrue(signature.validate(context))
        return uris
    }

    @Test fun `both signatures validate with the JDK XML-DSig engine`() {
        val author = identity("author")
        val distributor = identity("distributor")
        val out = tmp.newFile("out.wgt")
        WgtSigner.sign(unsignedWgt(), out, author, distributor)
        ZipFile(out).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(names.sorted(), names)
            assertEquals(listOf("author-signature.xml", "config.xml", "img/my icon.png", "index.html", "signature1.xml"), names)
            val authorUris = validate(zip.getInputStream(zip.getEntry("author-signature.xml")).readBytes(), zip, author.chain[0])
            assertEquals(listOf("config.xml", "img/my%20icon.png", "index.html", "#prop"), authorUris)
            val distUris = validate(zip.getInputStream(zip.getEntry("signature1.xml")).readBytes(), zip, distributor.chain[0])
            assertEquals(listOf("author-signature.xml", "config.xml", "img/my%20icon.png", "index.html", "#prop"), distUris)
        }
    }

    @Test fun `layout matches the Tizen CLI`() {
        val out = tmp.newFile("out.wgt")
        WgtSigner.sign(unsignedWgt(), out, identity("a"), identity("d"))
        val xml = ZipFile(out).use { String(it.getInputStream(it.getEntry("author-signature.xml")).readBytes()) }
        assertTrue(xml.startsWith("<Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\" Id=\"AuthorSignature\">\n<SignedInfo>"))
        assertTrue(xml.endsWith("</Object>\n</Signature>"))
        assertTrue("rsa-sha512" in xml)
        val digest = Regex("<DigestValue>(.*?)</DigestValue>", RegexOption.DOT_MATCHES_ALL).find(xml)!!.groupValues[1]
        assertEquals(listOf(76, 12), digest.split("\n").map { it.length })
    }

    @Test fun `reference uri escaping`() {
        assertEquals("a/b%20c.png", WgtSigner.referenceUri("a/b c.png"))
        assertEquals("%C3%A1.txt", WgtSigner.referenceUri("á.txt"))
        assertEquals("x_y-z.~", WgtSigner.referenceUri("x_y-z.~"))
    }

    @Test(expected = SigningException::class) fun `path traversal rejected`() {
        val file = tmp.newFile("evil.wgt")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("config.xml")); zip.write(CONFIG_XML.toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("../evil")); zip.write(byteArrayOf(1)); zip.closeEntry()
        }
        WgtSigner.sign(file, tmp.newFile("o.wgt"), identity("a"), identity("d"))
    }
}
