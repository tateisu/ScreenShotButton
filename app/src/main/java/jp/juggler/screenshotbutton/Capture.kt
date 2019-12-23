package jp.juggler.screenshotbutton

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.SystemClock
import androidx.documentfile.provider.DocumentFile
import jp.juggler.util.LogCategory
import jp.juggler.util.pathFromDocumentUri
import jp.juggler.util.use
import jp.juggler.util.waitEventWithTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume


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
    private lateinit var mpm: MediaProjectionManager
    private lateinit var mediaScannerTracker: MediaScannerTracker

    private var mediaProjectionState = MediaProjectionState.Off
    private var screenCaptureIntent: Intent? = null
    private var mMediaProjection: MediaProjection? = null
    private var mediaProjectionAddr = AtomicReference<String?>(null)


    fun onInitialize(context: Context) {
        log.d("onInitialize")
        mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        handler = Handler()
        mediaScannerTracker = MediaScannerTracker(context.applicationContext, handler)
    }

    fun release(): Boolean {
        log.d("release")

        if (mMediaProjection != null) {
            log.d("MediaProjection close.")
            mMediaProjection?.stop()
            mMediaProjection = null
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
                val permissionIntent = mpm.createScreenCaptureIntent()
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
        if (resultCode != Activity.RESULT_OK) {
            log.eToast(activity, false, "permission not granted.")
            return release()
        }

        if (data == null) {
            log.eToast(activity, false, "result data is null.")
            return release()
        }
        screenCaptureIntent = data
        mediaProjectionState = MediaProjectionState.HasScreenCaptureIntent

        val bundle = data.extras
        if (bundle != null) {
            for (key in bundle.keySet()) {
                val v = bundle.get(key)
                log.d("bundle[$key]=$v")
            }
        }

        return true
    }

    fun canCapture() = mMediaProjection != null && mediaProjectionAddr.get() != null

    fun updateMediaProjection(context: Context): Boolean {
        log.d("updateMediaProjection")

        val screenCaptureIntent = this.screenCaptureIntent
        if (screenCaptureIntent == null) {
            log.e("screenCaptureIntent is null")
            return release()
        }

        // MediaProjectionの取得
        mMediaProjection?.stop()
        mMediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, screenCaptureIntent)
        return when (mMediaProjection) {
            null -> {
                log.eToast(context, false, "getMediaProjection() returns null")
                release()
            }
            else -> {
                mediaProjectionState = MediaProjectionState.HasMediaProjection
                mMediaProjection?.registerCallback(
                    object : MediaProjection.Callback() {
                        val addr = mMediaProjection.toString()

                        init {
                            log.d("MediaProjection registerCallback. addr=$addr")
                            mediaProjectionAddr.set(addr)
                        }

                        override fun onStop() {
                            super.onStop()
                            log.d("MediaProjection onStop. addr=$addr")
                            mediaProjectionAddr.compareAndSet(addr, null)
                        }
                    },
                    handler
                )
                true
            }
        }
    }


    suspend fun capture(context: Context, timeClick: Long): String {
        return CaptureEnv(context, timeClick).capture()
    }

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

        suspend fun capture(): String {

            val mediaProjection = mMediaProjection
                ?: error("mediaProjection is null.")

            val dm = context.resources.displayMetrics
            val displayWidth = dm.widthPixels
            val displayHeight = dm.heightPixels
            val densityDpi = dm.densityDpi


            ImageReader.newInstance(
                displayWidth,
                displayHeight,
                PixelFormat.RGBA_8888,
                2
            ).use { imageReader ->

                bench("create imageReader")


                var virtualDisplay:VirtualDisplay? = null
                val resumeResult = waitEventWithTimeout<String>(2000L){ cont->
                     virtualDisplay = mediaProjection.createVirtualDisplay(
                         App1.tagPrefix,
                         displayWidth,
                         displayHeight,
                         densityDpi,
                         DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                         imageReader.surface,
                         object:VirtualDisplay.Callback(){
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

                bench("waiting virtualDisplay onResume: ${resumeResult ?:  "timeout"}")

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
                            delay(10L)
                            continue
                            // 無限リトライ
                        }
                        val timeGetImage = SystemClock.elapsedRealtime()

                        try {
                            val rv = withContext(Dispatchers.IO) {
                                save(context, image)
                            }
                            bench("save OK. shutter delay=${timeGetImage - timeClick}ms")
                            return rv
                        } catch (ex: Throwable) {
                            bench("save failed")
                            val errMessage = ex.message
                            if (errMessage?.contains(ERROR_BLANK_IMAGE) == true) {
                                if (nTry < maxTry) {
                                    log.e(errMessage)
                                    ++nTry
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

        private suspend fun save(context: Context, image: Image): String {

            bench("save start")

            val width = image.width
            val height = image.height
            val plane = image.planes[0]
            val buffer = plane.buffer

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            log.d("size=($width,$height),pixelStride=$pixelStride,rowStride=$rowStride,rowPadding=$rowPadding")

            val bitmap =
                Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                    ?: error("bitmap creation failed.")

            try {
                bitmap.copyPixelsFromBuffer(buffer)

                bench("copyPixelsFromBuffer")

                createResizedBitmap(bitmap).use { smallBitmap ->
                    bench("createResizedBitmap")
                    checkBlank(smallBitmap)
                    bench("checkBlank")
                }

                val mimeType: String
                val compressFormat: Bitmap.CompressFormat
                val compressQuality: Int
                val fileExtension: String
                when {
                    Pref.bpSavePng(App1.pref) -> {
                        mimeType = "image/png"
                        compressFormat = Bitmap.CompressFormat.PNG
                        compressQuality = 100
                        fileExtension = "png"
                    }
                    else -> {
                        mimeType = "image/jpeg"
                        compressFormat = Bitmap.CompressFormat.JPEG
                        compressQuality = 100
                        fileExtension = "jpg"
                    }
                }

                if (Build.VERSION.SDK_INT >= API_USE_DOCUMENT) {
                    val saveTreeUri = Uri.parse(Pref.spSaveTreeUri(App1.pref))
                    val dir = DocumentFile.fromTreeUri(context, saveTreeUri)
                        ?: error("can't get save directory.")
                    val baseName = generateBasename()
                    val itemUri = generateFile(dir, baseName,mimeType).uri
                    context.contentResolver.openOutputStream(itemUri).use{outputStream->
                        bench("before compress")
                        bitmap.compress(compressFormat, compressQuality, outputStream)
                    }
                    bench("compress and write to $itemUri")

                    val path = pathFromDocumentUri(context,itemUri.toString())
                    if( path != null){
                        val contentUri = mediaScannerTracker.scanAndWait(path,mimeType)
                        bench("media scan: $contentUri")
                    }

                    return itemUri.toString()

                } else {
                    val dir = generateDir()
                    val displayName = generateDisplayName(dir, fileExtension)
                    val file = generateFile(dir, displayName, fileExtension)
                    val path = file.absolutePath

                    // BufferedOutputStream
                    FileOutputStream(file).use { outputStream ->
                        bitmap.compress(compressFormat, compressQuality, outputStream)
                    }
                    bench("compress and write to $path")

                    val contentUri = mediaScannerTracker.scanAndWait(path, mimeType)
                    bench("media scan: $contentUri")

                    return path
                }

            } finally {
                bitmap.recycle()
            }
        }

    }


    private fun checkBlank(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var preColor: Int? = null
        for (i in pixels) {
            val color = i or Color.BLACK
            if (preColor == color) {
                continue
            } else if (preColor == null) {
                preColor = color
            } else {
                return // not blank image
            }
        }
        error(ERROR_BLANK_IMAGE)
    }

    private fun createResizedBitmap(src: Bitmap): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val dstSize = 256
        val dst = Bitmap.createBitmap(dstSize, dstSize, Bitmap.Config.ARGB_8888)
            ?: error("createBitmap returns null")

        val canvas = Canvas(dst)
        val paint = Paint()
        paint.isFilterBitmap = true
        val matrix = Matrix()
        matrix.postScale(
            (dstSize.toFloat() + 0.999f) / srcW.toFloat(),
            (dstSize.toFloat() + 0.999f) / srcH.toFloat()
        )
        canvas.drawBitmap(src, matrix, paint)
        return dst

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

    @Suppress("DEPRECATION")
    private fun generateDir(): File {
        var dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            ?: error("shared storage is not currently available.")

        if (!dir.mkdir() && !dir.isDirectory) error("not a directory: $dir")

        dir = File(dir, App1.tagPrefix)
        if (!dir.mkdir() && !dir.isDirectory) error("not a directory: $dir")

        return dir
    }

    private fun generateDisplayName(dir: File, fileException: String): String {
        val namePrefix = generateBasename()
        var count = 1
        var displayName: String
        do {
            displayName = if (count == 1) {
                namePrefix
            } else {
                "$namePrefix-$count"
            }
            ++count

        } while (generateFile(dir, displayName, fileException).exists())

        return displayName
    }

    private fun generateFile(dir: File, displayName: String, fileException: String) =
        File(dir, "$displayName.$fileException")

    @TargetApi(29)
    fun generateFile(dir: DocumentFile, baseName: String, mimeType: String): DocumentFile {
        val duplicates =
            dir.listFiles().filter { it.name?.contains(baseName) == true }.map { it.name }.toSet()

        var count = 1
        var displayName: String
        do {
            displayName = if (count == 1) {
                baseName
            } else {
                "$baseName-$count"
            }
            ++count

        } while (duplicates.contains(displayName))

        return dir.createFile(mimeType, displayName) ?: error("DocumentFile.createFile() returns null.")
    }

}