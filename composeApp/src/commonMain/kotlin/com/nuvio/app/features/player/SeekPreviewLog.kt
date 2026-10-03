package com.nuvio.app.features.player

/**
 * Debug logger for seek previews (expect; actuals per platform).
 *
 * Kermit has no writer installed in this app, so Kermit logs never reach
 * logcat. This indirection uses android.util.Log on Android and print on
 * iOS. Temporary triage scaffolding — remove with the Kermit logs.
 */
internal expect fun seekPreviewLog(message: String)
