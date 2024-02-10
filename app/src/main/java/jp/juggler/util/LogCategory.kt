package jp.juggler.util

import android.content.Context
import android.util.Log
import android.widget.Toast
import jp.juggler.screenshotbutton.App1
import jp.juggler.screenshotbutton.Pref
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.*

@Suppress("unused")
class LogCategory(private val tag: String) {
    companion object {

        private var lastToast: Toast? = null

        private fun showToast(
            context: Context,
            bLong: Boolean,
            msg: String
        ) {
            runOnMainThread {
                lastToast?.cancel()
                val toast = Toast.makeText(
                    context, msg, when {
                        bLong -> Toast.LENGTH_LONG
                        else -> Toast.LENGTH_SHORT
                    }
                )
                toast.show()
                lastToast = toast
            }
        }

        private fun showToast(
            context: Context,
            ex: Throwable,
            msg: String
        ) = showToast(context, true, "$msg: ${ex.javaClass.simpleName} ${ex.message}")


        private const val V = "V"
        private const val D = "D"
        private const val I = "I"
        private const val W = "W"
        private const val E = "E"

        private val defaultOutLogToFile: (tag: String, lv: String, msg: String) -> Unit =
            { _, _, _ -> }
        private val defaultOutLogToFileEx: (tag: String, lv: String, msg: String, ex: Throwable) -> Unit =
            { _, _, _, _ -> }

        @Volatile
        private var outLogToFile = defaultOutLogToFile

        @Volatile
        private var outLogToFileEx = defaultOutLogToFileEx

        @Volatile
        private var logWriter: PrintWriter? = null

        fun getLogDirectory(context: Context) =
            File(context.getExternalFilesDir(null), "log")

        private fun PrintWriter.appendHeader(lv: String, tag: String): PrintWriter {
            val cal = Calendar.getInstance()
            printf(
                "%d-%02d-%02d %02d:%02d:%02d.%03d ",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                cal.get(Calendar.SECOND),
                cal.get(Calendar.MILLISECOND)
            )
            append(Thread.currentThread().id.toString())
            append(' ')
            append(lv)
            append('/')
            append(tag)
            append(": ")
            return this
        }

        fun onInitialize(context: Context) {
            setLogToFile(context, Pref.bpLogToFile(App1.pref))
        }

        fun setLogToFile(context: Context, enabled: Boolean) {

            val oldWriter = logWriter

            if (enabled == (oldWriter != null)) return

            // 切り替える時は必ず出力ストリームを一度閉じる
            if (oldWriter != null) {
                try {
                    synchronized(oldWriter) {
                        oldWriter.close()
                    }
                } catch (ex: Throwable) {
                    Log.e(App1.tagPrefix, "closeLogWriter failed.", ex)
                }
            }

            if (!enabled) {
                outLogToFile = defaultOutLogToFile
                outLogToFileEx = defaultOutLogToFileEx
                logWriter = null
            } else try {
                val dir = File(getLogDirectory(context), getCurrentTimeString().substring(0, 6))

                if (!dir.mkdirs() && !dir.isDirectory)
                    error("mkdir failed. $dir")

                val file = File(dir, "${getCurrentTimeString()}.txt")

                val writer = PrintWriter(
                    BufferedWriter(
                        OutputStreamWriter(
                            FileOutputStream(file, true),
                            "UTF-8"
                        )
                    )
                )

                outLogToFile = { tag, lv, msg ->
                    try {
                        synchronized(writer) {
                            writer
                                .appendHeader(lv, tag)
                                .println(msg)
                            writer.flush()
                        }
                    } catch (ex: Throwable) {
                        Log.e(App1.tagPrefix, "outLogToFile failed.", ex)
                    }
                }

                outLogToFileEx = { tag, lv, msg, error ->
                    try {
                        synchronized(writer) {
                            writer.appendHeader(lv, tag)
                                .append(msg)
                                .append(", ")
                                .append(error.javaClass.simpleName)
                                .append(", ")
                                .println(error.message ?: "?")
                            error.printStackTrace(writer)
                            writer.flush()
                        }
                    } catch (ex: Throwable) {
                        Log.e(App1.tagPrefix, "outLogToFileEx failed.", ex)
                    }
                }

                logWriter = writer

            } catch (ex: Throwable) {
                Log.e(App1.tagPrefix, "setLogToFile failed.", ex)
            }
        }
    }

    fun v(msg: String) {
        Log.v(tag, msg)
        outLogToFile(tag, V, msg)
    }

    fun d(msg: String) {
        Log.d(tag, msg)
        outLogToFile(tag, D, msg)
    }

    fun i(msg: String) {
        Log.i(tag, msg)
        outLogToFile(tag, I, msg)
    }

    fun w(msg: String) {
        Log.w(tag, msg)
        outLogToFile(tag, W, msg)
    }

    fun e(msg: String) {
        Log.e(tag, msg)
        outLogToFile(tag, E, msg)
    }

    fun v(ex: Throwable, msg: String) {
        Log.v(tag, msg, ex)
        outLogToFileEx(tag, V, msg, ex)
    }

    fun d(ex: Throwable, msg: String) {
        Log.d(tag, msg, ex)
        outLogToFileEx(tag, D, msg, ex)
    }

    fun i(ex: Throwable, msg: String) {
        Log.i(tag, msg, ex)
        outLogToFileEx(tag, I, msg, ex)
    }

    fun w(ex: Throwable, msg: String) {
        Log.w(tag, msg, ex)
        outLogToFileEx(tag, W, msg, ex)
    }

    fun e(ex: Throwable, msg: String) {
        Log.e(tag, msg, ex)
        outLogToFileEx(tag, E, msg, ex)
    }


    fun vToast(context: Context, bLong: Boolean, msg: String) {
        Log.v(tag, msg)
        outLogToFile(tag, V, msg)
        showToast(context, bLong, msg)
    }

    fun dToast(context: Context, bLong: Boolean, msg: String) {
        Log.d(tag, msg)
        outLogToFile(tag, D, msg)
        showToast(context, bLong, msg)
    }

    fun iToast(context: Context, bLong: Boolean, msg: String) {
        Log.i(tag, msg)
        outLogToFile(tag, I, msg)
        showToast(context, bLong, msg)
    }

    fun wToast(context: Context, bLong: Boolean, msg: String) {
        Log.w(tag, msg)
        outLogToFile(tag, W, msg)
        showToast(context, bLong, msg)
    }

    fun eToast(context: Context, bLong: Boolean, msg: String) {
        Log.e(tag, msg)
        outLogToFile(tag, E, msg)
        showToast(context, bLong, msg)
    }

    fun vToast(context: Context, ex: Throwable, msg: String) {
        Log.v(tag, msg, ex)
        outLogToFileEx(tag, V, msg, ex)
        showToast(context, ex, msg)
    }

    fun dToast(context: Context, ex: Throwable, msg: String) {
        Log.d(tag, msg, ex)
        outLogToFileEx(tag, D, msg, ex)
        showToast(context, ex, msg)
    }

    fun iToast(context: Context, ex: Throwable, msg: String) {
        Log.i(tag, msg, ex)
        outLogToFileEx(tag, I, msg, ex)
        showToast(context, ex, msg)
    }

    fun wToast(context: Context, ex: Throwable, msg: String) {
        Log.w(tag, msg, ex)
        outLogToFileEx(tag, W, msg, ex)
        showToast(context, ex, msg)
    }

    fun eToast(context: Context, ex: Throwable, msg: String) {
        Log.e(tag, msg, ex)
        outLogToFileEx(tag, E, msg, ex)
        showToast(context, ex, msg)
    }
}
