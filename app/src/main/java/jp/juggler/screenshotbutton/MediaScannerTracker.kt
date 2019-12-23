package jp.juggler.screenshotbutton


import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import jp.juggler.util.LogCategory
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MediaScannerTracker(
    context: Context,
    val handler: Handler
) : MediaScannerConnection.MediaScannerConnectionClient {

    companion object {
        private val log = LogCategory("MediaScannerTracker")
    }

    class Item(
        val path: String,
        val mimeType: String,
        val onComplete: (Uri) -> Unit
    ) {
        val timeCreated = SystemClock.elapsedRealtime()
    }

    val conn: MediaScannerConnection = MediaScannerConnection(context, this)

    var isDisposed = false

    private val scanning = ArrayList<Item>()

    private val queue = ConcurrentLinkedQueue<Item>()

    private val queueReader: Runnable = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            loop@ while (true) {
                val item = queue.peek()
                when {
                    // queue is empty
                    item == null -> {
                        if (isDisposed) {
                            try {
                                conn.disconnect()
                            } catch (ex: Throwable) {
                                log.e(ex, "disconnect failed.")
                            }
                        }
                        break@loop
                    }
                    // not connected? retry later
                    !prepareConnection() -> {
                        handler.postDelayed(this, 1000L)
                        break@loop
                    }
                    else -> {
                        conn.scanFile(item.path, item.mimeType)
                        queue.poll()
                        synchronized(scanning) {
                            // sweep uncompleted old entry.
                            val now = SystemClock.elapsedRealtime()
                            val it = scanning.iterator()
                            while (it.hasNext()) {
                                if (now - it.next().timeCreated >= 3600) it.remove()
                            }
                            //
                            scanning.add(item)
                        }
                    }
                }
            }
        }
    }

    // true if connected.
    // false if not connected.
    // this function may called periodically to start connection.
    private var lastConnectStart = 0L

    init {
        prepareConnection()
    }

    @Suppress("unused")
    fun dispose() {
        isDisposed = true
        try {
            conn.disconnect()
        } catch (ex: Throwable) {
            log.e(ex, "disconnect failed.")
        }
    }

    private fun prepareConnection(): Boolean {
        if (conn.isConnected) return true

        val now = SystemClock.elapsedRealtime()
        if (now - lastConnectStart >= 5000L) {
            lastConnectStart = now
            conn.connect()
        }
        return false
    }

    override fun onMediaScannerConnected() {
        handler.post(queueReader)
    }

    override fun onScanCompleted(path: String, contentUri: Uri) {
        try {
            synchronized(scanning) {
                val now = SystemClock.elapsedRealtime()
                val it = scanning.iterator()
                while (it.hasNext()) {
                    val i = it.next()
                    if (i.path == path) {
                        it.remove()
                        return@synchronized i.onComplete
                    }
                    if (now - i.timeCreated >= 3600) it.remove()
                }
                null
            }?.invoke(contentUri)
        } catch (ex: Throwable) {
            log.e(ex, "onScanCompleted: callback failed.")
        }
    }

    private fun addFile(
        path: String,
        mimeType: String,
        onComplete: (Uri) -> Unit
    ) {
        queue.add(Item(path, mimeType, onComplete))
        handler.post(queueReader)
    }

    suspend fun scanAndWait(path: String, mimeType: String): Uri? =
        withTimeoutOrNull(10000L) {
            suspendCoroutine<Uri> { cont ->
                addFile(path, mimeType) {
                    cont.resume(it)
                }
            }
        }
}
