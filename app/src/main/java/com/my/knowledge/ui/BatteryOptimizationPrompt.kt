package com.my.knowledge.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * N4 (RELIAB-1 PR-N4): helper to detect whether the user has exempted
 * this app from Android's "battery optimization" / Doze list, and to
 * surface a one-tap path to the relevant settings page.
 *
 * Why this exists:
 *   - Android can suspend the process (and the wake/wifi locks our
 *     `IngestRuntime` carefully acquires) when the user has not
 *     whitelisted the app.
 *   - Without a whitelist, the LLM request the user kicked off can
 *     get cut mid-stream on aggressive OEM ROMs (Huawei, Xiaomi, OPPO,
 *     …) that auto-kill "not in the user's recent tasks" apps.
 *   - A simple banner pointing at the system settings page fixes
 *     ~80% of the "为什么后台跑着跑着就断流" support load.
 *
 * The public surface is intentionally small: one detector, two intent
 * factories, one context-aware caller that falls back to the app-info
 * page on ROMs that intercept the canonical `REQUEST_IGNORE_*` intent.
 */
object BatteryOptimizationPrompt {

    private const val TAG = "BatteryOptPrompt"

    /**
     * True if the OS has marked this app as "ignoring battery
     * optimizations" (i.e. the user has explicitly whitelisted us).
     * On API < 23 (M) every app is implicitly whitelisted, so the
     * function returns `true` to keep the banner hidden.
     */
    fun isIgnoring(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Build an `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent
     * pointed at our package. Some OEM ROMs intercept this intent
     * with their own permission manager — callers should fall back
     * to [appSettingsIntent] when [launch] reports a failure.
     */
    fun requestIgnoreIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Fallback: the system app-info page. Works on every ROM and
     * every API level, but requires the user to navigate the
     * "Battery → Unrestricted" toggle themselves.
     */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Some Chinese OEM ROMs (notably Huawei EMUI ≤ 9) handle the
     * canonical `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` intent
     * by routing it to a custom "protected apps" manager. We can
     * detect by trying to resolve a known component, but the safer
     * path is to just attempt the canonical intent first and fall
     * back on `ActivityNotFoundException`. The helper here keeps
     * that fallback policy in one place.
     */
    fun launch(context: Context) {
        try {
            context.startActivity(requestIgnoreIntent(context))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS not handled, falling back to app settings: ${e.message}")
            try {
                context.startActivity(appSettingsIntent(context))
            } catch (e2: ActivityNotFoundException) {
                // Last-ditch: even app-info page is unreachable (very
                // locked-down ROMs / MIUI strict mode). Surface a toast
                // so the user knows we tried.
                Log.w(TAG, "APPLICATION_DETAILS_SETTINGS not handled either: ${e2.message}")
                Toast.makeText(
                    context,
                    "请在系统设置中允许「我的知识库」后台运行",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
