package com.ldp.reader.media

import java.security.cert.X509Certificate

object MediaPlaybackTlsPolicy {
    private val knownAliyunCdnAliasHosts = setOf("pp.ting55.com")
    private val acceptedAliyunCdnCertificateNames = setOf("*.aliyun.com", "*.alicdn.com")

    fun acceptsKnownAudioCdnAlias(host: String, certificateDnsNames: Collection<String>): Boolean {
        val normalizedHost = host.trim().lowercase()
        if (normalizedHost !in knownAliyunCdnAliasHosts) return false
        return certificateDnsNames.any { dnsName ->
            dnsName.trim().lowercase() in acceptedAliyunCdnCertificateNames
        }
    }

    fun dnsSubjectAlternativeNames(certificate: X509Certificate): List<String> {
        return certificate.subjectAlternativeNames
            ?.mapNotNull { item ->
                val type = item.getOrNull(0) as? Int
                val value = item.getOrNull(1) as? String
                value?.takeIf { type == DNS_NAME_TYPE && it.isNotBlank() }
            }
            .orEmpty()
    }

    private const val DNS_NAME_TYPE = 2
}
