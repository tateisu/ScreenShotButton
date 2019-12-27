package jp.juggler.screenshotbutton

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import jp.juggler.util.runOnMainThread
import java.lang.ref.WeakReference


@SuppressLint("InflateParams")
class CaptureServiceVideo : CaptureServiceBase(
    Pref.fpCameraButtonXVideo,
    Pref.fpCameraButtonYVideo,
    startButtonId = R.drawable.ic_videocam,
    notificationId = NOTIFICATION_ID_RUNNING_VIDEO
) {

    companion object {
        // private val log = LogCategory("CaptureServiceVideo")

        private var refService: WeakReference<CaptureServiceVideo>? = null
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

    override fun arrangeNotification(
        builder: NotificationCompat.Builder,
        isRecording: Boolean
    ): IntArray {

        val closeIntent = PendingIntent.getBroadcast(
            context,
            PI_CODE_RUNNING_DELETE_VIDEO,
            Intent(context, MyReceiver::class.java)
                .apply { action = MyReceiver.ACTION_RUNNING_DELETE_VIDEO },
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        builder
            .setSmallIcon(R.drawable.notification_videocam)
            .setContentTitle(getString(if (isRecording) R.string.capture_standby_video_recording else R.string.capture_standby_video))
            .setContentText(getString(if (isRecording) R.string.capture_standby_description_video_recording else R.string.capture_standby_description_video))
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
                if (isRecording) {
                    NotificationCompat.Action(
                        R.drawable.ic_stop,
                        getString(R.string.stop),
                        PendingIntent.getBroadcast(
                            context,
                            PI_CODE_VIDEO_STOP,
                            Intent(context, MyReceiver::class.java)
                                .apply { action = MyReceiver.ACTION_RUNNING_VIDEO_STOP },
                            PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                } else {
                    NotificationCompat.Action(
                        R.drawable.ic_record,
                        getString(R.string.record),
                        PendingIntent.getBroadcast(
                            context,
                            PI_CODE_VIDEO_START,
                            Intent(context, MyReceiver::class.java)
                                .apply { action = MyReceiver.ACTION_RUNNING_VIDEO_START },
                            PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_close,
                    getString(R.string.close),
                    closeIntent
                )
            )

        // returns array of int that means action button index
        return intArrayOf(0, 1)
    }

    override suspend fun performCapture(timeClick: Long) {
        val (_, mimeType, mediaUri) = Capture.capture(context, timeClick, isVideo = true)
        runOnMainThread {
            if (!isDestroyed && Pref.bpShowPostView(App1.pref) && mediaUri != null) {
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            setDataAndType(mediaUri, mimeType)
                        }
                )
            }
        }
    }
}