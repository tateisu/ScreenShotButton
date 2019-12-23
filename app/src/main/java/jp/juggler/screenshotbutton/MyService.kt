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
        private var refService: WeakReference<MyService>? = null

        fun getService() = refService?.get()

        private val log = LogCategory("${App1.tagPrefix}/MyService")
    }

    private val notificationManager by
    lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    private val windowManager by
    lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private val layoutInflater by
    lazy { LayoutInflater.from(this) }

    private val viewRoot by
    lazy { layoutInflater.inflate(R.layout.service_overlay, null) }


    private lateinit var serviceJob: Job

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + serviceJob

    private lateinit var layoutParam: WindowManager.LayoutParams

    private lateinit var btnCamera: MyImageButton


    private var startLpX = 0
    private var startLpY = 0
    private var startMotionX = 0f
    private var startMotionY = 0f
    private var isDragging = false
    private var draggingThreshold = 0f
    private var maxX = 0
    private var maxY = 0
    private var buttonSize = 0

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStart(intent: Intent, startId: Int) {
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    private fun createRunningNotification(): Notification {

        if (Build.VERSION.SDK_INT >= 26) {
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

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.notification_icon1)
            .setContentTitle(getString(R.string.capture_standby))
            .setContentText(getString(R.string.capture_standby_description))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    PI_CODE_RUNNING_TAP,
                    Intent(this, ActMain::class.java)
                        .apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        },
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    this,
                    PI_CODE_RUNNING_DELETE,
                    Intent(this, MyReceiver::class.java)
                        .apply { action = MyReceiver.ACTION_RUNNING_DELETE },
                    PendingIntent.FLAG_UPDATE_CURRENT
                )

            )
            .build()
    }

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    override fun onCreate() {
        refService = WeakReference(this)
        ActMain.getActivity()?.showButton()
        super.onCreate()
        App1.prepareAppState(this)
        serviceJob = Job()

        startForeground(NOTIFICATION_ID_RUNNING, createRunningNotification())

        val dm = resources.displayMetrics

        this.buttonSize = Pref.ipCameraButtonSize(App1.pref).toFloat().dp2px(dm)

        val buttonX = clipInt(
            0,
            dm.widthPixels - buttonSize,
            Pref.fpCameraButtonX(App1.pref).dp2px(dm)
        )
        val buttonY = clipInt(
            0,
            dm.heightPixels - buttonSize,
            Pref.fpCameraButtonY(App1.pref).dp2px(dm)
        )

        btnCamera = viewRoot.findViewById(R.id.btnCamera)
        btnCamera.setOnClickListener(this)
        btnCamera.setOnTouchListener(this)

        layoutParam = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            if (Build.VERSION.SDK_INT >= 26) {
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
            x = buttonX
            y = buttonY
        }
        btnCamera.windowLayoutParams = layoutParam
        windowManager.addView(viewRoot, layoutParam)

        if (!Capture.updateMediaProjection(this)) stopSelf()
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
        if (!Capture.updateMediaProjection(this)) stopSelf()
    }

    //////////////////////////////////////////////
    // 撮影ボタンをドラッグして移動できるようにする

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
            R.id.btnCamera -> {
                launch {
                    try {
                        if (!Capture.canCapture()) {
                            if (!Capture.updateMediaProjection(this@MyService)) return@launch
                        }

                        btnCamera.visibility = View.GONE

                        val pathOrUri = Capture.capture(this@MyService, timeClick)

                        if (Pref.bpShowPostView(App1.pref)) {
                            ActViewer.open(this@MyService, pathOrUri)
                        }
                    } catch (ex: Throwable) {
                        log.eToast(this@MyService, ex, "capture failed.")
                    } finally {
                        btnCamera.visibility = View.VISIBLE
                        setButtonDrawable(true)
                    }
                }
            }
        }
    }

}