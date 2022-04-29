package jp.juggler.screenshotbutton

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class CaptureServiceStill : CaptureServiceBase(isVideo = false) {
    companion object {

        fun getService() = getServices().find { it is CaptureServiceStill }

        fun isAlive() = getService() != null
    }

    override fun createNotificationChannel(channelId: String) {
        if (Build.VERSION.SDK_INT >= API_NOTIFICATION_CHANNEL) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.capture_standby_still),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.capture_standby_description_still)
                }
            )
        }
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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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

    override fun openPostView(captureResult: Capture.CaptureResult) {
        if (!isDestroyed) {
            ActViewer.open(context, captureResult.documentUri)
        }
    }
}
