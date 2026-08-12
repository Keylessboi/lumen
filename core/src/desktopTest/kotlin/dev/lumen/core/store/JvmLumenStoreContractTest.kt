package dev.lumen.core.store

/**
 * Wires B's abstract [LumenStoreContract] test kit (PR #17, commonTest)
 * to the JVM desktop driver. One-liner per the kit's own KDoc; Agent A's
 * zone (core/src/desktopTest).
 */
class JvmLumenStoreContractTest : LumenStoreContract() {
    override fun store(): LumenStore = JvmLumenStore.inMemory()
}
