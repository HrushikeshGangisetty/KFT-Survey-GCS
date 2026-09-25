package com.kft.gcs.core.vehicle

/**
 * HMAC-SHA256 (RFC 2104 with SHA-256), all 32 bytes. Both of our targets are JVMs, so the one `actual` uses the
 * platform's `javax.crypto` (ADR-003).
 */
internal expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
