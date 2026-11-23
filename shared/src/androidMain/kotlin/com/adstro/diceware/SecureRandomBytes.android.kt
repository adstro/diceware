package com.adstro.diceware

import java.security.SecureRandom

// Default constructor on purpose: the platform picks its strongest provider (never SHA1PRNG).
actual fun secureRandomBytes(): RandomBytes {
    val random = SecureRandom()
    return RandomBytes { bytes ->
        try {
            random.nextBytes(bytes)
        } catch (e: RuntimeException) {
            throw RandomnessUnavailableException("SecureRandom failed: $e")
        }
    }
}
