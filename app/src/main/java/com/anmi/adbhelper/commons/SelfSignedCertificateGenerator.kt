package com.anmi.adbhelper.commons

import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

class SelfSignedCertificateGenerator {
    fun generate(subject: X500Principal, keyPair: KeyPair): X509Certificate {
        val from = Date()
        val to = Date(from.time + 365 * 86400000L) // 1年有效期
        val serialNumber = BigInteger(64, SecureRandom())

        val certInfo = X509CertInfo().apply {
            set(X509CertInfo.VERSION, CertificateVersion(CertificateVersion.V3))
            set(X509CertInfo.SERIAL_NUMBER, CertificateSerialNumber(serialNumber))
            set(X509CertInfo.SUBJECT, CertificateSubjectName(X500Name(subject.name)))
            set(X509CertInfo.ISSUER, CertificateIssuerName(X500Name(subject.name)))
            set(X509CertInfo.VALIDITY, CertificateValidity(from, to))
            set(X509CertInfo.KEY, CertificateX509Key(keyPair.public))
            set(
                X509CertInfo.ALGORITHM_ID,
                CertificateAlgorithmId(AlgorithmId.get("SHA256withRSA"))
            )
        }

        val cert = X509CertImpl(certInfo)
        cert.sign(keyPair.private, "SHA256withRSA")

        return cert
    }
}
