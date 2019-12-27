package jp.juggler.screenshotbutton

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.lang.ref.WeakReference

@SuppressLint("InflateParams")
class CaptureServiceStill : CaptureServiceBase(
    Pref.fpCameraButtonXStill,
    Pref.fpCameraButtonYStill,
    startButtonId = R.drawable.ic_camera,
    notificationId = NOTIFICATION_ID_RUNNING_STILL
) {


    companion object {
        private var refService: WeakReference<CaptureServiceStill>? = null
        fun getService() = refService?.get()
    }

    override fun onCreate() {
        // 順序に注意。常にrefServiceの変更が先
        refService = WeakReference(this)
        super.onCreate()
    }

    override fun onDestroy() {
        // 順序に注意。常にrefServiceの変更が先
        refService = null
        super.onDestroy()
    }

    override fun createNotificationChannel() :String{
        if (Build.VERSION.SDK_INT >= API_NOTIFICATION_CHANNEL) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_STILL,
                    getString(R.string.capture_standby_still),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.capture_standby_description_still)
                }
            )
        }

        return NOTIFICATION_CHANNEL_STILL
    }

    override fun arrangeNotification(
        builder: NotificationCompat.Builder,
        isRecording: Boolean
    ): IntArray {

        val closeIntent = PendingIntent.getBroadcast(
            context,
            PI_CODE_RUNNING_DELETE_STILL,
            Intent(context, MyReceiver::class.java)
                .apply { action = MyReceiver.ACTION_RUNNING_DELETE_STILL },
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        builder
            .setSmallIcon(R.drawable.notification_icon1)
            .setContentTitle(getString(R.string.capture_standby_still))
            .setContentText(getString(R.string.capture_standby_description_still))
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
                closeIntent
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_close,
                    getString(R.string.close),
                    closeIntent
                )
            )

        // returns array of int that means action button index
        return intArrayOf(0)
    }

    override fun afterCapture(captureResult: Capture.CaptureResult){
        if (!isDestroyed && Pref.bpShowPostView(App1.pref)) {
            ActViewer.open(context, captureResult.documentUri)
        }
    }
}