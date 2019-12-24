package jp.juggler.screenshotbutton

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.*
import androidx.core.app.NotificationCompat
import jp.juggler.util.LogCategory
import jp.juggler.util.clipInt
import jp.juggler.util.dp2px
import jp.juggler.util.px2dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("InflateParams")
class MyService : Service(), CoroutineScope, View.OnClickListener, View.OnTouchListener {

    companion object {
        private val log = LogCategory("${App1.tagPrefix}/MyService")

        private var refService: WeakReference<MyService>? = null

        fun getService() = refService?.get()
    }

    private val context = this

    private lateinit var notificationManager: NotificationManager
    private lateinit var windowManager: WindowManager

    private lateinit var btnCamera: MyImageButton
    private lateinit var layoutParam: WindowManager.LayoutParams
    private lateinit var serviceJob: Job
    private lateinit var viewRoot: View

    private var startLpX = 0
    private var startLpY = 0
    private var startMotionX = 0f
    private var startMotionY = 0f
    private var isDragging = false
    private var draggingThreshold = 0f
    private var maxX = 0
    private var maxY = 0
    private var buttonSize = 0

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + serviceJob

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStart(intent: Intent, startId: Int) {
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    override fun onCreate() {
        serviceJob = Job()
        refService = WeakReference(this)

        super.onCreate()
        App1.prepareAppState(context)

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        viewRoot = LayoutInflater.from(context).inflate(R.layout.service_overlay, null)

        startForeground(NOTIFICATION_ID_RUNNING, createRunningNotification())

        btnCamera = viewRoot.findViewById(R.id.btnCamera)
        btnCamera.setOnClickListener(this)
        btnCamera.setOnTouchListener(this)

        layoutParam = WindowManager.LayoutParams(
            0, // 後で上書きされる
            0,
            if (Build.VERSION.SDK_INT >= API_APPLICATION_OVERLAY) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.LEFT or Gravity.TOP
        }

        loadButtonPosition()

        btnCamera.windowLayoutParams = layoutParam
        windowManager.addView(viewRoot, layoutParam)

        try {
            Capture.updateMediaProjection()
        } catch (ex: Throwable) {
            log.eToast(this, ex, "updateMediaProjection failed.")
            stopSelf()
            return
        }

        ActMain.getActivity()?.showButton()
    }

    override fun onDestroy() {
        refService = null
        ActMain.getActivity()?.showButton()
        serviceJob.cancel()
        Capture.release()
        windowManager.removeView(viewRoot)
        stopForeground(true)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        reloadPosition()

        try {
            Capture.updateMediaProjection()
        } catch (ex: Throwable) {
            log.eToast(this, ex, "updateMediaProjection failed.")
            stopSelf()
        }
    }

    //////////////////////////////////////////////

    // 設定からボタン位置を読み直す
    // ただし反映はされない
    private fun loadButtonPosition() {
        val dm = resources.displayMetrics

        buttonSize = Pref.ipCameraButtonSize(App1.pref).toFloat().dp2px(dm)
        layoutParam.width = buttonSize
        layoutParam.height = buttonSize

        layoutParam.x = clipInt(
            0,
            dm.widthPixels - buttonSize,
            Pref.fpCameraButtonX(App1.pref).dp2px(dm)
        )

        layoutParam.y = clipInt(
            0,
            dm.heightPixels - buttonSize,
            Pref.fpCameraButtonY(App1.pref).dp2px(dm)
        )
    }

    // UIから呼ばれる
    fun reloadPosition() {
        loadButtonPosition()
        windowManager.updateViewLayout(viewRoot, layoutParam)
        btnCamera.updateExclusion()
    }

    //////////////////////////////////////////////
    // 撮影ボタンのドラッグ操作

    private fun setButtonDrawable(showing: Boolean) {
        if (showing) {
            btnCamera.setBackgroundResource(R.drawable.btn_bg_round)
            btnCamera.setImageResource(R.drawable.ic_camera)
        } else {
            btnCamera.background = null
            btnCamera.setImageDrawable(null)
        }
    }

    private fun updateDragging(
        ev: MotionEvent,
        save: Boolean = false
    ): Boolean {
        val deltaX = ev.rawX - startMotionX
        val deltaY = ev.rawY - startMotionY
        if (!isDragging) {
            if (max(abs(deltaX), abs(deltaY)) < draggingThreshold) return false
            isDragging = true
            setButtonDrawable(true)
        }
        layoutParam.x = clipInt(0, maxX, startLpX + deltaX.toInt())
        layoutParam.y = clipInt(0, maxY, startLpY + deltaY.toInt())
        if (save) {
            val dm = resources.displayMetrics
            App1.pref.edit()
                .put(Pref.fpCameraButtonX, layoutParam.x.px2dp(dm))
                .put(Pref.fpCameraButtonY, layoutParam.y.px2dp(dm))
                .apply()
        }
        windowManager.updateViewLayout(viewRoot, layoutParam)
        btnCamera.updateExclusion()
        return true
    }

    override fun onTouch(v: View?, ev: MotionEvent): Boolean {
        if (v?.id != R.id.btnCamera) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                setButtonDrawable(false)
                val dm = resources.displayMetrics
                startLpX = layoutParam.x
                startLpY = layoutParam.y
                startMotionX = ev.rawX
                startMotionY = ev.rawY
                isDragging = false
                draggingThreshold = resources.displayMetrics.density * 8f
                maxX = dm.widthPixels - buttonSize
                maxY = dm.heightPixels - buttonSize
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateDragging(ev)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!updateDragging(ev, save = true)) {
                    v.performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!updateDragging(ev, save = true)) {
                    setButtonDrawable(true)
                }
                return true
            }
        }
        return false
    }

    ///////////////////////////////////////////////////////////////

    override fun onClick(v: View?) {
        val timeClick = SystemClock.elapsedRealtime()

        when (v?.id) {
            R.id.btnCamera -> launch {
                try {
                    btnCamera.visibility = View.GONE

                    val uri = Capture.capture(context, timeClick)

                    if (Pref.bpShowPostView(App1.pref))
                        ActViewer.open(context, uri)

                } catch (ex: Throwable) {
                    log.eToast(context, ex, "capture failed.")
                } finally {
                    btnCamera.visibility = View.VISIBLE
                    setButtonDrawable(true)
                }
            }
        }
    }

    private fun createRunningNotification(): Notification {

        if (Build.VERSION.SDK_INT >= API_NOTIFICATION_CHANNEL) {
            // Create the NotificationChannel
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_RUNNING,
                    getString(R.string.capture_standby),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.capture_standby_description)
                }
            )
        }

        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.notification_icon1)
            .setContentTitle(getString(R.string.capture_standby))
            .setContentText(getString(R.string.capture_standby_description))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    PI_CODE_RUNNING_TAP,
                    Intent(context, ActMain::class.java)
                        .apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        },
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    PI_CODE_RUNNING_DELETE,
                    Intent(context, MyReceiver::class.java)
                        .apply { action = MyReceiver.ACTION_RUNNING_DELETE },
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

}