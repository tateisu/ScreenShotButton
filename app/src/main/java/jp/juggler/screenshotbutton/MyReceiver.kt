package jp.juggler.screenshotbutton

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MyReceiver : BroadcastReceiver() {
    companion object{
        const val ACTION_RUNNING_DELETE="running_delete"
    }

    override fun onReceive(context: Context, data: Intent?) {
        when(data?.action){
            ACTION_RUNNING_DELETE-> context.stopService(Intent(context,MyService::class.java))
        }
    }

}
