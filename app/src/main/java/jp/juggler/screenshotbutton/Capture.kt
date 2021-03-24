package jp.juggler.screenshotbutton

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.view.Surface
import android.view.WindowManager
import jp.juggler.util.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min


object Capture {
    private val log = LogCategory("${App1.tagPrefix}/Capture")

    private const val ERROR_BLANK_IMAGE = "captured image is blank."
    private const val ERROR_STOP_BY_USER = "stop by user control."

    enum class MediaProjectionState {
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

    var screenCaptureIntent: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionAddr = AtomicReference<String?>(null)
    var mediaProjectionState = MediaProjectionState.Off

    private val videoStopReason = AtomicReference<Throwable>(null)
    private var lastEncoderOutput = 0L

    // アプリ起動時に一度だけ実行する
    fun onInitialize(context: Context) {
        log.d("onInitialize")
        mediaScannerTracker = MediaScannerTracker(context.applicationContext, handler)
        mediaProjectionManager = systemService(context)!!
        windowManager = systemService(context)!!
    }

    // mediaProjection と screenCaptureIntentを解放する
    fun release(caller: String): Boolean {
        log.d("release. caller=$caller")

        if (mediaProjection != null) {
            log.d("MediaProjection close.")
            mediaProjection?.stop()
            mediaProjection = null
        }

        screenCaptureIntent = null
        mediaProjectionState = MediaProjectionState.Off

        return false
    }

    fun startScreenCaptureIntent(activity: Activity, requestCode: Int) {
        log.d("createScreenCaptureIntent")
        val permissionIntent = mediaProjectionManager.createScreenCaptureIntent()
        activity.startActivityForResult(permissionIntent, requestCode)
        mediaProjectionState = MediaProjectionState.RequestingScreenCaptureIntent
    }

    fun prepareScreenCaptureIntent(activity: Activity, requestCode: Int): Boolean {
        log.d("prepareScreenCaptureIntent")
        return when (mediaProjectionState) {
            MediaProjectionState.HasMediaProjection,
            MediaProjectionState.HasScreenCaptureIntent -> true

            MediaProjectionState.RequestingScreenCaptureIntent -> false

            MediaProjectionState.Off -> {
                startScreenCaptureIntent(activity, requestCode)
                false
            }
        }
    }

    fun handleScreenCaptureIntentResult(
        context: Context,
        resultCode: Int,
        data: Intent?
    ): Boolean {
        log.d("handleScreenCaptureIntentResult")
        return when {
            resultCode != Activity.RESULT_OK -> {
                log.eToast(context, false, "permission not granted.")
                release("handleScreenCaptureIntentResult: permission not granted.")
            }

            data == null -> {
                log.eToast(context, false, "result data is null.")
                release("handleScreenCaptureIntentResult: intent is null.")
            }

            else -> {
                log.i("screenCaptureIntent set!")
                // 得られたインテントはExtrasにBinderProxyオブジェクトを含む。ファイルに保存とかは無理っぽい…
                screenCaptureIntent = data
                mediaProjectionState = MediaProjectionState.HasScreenCaptureIntent
                true
            }
        }
    }

    fun canCapture() =
        mediaProjection != null && mediaProjectionAddr.get() != null

    class ScreenCaptureIntentError(msg: String) :IllegalStateException(msg)

    // throw error if failed.
    fun updateMediaProjection(caller: String) {
        log.d("updateMediaProjection caller=$caller")

        val screenCaptureIntent = this.screenCaptureIntent
        if (screenCaptureIntent == null) {
            release("updateMediaProjection: screenCaptureIntent is null")
            throw ScreenCaptureIntentError("screenCaptureIntent is null")
        }

        // 以前のMediaProjectionを停止させないとgetMediaProjectionはエラーを返す
        mediaProjection?.stop()

        // MediaProjectionの取得
        val mediaProjection =
            mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, screenCaptureIntent)
        this.mediaProjection = mediaProjection

        if (mediaProjection == null) {
            release("updateMediaProjection: getMediaProjection returns null.")
            throw ScreenCaptureIntentError("getMediaProjection() returns null")
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
                    videoStopReason.compareAndSet(
                        null,
                        java.lang.RuntimeException("MediaProjection stopped.")
                    )
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



    ////////////////////////////////////////////////////////////

    private var videoCodecInfo: MediaCodecInfoAndType? = null
    private var videoFrameRate: Int = 0
    private var videoBitRate: Int = 0

    fun createVideoCodec(width: Int, height: Int): MediaCodec {
        val videoCodecInfo = videoCodecInfo ?: error("videoCodecInfo is null.")

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
        return MediaCodec.createEncoderByType(mimeType).apply {
            configure(
                format,
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
        }
    }

    // load video settings from pref to variable.
    fun loadVideoSetting(context: Context, pref: SharedPreferences) {
        val sv = Pref.spCodec(pref)
        val videoCodecInfo = MediaCodecInfoAndType.getList(context).find {
            "${it.type} ${it.info.name}" == sv
        } ?: error("Can't find specified codec. $sv")
        this.videoCodecInfo = videoCodecInfo

        videoFrameRate = Pref.spFrameRate(pref).toIntOrNull()
            ?: error("(Frame rate) Please input integer.")
        var range = videoCodecInfo.vc.supportedFrameRates
        if (!range.contains(videoFrameRate))
            error("(Frame rate) $videoFrameRate is not in ${range.lower}..${range.upper}.")

        videoBitRate = Pref.spBitRate(pref).toIntOrNull()
            ?: error("(Bit rate) Please input integer.")
        range = videoCodecInfo.vc.bitrateRange
        if (!range.contains(videoBitRate))
            error("(Bit rate) $videoBitRate is not in ${range.lower}..${range.upper}.")

        val realSize = getScreenSize(context)
        val longside = max(realSize.x, realSize.y)
        if (longside > videoCodecInfo.maxSize)
            error("current screen size is ${longside}px, but selected video codec has size limit ${videoCodecInfo.maxSize}px.")

        val codec = createVideoCodec(realSize.x, realSize.y)
        codec.release()
    }

    ////////////////////////////////////////////////////////////

    data class CaptureResult(
        val documentUri: Uri,
        val mimeType: String,
        val mediaUri: Uri?
    )

    private class CaptureFile(val context: Context, mimeType: String){

        private val documentUri :Uri?
        private val file:File?

        init{
            if(  Pref.useScopedSaveFolder  ){
                documentUri =null
                file = generateFile(
                    context,
                    Pref.spScopedSaveFolder(App1.pref),
                    getCurrentTimeString(),
                    mimeType
                )
            }else{
                file=null
                documentUri = generateDocument(
                    context,
                    Uri.parse(Pref.spSaveTreeUri(App1.pref)),
                    getCurrentTimeString(),
                    mimeType
                )
            }
        }

        val uri: Uri
            get() = documentUri?.let{ return it} ?: Uri.fromFile(file!!)

        val path : String?
            get(){
                file?.let{ return it.canonicalPath}
                return if( Build.VERSION.SDK_INT >= 30)
                    null
                else
                    documentUri?.let{ pathFromDocumentUri(context, it)}
            }

        fun openFileDescriptor(): ParcelFileDescriptor? =
            documentUri?.let{ context.contentResolver.openFileDescriptor(it, "w") }
                ?: ParcelFileDescriptor.open(file!!, ParcelFileDescriptor.MODE_WRITE_ONLY)

        fun delete(): Boolean =
            documentUri?.let{ deleteDocument(context, it) }
                ?: file!!.delete()


        fun openOutputStream(): OutputStream =
            documentUri?.let{ context.contentResolver.openOutputStream(it)}
                ?: FileOutputStream(file!!)

        override fun toString(): String =
            documentUri?.toString() ?: file!!.canonicalPath
    }

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
            val realSize = getScreenSize(context)

            screenWidth = realSize.x
            screenHeight = realSize.y
            densityDpi = context.resources.displayMetrics.densityDpi

            if (!canCapture())
                updateMediaProjection("CaptureEnv.ctor")

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

            val captureFile = CaptureFile(context, mimeTypeFile)

            bench("generateDocument")

            try {
                captureFile.openFileDescriptor()
                    ?.use { parcelFileDescriptor ->
                        try {
                            bench("openFileDescriptor")

                            videoStopReason.set(null)

                            val videoCodec = createVideoCodec(screenWidth, screenHeight)
                            bench("createVideoCodec")

                            this.videoCodec = videoCodec
                            videoCodec.setCallback(object : MediaCodec.Callback() {

                                override fun onError(
                                    codec: MediaCodec,
                                    ex: MediaCodec.CodecException
                                ) {
                                    log.e(ex, "MediaCodec.Callback onError. codec=${codec.name}")
                                    videoStopReason.compareAndSet(null, ex)
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
                                        videoStopReason.compareAndSet(null, ex)
                                    }
                                }

                                override fun onOutputBufferAvailable(
                                    codec: MediaCodec,
                                    index: Int,
                                    info: MediaCodec.BufferInfo
                                ) {
                                    //大量に出る log.d("onOutputBufferAvailable: index=$index, presentationTimeUs=${info.presentationTimeUs}")

                                    lastEncoderOutput = SystemClock.elapsedRealtime()
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
                                        videoStopReason.compareAndSet(null, ex)
                                    }
                                }
                            })

                            inputSurface = videoCodec.createInputSurface()
                            bench("videoCodec.createInputSurface")

                            videoCodec.start()
                            bench("videoCodec.start")

                            @Suppress("BlockingMethodInNonBlockingContext")
                            muxer = MediaMuxer(
                                parcelFileDescriptor.fileDescriptor,
                                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                            )
                            bench("MediaMuxer ctor")

                            var virtualDisplayResumed = false
                            fun setResumed(resumed: Boolean) {
                                lastEncoderOutput = SystemClock.elapsedRealtime()
                                virtualDisplayResumed = resumed
                                runOnMainThread {
                                    try {
                                        videoCodec.suspend(!resumed)
                                    }catch (ex: Throwable){
                                        log.e(ex, "suspend failed.")
                                    }
                                }
                            }

                            setResumed(false)

                            // Start the video input.
                            virtualDisplay = mediaProjection.createVirtualDisplay(
                                App1.tagPrefix,
                                screenWidth,
                                screenHeight,
                                densityDpi,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                                inputSurface,
                                object : VirtualDisplay.Callback() {
                                    override fun onResumed() {
                                        super.onResumed()
                                        log.e("VirtualDisplay.Callback onResumed")
                                        setResumed(true)
                                    }

                                    override fun onPaused() {
                                        super.onPaused()
                                        log.e("VirtualDisplay.Callback onPaused")
                                        setResumed(false)
                                    }

                                    override fun onStopped() {
                                        super.onStopped()
                                        log.e("VirtualDisplay.Callback onStopped")
                                        videoStopReason.compareAndSet(
                                            null,
                                            RuntimeException("VirtualDisplay onStopped")
                                        )
                                    }
                                },
                                handler
                            )

                            bench("createVirtualDisplay")
                            if(Build.VERSION.SDK_INT>=30){
                                setResumed(true)
                            }

                            // TODO : 画面オフとか画面回転とか画面のリサイズとかが発生したらどうなる？
                            var maxDelay = 0L
                            while (true) {
                                if (virtualDisplayResumed) {
                                    val delay = SystemClock.elapsedRealtime() - lastEncoderOutput
                                    if (delay > maxDelay) {
                                        maxDelay = delay
                                        log.d("delay: $maxDelay")
                                    }
                                    if (delay >= 20000L) {
                                        videoStopReason.compareAndSet(
                                            null,
                                            RuntimeException("encoder output timeout.")
                                        )
                                    }
                                }

                                val stopReason = videoStopReason.get()
                                if (stopReason != null) {
                                    if (stopReason.message?.contains(ERROR_STOP_BY_USER) == true) {
                                        log.e("cancelled by user.")
                                    } else {
                                        log.eToast(context, stopReason, "error.")
                                    }
                                    break
                                }

                                delay(333L)
                            }
                            bench("loop end.")
                        } finally {
                            releaseEncoders()
                        }
                    }
            } catch (ex: Throwable) {
                try {
                    if(! captureFile.delete())
                        log.e("deleteDocument returns false.")
                } catch (ex2: Throwable) {
                    log.e(ex2, "deleteDocument failed.")
                }
                throw ex
            }

            var mediaUri: Uri? = null
            captureFile.path?.let { path ->
                mediaScannerTracker.scanAndWait(path, mimeTypeFile)?.let { mediaUri = it }
            }
            bench("media scan: $mediaUri")

            return CaptureResult(
                documentUri = captureFile.uri,
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

                    val captureFile = CaptureFile(context,mimeType)
                    try {
                        captureFile.openOutputStream()

                            .use { outputStream ->
                                bench("before compress")
                                srcBitmap.compress(
                                    compressFormat,
                                    compressQuality,
                                    outputStream
                                )
                                bench("compress and write to $captureFile")
                            }
                    } catch (ex: Throwable) {
                        try {
                            if (! captureFile.delete())
                                log.e("deleteDocument returns false.")
                        } catch (ex2: Throwable) {
                            log.e(ex2, "deleteDocument failed.")
                        }
                        throw ex
                    }

                    var mediaUri: Uri? = null
                    captureFile.path?.let{ path->
                        mediaScannerTracker.scanAndWait(path, mimeType)
                            ?.let {  mediaUri = it }
                    }

                    bench("media scan: $mediaUri")

                    CaptureResult(
                        documentUri = captureFile.uri,
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

        @SuppressLint("WrongConstant")
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

                val resumeResult = withTimeoutOrNull(20000L) {
                    suspendCancellableCoroutine<String> { cont ->
                        val vd = mediaProjection.createVirtualDisplay(
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
                                    try {
                                        cont.resume("OK")
                                    }catch(ex:Throwable){
                                        log.e(ex,"resume failed.")
                                    }
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
                        virtualDisplay = vd
                        bench("virtualDisplay created. waiting onResumed…")
                        if(Build.VERSION.SDK_INT>=30){
                            cont.resume("OK")
                        }
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
        CaptureServiceBase.showButtonAll()
        try {
            return CaptureEnv(context, timeClick, isVideo).capture()
        } finally {
            isCapturing = false
            CaptureServiceBase.showButtonAll()
        }
    }

    fun stopVideo() {
        videoStopReason.compareAndSet(null, RuntimeException(ERROR_STOP_BY_USER))
    }
}