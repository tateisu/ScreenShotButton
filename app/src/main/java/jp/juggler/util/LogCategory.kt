package jp.juggler.util

import android.content.Context
import android.util.Log
import android.widget.Toast

@Suppress("unused")
class LogCategory(private val tag: String) {
    companion object{

        private var lastToast : Toast? = null

        private fun showToast(
            context: Context,
            bLong:Boolean,
            msg:String
        ){
            synchronized(this){
                lastToast?.cancel()
                val toast = Toast.makeText(context,msg,when{
                    bLong -> Toast.LENGTH_LONG
                    else -> Toast.LENGTH_SHORT
                })
                toast.show()
                lastToast = toast
            }
        }

        private fun showToast(
            context: Context,
            ex:Throwable,
            msg:String
        ){
            synchronized(this){
                lastToast?.cancel()
                val toast = Toast.makeText(context,msg+": ${ex.javaClass.simpleName} ${ex.message}",Toast.LENGTH_LONG)
                toast.show()
                lastToast = toast
            }
        }
    }

    fun v(msg: String) = Log.v(tag, msg)
    fun d(msg: String) = Log.d(tag, msg)
    fun i(msg: String) = Log.i(tag, msg)
    fun w(msg: String) = Log.w(tag, msg)
    fun e(msg: String) = Log.e(tag, msg)

    fun v(ex: Throwable, msg: String) = Log.v(tag, msg, ex)
    fun d(ex: Throwable, msg: String) = Log.d(tag, msg, ex)
    fun i(ex: Throwable, msg: String) = Log.i(tag, msg, ex)
    fun w(ex: Throwable, msg: String) = Log.w(tag, msg, ex)
    fun e(ex: Throwable, msg: String) = Log.e(tag, msg, ex)

    fun vToast(context:Context,bLong:Boolean,msg: String){
        Log.v(tag, msg)
        showToast(context,bLong,msg)
    }
    fun dToast(context:Context,bLong:Boolean,msg: String){
        Log.d(tag, msg)
        showToast(context,bLong,msg)
    }
    fun iToast(context:Context,bLong:Boolean,msg: String){
        Log.i(tag, msg)
        showToast(context,bLong,msg)
    }
    fun wToast(context:Context,bLong:Boolean,msg: String){
        Log.w(tag, msg)
        showToast(context,bLong,msg)
    }
    fun eToast(context:Context,bLong:Boolean,msg: String){
        Log.e(tag, msg)
        showToast(context,bLong,msg)
    }

    fun vToast(context:Context,ex: Throwable, msg: String){
        Log.v(tag, msg, ex)
        showToast(context,ex,msg)
    }
    fun dToast(context:Context,ex: Throwable, msg: String){
        Log.d(tag, msg, ex)
        showToast(context,ex,msg)
    }
    fun iToast(context:Context,ex: Throwable, msg: String){
        Log.i(tag, msg, ex)
        showToast(context,ex,msg)
    }
    fun wToast(context:Context,ex: Throwable, msg: String){
        Log.w(tag, msg, ex)
        showToast(context,ex,msg)
    }
    fun eToast(context:Context,ex: Throwable, msg: String){
        Log.e(tag, msg, ex)
        showToast(context,ex,msg)
    }

}