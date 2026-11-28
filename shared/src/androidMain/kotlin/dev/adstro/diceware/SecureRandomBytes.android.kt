package dev.adstro.diceware

import java.security.SecureRandom

// Default constructor on purpose: the platform picks its strongest provider (never SHA1PRNG).
actual fun secureRandomBytes(): RandomBytes {
    val random = SecureRandom()
    return RandomBytes { bytes ->
        // Any provider failure must surface as unavailable randomness, never a crash or a weaker fallback.
        @Suppress("TooGenericExceptionCaught")
        try {
            random.nextBytes(bytes)
        } catch (e: RuntimeException) {
            throw RandomnessUnavailableException("SecureRandom failed", e)
        }
    }
}
