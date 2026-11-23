package com.adstro.diceware

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(): RandomBytes = RandomBytes { bytes ->
    if (bytes.isEmpty()) return@RandomBytes
    val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, bytes.size.toULong(), it.addressOf(0)) }
    if (status != errSecSuccess) throw RandomnessUnavailableException("SecRandomCopyBytes failed with status $status")
}
