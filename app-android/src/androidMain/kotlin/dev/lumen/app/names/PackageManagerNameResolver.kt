package dev.lumen.app.names

import android.content.Context
import android.content.pm.PackageManager
import dev.lumen.core.collector.AppNameResolver
import dev.lumen.core.model.AppKey

/**
 * Resolves Android app names via [PackageManager].
 *
 * `UsageStatsManager` reports package names only, so backfilled history is a
 * list of `com.instagram.android` without this — the same defect macOS had
 * with bundle ids from the Screen Time store.
 *
 * Returns null for a package that is no longer installed. That case is
 * common in real history (the user uninstalled the app, which is often
 * exactly the app they want to look back at) and an id is a more honest
 * answer than a fabricated label.
 */
class PackageManagerNameResolver(
    private val packageManager: PackageManager,
) : AppNameResolver {

    constructor(context: Context) : this(context.packageManager)

    override fun resolve(appKey: AppKey): String? {
        val pkg = appKey.value.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
