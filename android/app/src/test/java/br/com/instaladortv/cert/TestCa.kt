package br.com.instaladortv.cert

import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.util.encoders.Base64
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date

/** CA de teste que emite certificados a partir de CSRs, copiando o SAN, como a svdca da Samsung. */
class TestCa(name: String) {
    private val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val cert: X509Certificate

    init {
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$name"), BigInteger.valueOf(now), Date(now - 86_400_000), Date(now + 86_400_000L * 3650),
            X500Name("CN=$name"), keys.public,
        ).addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)))
    }

    fun issuePem(csrPem: String, days: Long = 365): ByteArray {
        val der = Base64.decode(csrPem.lines().filterNot { it.startsWith("-----") }.joinToString(""))
        val csr = PKCS10CertificationRequest(der)
        val now = System.currentTimeMillis()
        val builder = X509v3CertificateBuilder(
            X500Name.getInstance(cert.subjectX500Principal.encoded), BigInteger.valueOf(System.nanoTime()),
            Date(now - 60_000), Date(now + days * 86_400_000L), csr.subject, csr.subjectPublicKeyInfo,
        )
        csr.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest).firstOrNull()?.let { attr ->
            Extensions.getInstance(attr.attrValues.getObjectAt(0)).getExtension(Extension.subjectAlternativeName)?.let { builder.addExtension(it) }
        }
        val issued = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)))
        return ("-----BEGIN CERTIFICATE-----\n" + Base64.toBase64String(issued.encoded).chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n").toByteArray()
    }
}
