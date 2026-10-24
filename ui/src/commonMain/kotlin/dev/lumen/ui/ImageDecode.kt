package dev.lumen.ui

import androidx.compose.ui.graphics.ImageBitmap

/** Decode PNG/JPEG/GIF bytes (a sign-up CAPTCHA). Null when undecodable. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?
