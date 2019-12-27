package jp.juggler.screenshotbutton

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import jp.juggler.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.math.min


object Capture {
    private val log = LogCategory("${App1.tagPrefix}/Capture")

    private const val ERROR_BLANK_IMAGE = "captured image is blank."
    private const val BITRAGE_MIN = 100000

    private enum class MediaProjectionState {
        Off,
        RequestingScreenCaptureIntent,
        HasScreenCaptureIntent,
        HasMediaProjection,
    }

    private val handlerThread: HandlerThread = HandlerThread("Capture.handler").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private lateinit var mediaScannerTracker: MediaScannerTracker
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var windowManager: WindowManager

    private var screenCaptureIntent: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionAddr = AtomicReference<String?>(null)
    private var mediaProjectionState = MediaProjectionState.Off

    private var videoStopper: WeakReference<Continuation<Long>>? = null


    // アプリ起動時に一度だけ実行する
    fun onInitialize(context: Context) {
        log.d("onInitialize")
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

    ////////////////////////////////////////////////////////////


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

    ////////////////////////////////////////////////////////////

    private var videoCodecInfo: MediaCodecInfoAndType = MediaCodecInfoAndType.LIST.first()
    private var videoFrameRate:Int =0
    private var videoBitRate:Int =0

    fun createVideoCodec(width:Int,height:Int):MediaCodec{
        val mimeType = videoCodecInfo.type
        val format = MediaFormat.createVideoFormat(mimeType, width, height)

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, videoBitRate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, videoFrameRate)
        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, videoFrameRate)
        format.setInteger(
            MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,
            1000000 / videoFrameRate
        )
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // 1 seconds between I-frames

        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        return  MediaCodec.createEncoderByType(mimeType).apply{
            configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
        }
    }

    // load video settings from pref to variable.
    fun loadVideoSetting(pref:SharedPreferences) {
        val sv = Pref.spCodec(pref)
        videoCodecInfo = MediaCodecInfoAndType.LIST.find{
            "${it.type} ${it.info.name}" == sv
        } ?: error("Can't find specified codec. $sv")

        videoFrameRate = Pref.spFrameRate(pref).toIntOrNull()
            ?:error("(Frame rate) Please input integer.")
        var range =  videoCodecInfo.vc.supportedFrameRates
        if( !range.contains(videoFrameRate)   )
            error("(Frame rate) $videoFrameRate is not in ${range.lower}..${range.upper}.")

        videoBitRate = Pref.spBitRate(pref).toIntOrNull()
            ?:error("(Bit rate) Please input integer.")
        range = videoCodecInfo.vc.bitrateRange
        if( ! range.contains(videoBitRate) )
            error("(Bit rate) $videoBitRate is not in ${range.lower}..${range.upper}.")

        val realSize = Point()
        windowManager.defaultDisplay.getRealSize(realSize)
        val longside = max( realSize.x,realSize.y)
        if( longside > videoCodecInfo.maxSize)
            error("current screen size is ${longside}px, but selected video codec has size limit ${videoCodecInfo.maxSize}px.")

        log.d("defaultDisplay.getRealSize ${realSize.x},${realSize.y}")
        val codec = createVideoCodec(realSize.x,realSize.y)
        codec.release()
    }

    ////////////////////////////////////////////////////////////

    data class CaptureResult(
        val documentUri: Uri,
        val mimeType: String,
        val mediaUri: Uri?
    )

    private class CaptureEnv(
        val context: Context,
        val timeClick: Long,
        val isVideo: Boolean
    ) : VirtualDisplay.Callback() {

        private var lastTime = SystemClock.elapsedRealtime()

        fun bench(caption: String) {
            val now = SystemClock.elapsedRealtime()
            val delta = now - lastTime
            log.d("${delta}ms $caption")
            lastTime = SystemClock.elapsedRealtime()
        }

        private val screenWidth: Int
        private val screenHeight: Int
        private val densityDpi: Int

        private var videoCodec: MediaCodec? = null
        private var muxer: MediaMuxer? = null
        private var muxerStarted = false
        private var trackIndex = -1
        private var inputSurface: Surface? = null
        private var virtualDisplay: VirtualDisplay? = null


        init {
            val realSize = Point()
            windowManager.defaultDisplay.getRealSize(realSize)
            log.d("defaultDisplay.getRealSize ${realSize.x},${realSize.y}")

            screenWidth = realSize.x
            screenHeight = realSize.y
            densityDpi = context.resources.displayMetrics.densityDpi

            if (!canCapture())
                updateMediaProjection()

        }

        private fun releaseEncoders() {

            if (muxerStarted) muxer?.stop()
            muxer?.release()
            muxer = null

            videoCodec?.stop()
            videoCodec?.release()
            videoCodec = null

            inputSurface?.release()
            inputSurface = null

            virtualDisplay?.release()
            virtualDisplay = null

            trackIndex = -1
            muxerStarted = false
        }


        @TargetApi(26)
        suspend fun captureVideo(): CaptureResult {

            val mediaProjection = mediaProjection
                ?: error("mediaProjection is null.")

            val mimeTypeFile = "video/mp4"

            val documentUri = generateDocument(
                context,
                Uri.parse(Pref.spSaveTreeUri(App1.pref)),
                generateBasename(),
                mimeTypeFile
            )

            try {
                context.contentResolver.openFileDescriptor(documentUri, "w")
                    ?.use { parcelFileDescriptor ->
                        try {

                            val videoCodec = createVideoCodec(screenWidth,screenHeight)

                                this.videoCodec = videoCodec
                            videoCodec.setCallback(object : MediaCodec.Callback() {

                                override fun onError(
                                    codec: MediaCodec,
                                    ex: MediaCodec.CodecException
                                ) {
                                    log.e(ex, "MediaCodec.Callback onError. codec=${codec.name}")
                                    videoStopper?.get()?.resumeWithException(ex)
                                }

                                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                                }

                                override fun onOutputFormatChanged(
                                    codec: MediaCodec,
                                    format: MediaFormat
                                ) {
                                    log.d("MediaCodec.Callback onOutputFormatChanged. codec=${codec.name}, format=$format")
                                    try {
                                        val muxer = muxer
                                            ?: error("muxer is null")

                                        if (trackIndex == -1) {
                                            trackIndex = muxer.addTrack(videoCodec.outputFormat)
                                            if (!muxerStarted && trackIndex >= 0) {
                                                muxer.start()
                                                muxerStarted = true
                                            }
                                        } else {
                                            log.w("format changed twice")
                                        }
                                    } catch (ex: Throwable) {
                                        videoStopper?.get()?.resumeWithException(ex)
                                    }
                                }

                                override fun onOutputBufferAvailable(
                                    codec: MediaCodec,
                                    index: Int,
                                    info: MediaCodec.BufferInfo
                                ) {
                                    log.d("MediaCodec.Callback onOutputBufferAvailable codec=${codec.name}, index=$index, info=$info")
                                    try {
                                        val encodedData = videoCodec.getOutputBuffer(index)
                                            ?: error("getOutputBuffer($index) is null ")
                                        try {
                                            val size = info.size
                                            val offset = info.offset
                                            when {
                                                // don't write if info has config flag.
                                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> {
                                                }
                                                size > 0 && muxerStarted -> {
                                                    encodedData.position(offset)
                                                    encodedData.limit(offset + size)
                                                    muxer?.writeSampleData(
                                                        trackIndex,
                                                        encodedData,
                                                        info
                                                    )
                                                }
                                            }
                                        } finally {
                                            videoCodec.releaseOutputBuffer(index, false)
                                        }
                                    } catch (ex: Throwable) {
                                        videoStopper?.get()?.resumeWithException(ex)
                                    }
                                }
                            })

                            inputSurface = videoCodec.createInputSurface()

                            videoCodec.start()

                            muxer = MediaMuxer(
                                parcelFileDescriptor.fileDescriptor,
                                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                            )

                            // Start the video input.
                            virtualDisplay = mediaProjection.createVirtualDisplay(
                                App1.tagPrefix,
                                screenWidth,
                                screenHeight,
                                densityDpi,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                inputSurface,
                                null /* callback */,
                                null /* handler */
                            )

                            // TODO : 画面オフとか画面回転とか画面のリサイズとかが発生したらどうなる？

                            val timeStopRequested = suspendCoroutine<Long> {
                                videoStopper = WeakReference(it)
                            }
                            videoStopper = null

                            val delay = SystemClock.elapsedRealtime() - timeStopRequested
                            log.d("stop recording. delay=${delay}ms")

                        } finally {
                            releaseEncoders()
                        }
                    }
            } catch (ex: Throwable) {
                try {
                    if (!deleteDocument(context, documentUri))
                        log.e("deleteDocument returns false.")
                } catch (ex2: Throwable) {
                    log.e(ex2, "deleteDocument failed.")
                }
                throw ex
            }

            var mediaUri: Uri? = null
            pathFromDocumentUri(context, documentUri)?.let { path ->
                mediaScannerTracker.scanAndWait(path, mimeTypeFile)?.let { mediaUri = it }
            }
            bench("media scan: $mediaUri")

            return CaptureResult(
                documentUri = documentUri,
                mimeType = mimeTypeFile,
                mediaUri = mediaUri
            )
        }

        private suspend fun save(image: Image): CaptureResult {

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

                    val documentUri = generateDocument(
                        context,
                        Uri.parse(Pref.spSaveTreeUri(App1.pref)),
                        generateBasename(),
                        mimeType
                    )
                    try {
                        context.contentResolver.openOutputStream(documentUri)
                            .use { outputStream ->
                                bench("before compress")
                                srcBitmap.compress(
                                    compressFormat,
                                    compressQuality,
                                    outputStream
                                )
                                bench("compress and write to $documentUri")
                            }
                    } catch (ex: Throwable) {
                        try {
                            if (!deleteDocument(context, documentUri))
                                log.e("deleteDocument returns false.")
                        } catch (ex2: Throwable) {
                            log.e(ex2, "deleteDocument failed.")
                        }
                        throw ex
                    }

                    val path = pathFromDocumentUri(context, documentUri)
                        ?: error("can't get path for $documentUri")

                    var mediaUri: Uri? = null
                    mediaScannerTracker.scanAndWait(path, mimeType)?.let { mediaUri = it }

                    bench("media scan: $mediaUri")

                    CaptureResult(
                        documentUri = documentUri,
                        mimeType = mimeType,
                        mediaUri = mediaUri
                    )
                } finally {
                    if (srcBitmap !== tmpBitmap) {
                        srcBitmap?.recycle()
                    }
                }
            } ?: error("bitmap creation failed.")
        }

        suspend fun captureStill(): CaptureResult {

            val mediaProjection = mediaProjection
                ?: error("mediaProjection is null.")

            ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            ).use { imageReader ->

                bench("create imageReader")

                var virtualDisplay: VirtualDisplay? = null

                val resumeResult = withTimeoutOrNull(2000L) {
                    suspendCoroutine<String> { cont ->
                        virtualDisplay = mediaProjection.createVirtualDisplay(
                            App1.tagPrefix,
                            screenWidth,
                            screenHeight,
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
                                save(image)
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

        suspend fun capture(): CaptureResult {
            return if (isVideo) captureVideo() else captureStill()
        }
    }

    @Volatile
    var isCapturing = false

    suspend fun capture(
        context: Context,
        timeClick: Long,
        isVideo: Boolean = false
    ): CaptureResult {
        isCapturing = true
        runOnMainThread {
            CaptureServiceStill.getService()?.showButton()
            CaptureServiceVideo.getService()?.showButton()
        }
        try {
            return CaptureEnv(context, timeClick, isVideo).capture()
        } finally {
            isCapturing = false
            runOnMainThread {
                CaptureServiceStill.getService()?.showButton()
                CaptureServiceVideo.getService()?.showButton()
            }
        }
    }

    fun stopVideo() {
        videoStopper?.get()?.resume(SystemClock.elapsedRealtime())
    }


}