package jp.juggler.screenshotbutton

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import jp.juggler.util.LogCategory

class MyReceiver : BroadcastReceiver() {
    companion object {
        private val log = LogCategory("MyReceiver")
        const val ACTION_RUNNING_DELETE_STILL = "running_delete_still"
        const val ACTION_RUNNING_DELETE_VIDEO = "running_delete_video"

        const val ACTION_RUNNING_VIDEO_START = "video_start"
        const val ACTION_RUNNING_VIDEO_STOP = "video_stop"
    }

    override fun onReceive(context: Context, data: Intent?) {
        val action = data?.action
        log.i("onReceive $action")
        when (data?.action) {
            ACTION_RUNNING_DELETE_STILL ->
                context.stopService(Intent(context, CaptureServiceStill::class.java))
            ACTION_RUNNING_DELETE_VIDEO ->
                context.stopService(Intent(context, CaptureServiceVideo::class.java))
            ACTION_RUNNING_VIDEO_START ->{
                val service = CaptureServiceVideo.getService()
                if(service==null){
                    log.e("service is null.")
                }else{
                    service.captureStart()
                }
            }
            ACTION_RUNNING_VIDEO_STOP ->{
                val service = CaptureServiceVideo.getService()
                if(service==null){
                    log.e("service is null.")
                }else{
                    service.captureStop()
                }
            }
        }
    }
}
