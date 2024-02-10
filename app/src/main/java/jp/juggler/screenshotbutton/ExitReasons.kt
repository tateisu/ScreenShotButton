package jp.juggler.screenshotbutton

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import jp.juggler.util.LogCategory
import jp.juggler.util.systemService
import jp.juggler.util.withCaption
import java.text.SimpleDateFormat
import java.util.*

@Suppress("MaxLineLength")
private fun exitReasonString(v: Int) = when (v) {
    ApplicationExitInfo.REASON_ANR ->
        "REASON_ANR Application process was killed due to being unresponsive (ANR)."

    ApplicationExitInfo.REASON_CRASH ->
        "REASON_CRASH Application process died because of an unhandled exception in Java code."

    ApplicationExitInfo.REASON_CRASH_NATIVE ->
        "REASON_CRASH_NATIVE Application process died because of a native code crash."

    ApplicationExitInfo.REASON_DEPENDENCY_DIED ->
        "REASON_DEPENDENCY_DIED Application process was killed because its dependency was going away, for example, a stable content provider connection's client will be killed if the provider is killed."

    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
        "REASON_EXCESSIVE_RESOURCE_USAGE Application process was killed by the system due to excessive resource usage."

    ApplicationExitInfo.REASON_EXIT_SELF ->
        "REASON_EXIT_SELF Application process exit normally by itself, for example, via java.lang.System#exit; getStatus will specify the exit code."

    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
        "REASON_INITIALIZATION_FAILURE Application process was killed because of initialization failure, for example, it took too long to attach to the system during the start, or there was an error during initialization."

    ApplicationExitInfo.REASON_LOW_MEMORY ->
        "REASON_LOW_MEMORY Application process was killed by the system low memory killer, meaning the system was under memory pressure at the time of kill."

    ApplicationExitInfo.REASON_OTHER ->
        "REASON_OTHER Application process was killed by the system for various other reasons which are not by problems in apps and not actionable by apps, for example, the system just finished updates; getDescription will specify the cause given by the system."

    ApplicationExitInfo.REASON_PERMISSION_CHANGE ->
        "REASON_PERMISSION_CHANGE Application process was killed due to a runtime permission change."

    ApplicationExitInfo.REASON_SIGNALED ->
        "REASON_SIGNALED Application process died due to the result of an OS signal; for example, android.system.OsConstants#SIGKILL; getStatus will specify the signal number."

    ApplicationExitInfo.REASON_UNKNOWN ->
        "REASON_UNKNOWN Application process died due to unknown reason."

    ApplicationExitInfo.REASON_USER_REQUESTED ->
        "REASON_USER_REQUESTED Application process was killed because of the user request, for example, user clicked the \"Force stop\" button of the application in the Settings, or removed the application away from Recents."

    ApplicationExitInfo.REASON_USER_STOPPED ->
        "REASON_USER_STOPPED Application process was killed, because the user it is running as on devices with mutlple users, was stopped."

    else -> "?($v)"
}

/**
 * getHistoricalProcessExitReasonsを文字列に変換したリストを返す
 * または例外を投げる
 */

fun Context.getExitReasons( maxNum: Int = 10): List<String> {
    if (Build.VERSION.SDK_INT < 30) {
        error("getExitReasons() can be used for devices Android 11+")
    } else {
        val sdf = SimpleDateFormat("yyyy-MM-dd hh:mm:ss", Locale.getDefault())
        fun formatTime(t: Long) = sdf.format(Date(t))

        val am:ActivityManager = systemService() ?: error("missing ActivityManager.")
        return am.getHistoricalProcessExitReasons(null, 0, maxNum)
            .map { info ->
                val trace = try {
                    info.traceInputStream?.use {
                        it.readBytes().toString()
                    } ?: "(null)"
                } catch (ex: Throwable) {
                    ex.withCaption("can't read traceInputStream.")
                }
                """
                timestamp=${formatTime(info.timestamp)}
                importance=${info.importance}
                pss=${info.pss}
                rss=${info.rss}
                reason=${exitReasonString(info.reason)}
                status=${info.status}
                description=${info.description}
                trace=$trace
                """.trimIndent()
            }
    }
}
