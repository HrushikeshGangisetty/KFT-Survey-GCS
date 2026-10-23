package com.kft.gcs.core.mavlink.signing

/**
 * SHA-256 (FIPS 180-4), all 32 bytes. MAVLink 2 signing hashes `key ‖ frame` with plain SHA-256, not HMAC
 * (https://mavlink.io/en/guide/message_signing.html). Both of our targets are JVMs, so the one `actual` uses the
 * platform's `MessageDigest`, the same way `core:vehicle` does HMAC-SHA256 (ADR-003).
 */
internal expect fun sha256(data: ByteArray): ByteArray
