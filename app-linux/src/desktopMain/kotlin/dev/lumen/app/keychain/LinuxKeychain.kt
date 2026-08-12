package dev.lumen.app.keychain

import dev.lumen.core.crypto.KeyPairRef
import dev.lumen.core.crypto.Keychain

// Agent A owns this file. libsecret/OS keyring impl at M1.
class LinuxKeychain : Keychain {
    override fun deviceKeyPair(): KeyPairRef = TODO("M1")
}
