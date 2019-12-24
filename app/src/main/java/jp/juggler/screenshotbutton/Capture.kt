package jp.juggler.screenshotbutton

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.view.WindowManager
import jp.juggler.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min


object Capture {
    private val log = LogCategory("${App1.tagPrefix}/Capture")

    private const val ERROR_BLANK_IMAGE = "captured image is blank."

    private enum class MediaProjectionState {
        Off,
        RequestingScreenCaptureIntent,
        HasScreenCaptureIntent,
        HasMediaProjection,
    }

    private lateinit var handler: Handler
    private lateinit var mediaScannerTracker: MediaScannerTracker
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var windowManager: WindowManager

    private var screenCaptureIntent: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionAddr = AtomicReference<String?>(null)
    private var mediaProjectionState = MediaProjectionState.Off

    // アプリ起動時に一度だけ実行する
    fun onInitialize(context: Context) {
        log.d("onInitialize")
        handler = Handler()
        mediaScannerTracker = MediaScannerTracker(context.applicationContext, handler)
        mediaProjectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        windowManager =
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    // mediaProjection と screenCaptureIntentを解放する
    fun release(): Boolean {
        log.d("release")

        if (mediaProjection != null) {
            log.d("MediaProjection close.")
            mediaProjection?.stop()
            mediaProjection = null
        }

        screenCaptureIntent = null
        mediaProjectionState = MediaProjectionState.Off

        return false
    }

    fun prepareScreenCaptureIntent(activity: Activity, requestCode: Int): Boolean {
        log.d("prepareScreenCaptureIntent")
        return when (mediaProjectionState) {
            MediaProjectionState.HasMediaProjection,
            MediaProjectionState.HasScreenCaptureIntent -> true

            MediaProjectionState.RequestingScreenCaptureIntent -> false

            MediaProjectionState.Off -> {
                log.d("createScreenCaptureIntent")
                val permissionIntent = mediaProjectionManager.createScreenCaptureIntent()
                activity.startActivityForResult(permissionIntent, requestCode)
                mediaProjectionState = MediaProjectionState.RequestingScreenCaptureIntent
                false
            }
        }
    }

    fun handleScreenCaptureIntentResult(
        activity: Activity,
        resultCode: Int,
        data: Intent?
    ): Boolean {
        log.d("handleScreenCaptureIntentResult")
        return when {
            resultCode != Activity.RESULT_OK -> {
                log.eToast(activity, false, "permission not granted.")
                release()
            }

            data == null -> {
                log.eToast(activity, false, "result data is null.")
                release()
            }

            else -> {
                // 得られたインテントはExtrasにBinderProxyオブジェクトを含む。ファイルに保存とかは無理っぽい…
                screenCaptureIntent = data
                mediaProjectionState = MediaProjectionState.HasScreenCaptureIntent
                true
            }
        }
    }

    fun canCapture() =
        mediaProjection != null && mediaProjectionAddr.get() != null

    // throw error if failed.
    fun updateMediaProjection() {
        log.d("updateMediaProjection")

        val screenCaptureIntent = this.screenCaptureIntent
        if (screenCaptureIntent == null) {
            release()
            error("screenCaptureIntent is null")
        }

        // 以前のMediaProjectionを停止させないとgetMediaProjectionはエラーを返す
        mediaProjection?.stop()

        // MediaProjectionの取得
        val mediaProjection =
            mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, screenCaptureIntent)
        this.mediaProjection = mediaProjection

        if (mediaProjection == null) {
            release()
            error("getMediaProjection() returns null")
        }

        mediaProjectionState = MediaProjectionState.HasMediaProjection
        val addr = mediaProjection.toString()
        mediaProjectionAddr.set(addr)
        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    log.d("MediaProjection onStop. addr=$addr")
                    mediaProjectionAddr.compareAndSet(addr, null)
                }
            },
            handler
        )
        log.d("MediaProjection registerCallback. addr=$addr")
    }

    suspend fun capture(context: Context, timeClick: Long): Uri =
        CaptureEnv(context, timeClick).capture()

    private class CaptureEnv(
        val context: Context,
        val timeClick: Long
    ) : VirtualDisplay.Callback() {

        private var lastTime = SystemClock.elapsedRealtime()

        fun bench(caption: String) {
            val now = SystemClock.elapsedRealtime()
            val delta = now - lastTime
            log.d("${delta}ms $caption")
            lastTime = SystemClock.elapsedRealtime()
        }

        suspend fun capture(): Uri {

            if (!canCapture())
                updateMediaProjection()

            val mediaProjection = mediaProjection
                ?: error("mediaProjection is null.")

            val densityDpi = context.resources.displayMetrics.densityDpi

            val realSize = Point()
            windowManager.defaultDisplay.getRealSize(realSize)
            log.d("defaultDisplay.getRealSize ${realSize.x},${realSize.y}")

            ImageReader.newInstance(
                realSize.x,
                realSize.y,
                PixelFormat.RGBA_8888,
                2
            ).use { imageReader ->

                bench("create imageReader")

                var virtualDisplay: VirtualDisplay? = null

                val resumeResult = withTimeoutOrNull(2000L) {
                    suspendCoroutine<String> { cont ->
                        virtualDisplay = mediaProjection.createVirtualDisplay(
                            App1.tagPrefix,
                            realSize.x,
                            realSize.y,
                            densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            imageReader.surface,
                            object : VirtualDisplay.Callback() {
                                override fun onResumed() {
                                    super.onResumed()
                                    log.d("VirtualDisplay onResumed")
                                    cont.resume("OK")
                                }

                                override fun onStopped() {
                                    super.onStopped()
                                    log.d("VirtualDisplay onStopped")
                                }

                                override fun onPaused() {
                                    super.onPaused()
                                    log.d("VirtualDisplay onPaused")
                                }
                            },
                            handler
                        )
                        bench("create virtualDisplay")
                    }
                }

                bench("waiting virtualDisplay onResume: ${resumeResult ?: "timeout"}")

                try {
                    val maxTry = 10
                    var nTry = 1
                    while (nTry <= maxTry) {

                        // service closed by other thread
                        if (mediaProjectionState != MediaProjectionState.HasMediaProjection)
                            error("mediaProjectionState is $mediaProjectionState")

                        val image = imageReader.acquireLatestImage()
                        if (image == null) {
                            log.w("acquireLatestImage() is null")
                            // 無限リトライ
                            delay(10L)
                            continue
                        }

                        val timeGetImage = SystemClock.elapsedRealtime()

                        try {
                            return withContext(Dispatchers.IO) {
                                save(image, realSize.x, realSize.y)
                            }.also {
                                bench("save OK. shutter delay=${timeGetImage - timeClick}ms")
                            }
                        } catch (ex: Throwable) {
                            bench("save failed")
                            val errMessage = ex.message
                            if (errMessage?.contains(ERROR_BLANK_IMAGE) == true) {
                                // ブランクイメージは異常ではない場合がありうるのでリトライ回数制限あり
                                if (++nTry <= maxTry) {
                                    log.w(errMessage)
                                    delay(10L)
                                    continue
                                }
                            }
                            throw ex
                        } finally {
                            image.close()
                        }
                    }
                    error("retry count exceeded.")
                } finally {
                    virtualDisplay?.release()
                }
            }
        }

        private suspend fun save(image: Image, screenWidth: Int, screenHeight: Int): Uri {

            bench("save start")

            val imageWidth = image.width
            val imageHeight = image.height
            val plane = image.planes[0]
            val pixelBytes =
                plane.pixelStride // The distance between adjacent pixel samples, in bytes.
            val rowBytes = plane.rowStride // The row stride for this color plane, in bytes.
            val rowPixels = rowBytes / pixelBytes
            val paddingPixels = rowPixels - imageWidth

            log.d("size=($imageWidth,$imageHeight),rowPixels=$rowPixels,paddingPixels=$paddingPixels")

            @Suppress("UnnecessaryVariable") val tmpWidth = rowPixels
            @Suppress("UnnecessaryVariable") val tmpHeight = imageHeight
            return Bitmap.createBitmap(
                tmpWidth,
                tmpHeight,
                Bitmap.Config.ARGB_8888
            )?.use { tmpBitmap ->

                tmpBitmap.copyPixelsFromBuffer(plane.buffer)
                bench("copyPixelsFromBuffer")

                val srcWidth = min(tmpWidth, screenWidth)
                val srcHeight = min(tmpHeight, screenHeight)
                val srcBitmap = if (tmpWidth == srcWidth && tmpHeight == srcHeight) {
                    tmpBitmap
                } else {
                    Bitmap.createBitmap(tmpBitmap, 0, 0, srcWidth, srcHeight)
                }

                try {
                    createResizedBitmap(srcBitmap, 256, 256).use { smallBitmap ->
                        bench("createResizedBitmap")
                        if (smallBitmap.isBlank()) error(ERROR_BLANK_IMAGE)
                        bench("checkBlank")
                    }

                    val mimeType: String
                    val compressFormat: Bitmap.CompressFormat
                    val compressQuality = 100
                    when {
                        Pref.bpSavePng(App1.pref) -> {
                            mimeType = "image/png"
                            compressFormat = Bitmap.CompressFormat.PNG
                        }
                        else -> {
                            mimeType = "image/jpeg"
                            compressFormat = Bitmap.CompressFormat.JPEG
                        }
                    }

                    generateDocument(
                        context,
                        Uri.parse(Pref.spSaveTreeUri(App1.pref)),
                        generateBasename(),
                        mimeType
                    ).also { itemUri ->

                        try {
                            context.contentResolver.openOutputStream(itemUri)
                                .use { outputStream ->
                                    bench("before compress")
                                    srcBitmap.compress(
                                        compressFormat,
                                        compressQuality,
                                        outputStream
                                    )
                                    bench("compress and write to $itemUri")
                                }
                        } catch (ex: Throwable) {
                            try {
                                if (!deleteDocument(context, itemUri))
                                    log.e("deleteDocument returns false.")
                            } catch (ex2: Throwable) {
                                log.e(ex2, "deleteDocument failed.")
                            }
                            throw ex
                        }

                        val path = pathFromDocumentUri(context, itemUri)
                            ?: error("can't get path for $itemUri")

                        mediaScannerTracker.scanAndWait(path, mimeType)?.let { mediaUri ->
                            bench("media scan: $mediaUri")
                        }

                    }
                } finally {
                    if (srcBitmap !== tmpBitmap) {
                        srcBitmap?.recycle()
                    }
                }
            } ?: error("bitmap creation failed.")
        }
    }

    private fun Bitmap.isBlank(): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        var preColor: Int? = null
        for (i in pixels) {
            val color = i or Color.BLACK
            if (color == preColor) {
                continue
            } else if (null == preColor) {
                preColor = color
            } else {
                return false// not blank image
            }
        }
        return true
    }

    private fun generateBasename(): String {
        val cal = Calendar.getInstance()
        return String.format(
            "%d%02d%02d-%02d%02d%02d"
            , cal.get(Calendar.YEAR)
            , cal.get(Calendar.MONTH) + 1
            , cal.get(Calendar.DAY_OF_MONTH)
            , cal.get(Calendar.HOUR_OF_DAY)
            , cal.get(Calendar.MINUTE)
            , cal.get(Calendar.SECOND)
        )
    }
}