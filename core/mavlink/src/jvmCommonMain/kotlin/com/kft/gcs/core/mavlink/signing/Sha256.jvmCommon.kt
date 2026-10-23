package com.kft.gcs.core.mavlink.signing

import java.security.MessageDigest

/** The platform's SHA-256: the JDK provider on desktop, Conscrypt/BoringSSL on Android. */
internal actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
