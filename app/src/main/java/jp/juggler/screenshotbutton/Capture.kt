package jp.juggler.screenshotbutton

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.SystemClock
import android.provider.MediaStore
import jp.juggler.util.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min


object Capture {
    private val log = LogCategory("${App1.tagPrefix}/Capture")

    private const val ERROR_BLANK_IMAGE = "captured image is blank."

    private enum class MediaProjectionState {
        Off,
        Requested,
        Created,
    }

    private lateinit var handler: Handler
    private lateinit var mpm: MediaProjectionManager

    private var mediaProjectionState = MediaProjectionState.Off
    private var screenCaptureIntent: Intent? = null
    private var mMediaProjection: MediaProjection? = null


    fun onInitialize(context: Context) {
        log.d("onInitialize")
        mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        handler = Handler()
    }

    fun release() :Boolean{
        log.d("release")

        if (mMediaProjection != null) {
            log.d("MediaProjection close.")
            mMediaProjection?.stop()
            mMediaProjection = null
        }
        mediaProjectionState = MediaProjectionState.Off

        return false
    }

    fun prepareScreenCaptureIntent(activity: Activity, requestCode: Int): Boolean {
        log.d("prepareScreenCaptureIntent")
        return when (mediaProjectionState) {
            MediaProjectionState.Created -> true
            MediaProjectionState.Requested -> false
            MediaProjectionState.Off -> {
                log.d("createScreenCaptureIntent")
                mediaProjectionState = MediaProjectionState.Requested
                val permissionIntent = mpm.createScreenCaptureIntent()
                activity.startActivityForResult(permissionIntent, requestCode)
                false
            }
        }
    }

    fun handleScreenCaptureIntentResult(activity: Activity, resultCode: Int, data: Intent?) :Boolean {
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
        return updateMediaProjection(activity)
    }

    fun updateMediaProjection(context: Context) :Boolean {
        log.d("updateMediaProjection")
        val screenCaptureIntent = this.screenCaptureIntent
        if (screenCaptureIntent == null) {
            log.e("screenCaptureIntent is null")
            return false
        }

        if(mMediaProjection != null) release()
        // MediaProjectionの取得
        mMediaProjection = mpm.getMediaProjection(Activity.RESULT_OK, screenCaptureIntent)
        return when (mMediaProjection) {
            null -> {
                log.eToast(context, false, "getMediaProjection() returns null")
                release()
                false
            }
            else -> {
                mediaProjectionState = MediaProjectionState.Created
                log.d("mediaProjectionState = Created")
                true
            }
        }
    }

    fun canCapture():Boolean{
        return mMediaProjection != null
    }

    class Bench {
        private var lastTime = SystemClock.elapsedRealtime()

        fun step(caption: String) {
            val now = SystemClock.elapsedRealtime()
            val delta = now - lastTime
            log.d("${delta}ms $caption")
            lastTime = SystemClock.elapsedRealtime()
        }
    }

    suspend fun capture(context: Context): String {

        val bench = Bench()

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

            bench.step("create imageReader")

            val isResumed = AtomicBoolean(false)
            val virtualDisplay = mediaProjection.createVirtualDisplay(
                App1.tagPrefix,
                displayWidth,
                displayHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                object : VirtualDisplay.Callback() {

                    override fun onResumed() {
                        super.onResumed()
                        log.d("VirtualDisplay onResumed")
                        isResumed.set(true)
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

            bench.step("create virtualDisplay")

            val resumeExpire = SystemClock.elapsedRealtime() + 2000L
            while (!isResumed.get()) {
                val now = SystemClock.elapsedRealtime()
                val remain = resumeExpire - now
                if (remain <= 0) break
                delay(min(8L, remain))
            }
            bench.step("waiting virtualDisplay onResume")

            try {
                val maxTry = 10
                var nTry =1
                while(nTry <= maxTry) {

                    // service closed by other thread
                    if (mediaProjectionState != MediaProjectionState.Created)
                        error("mediaProjectionState is $mediaProjectionState")

                    val image = imageReader.acquireLatestImage()
                    if (image == null) {
                        log.w("acquireLatestImage() is null")
                        delay(17L)
                        continue
                        // 無限リトライ
                    }

                    try {
                        val rv = withContext(Dispatchers.IO) {
                            save(context, bench, image)
                        }
                        bench.step("save OK")
                        return rv
                    } catch (ex: Throwable) {
                        bench.step("save failed")
                        if (ex.message?.contains(ERROR_BLANK_IMAGE) == true) {
                            if (nTry < maxTry) {
                                log.e("ブランクイメージでした。リトライします…")
                                delay(17L)
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
                virtualDisplay.release()
            }
        }
    }

    private fun checkBlank(bitmap:Bitmap){
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var minR = Int.MAX_VALUE
        var minG = Int.MAX_VALUE
        var minB = Int.MAX_VALUE

        var maxR = Int.MIN_VALUE
        var maxG = Int.MIN_VALUE
        var maxB = Int.MIN_VALUE
        for (color in pixels) {
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)

            if (r < minR) minR = r
            if (g < minG) minG = g
            if (b < minB) minB = b

            if (r > maxR) maxR = r
            if (g > maxG) maxG = g
            if (b > maxB) maxB = b
        }
        if (minR == maxR && minG == maxG && minB == maxB)
            error(ERROR_BLANK_IMAGE)
    }

    private fun createResizedBitmap(src:Bitmap):Bitmap{
        val srcW = src.width
        val srcH = src.height
        val dstSize = 256
        val dst = Bitmap.createBitmap(dstSize,dstSize, Bitmap.Config.ARGB_8888)
            ?: error("createBitmap returns null")

        val canvas = Canvas(dst)
        val paint = Paint()
        paint.isFilterBitmap = true
        val matrix = Matrix()
        matrix.postScale(
            (dstSize.toFloat()+0.999f)/srcW.toFloat(),
            (dstSize.toFloat()+0.999f)/srcH.toFloat()
        )
        canvas.drawBitmap(src, matrix, paint)
        return dst

    }

    private fun save(context: Context, bench: Bench, image: Image): String {

        bench.step("save start")

        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        log.d("size=($width,$height),pixelStride=$pixelStride,rowStride=$rowStride,rowPadding=$rowPadding")

        val bitmap =
            Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                ?: error("bitmap creation failed.")

        try {
            bitmap.copyPixelsFromBuffer(buffer)

            bench.step("copyPixelsFromBuffer")

            val bitmap2 = createResizedBitmap(bitmap)
            bench.step("createResizedBitmap")
            try{
                 checkBlank(bitmap2)
            }finally{
                bitmap2.recycle()
            }
            bench.step("checkBlank")

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


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val baseName = generateBasename()

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, baseName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    // Qで必要になった
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                    // サブフォルダに保存する。Q以降で使える。
                    put(MediaStore.Images.Media.RELATIVE_PATH, App1.tagPrefix)
                }
                val collection =
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = context.contentResolver.insert(collection, values)!!
                val fd = context.contentResolver.openFileDescriptor(itemUri, "w", null)
                    ?: error("openFileDescriptor returns null")
                fd.use {
                    bench.step("before compress")
                    FileOutputStream(it.fileDescriptor).use { outputStream ->
                        bitmap.compress(compressFormat, compressQuality, outputStream)
                    }
                    bench.step("compress and write to $itemUri")
                }
                return itemUri.toString()

            } else {
                val dir = generateDir()
                val displayName = generateDisplayName(dir, fileExtension)
                val file = generateFile(dir, displayName, fileExtension)

                // BufferedOutputStream
                FileOutputStream(file).use { outputStream ->
                    bitmap.compress(compressFormat, compressQuality, outputStream)
                }

                bench.step("compress and write to $file")

                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf(mimeType),
                    null
                )

                return file.absolutePath
            }


        } finally {
            bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun generateDir(): File {
        var dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            ?: error("shared storage is not currently available.")

        if (!dir.mkdir() && !dir.isDirectory) error("not a directory: $dir")

        dir = File(dir, App1.tagPrefix)
        if (!dir.mkdir() && !dir.isDirectory) error("not a directory: $dir")

        return dir
    }

    private fun generateFile(dir: File, displayName: String, fileException: String) =
        File(dir, "$displayName.$fileException")

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