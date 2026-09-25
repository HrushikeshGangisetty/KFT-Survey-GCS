package com.kft.gcs.core.vehicle

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The platform's HMAC-SHA256: the JDK provider on desktop, Conscrypt/BoringSSL on Android. */
internal actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }
