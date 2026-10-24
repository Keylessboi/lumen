package dev.lumen.app.common

/**
 * Links Lumen hands to the browser come partly from servers (a CAPTCHA
 * fallback page, a provider's sign-up page). Only plain web links with a host
 * are allowed out: no `file:`, no `intent:`/custom schemes that launch other
 * apps, and nothing an argument parser (xdg-open) could take for an option.
 */
fun isSafeWebLink(url: String): Boolean {
    val text = url.trim()
    if (text.isEmpty() || text.startsWith("-") || text.any { it.isWhitespace() || it.isISOControl() }) return false
    val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    return (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()
}
