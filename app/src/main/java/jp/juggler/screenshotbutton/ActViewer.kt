package jp.juggler.screenshotbutton

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.MediaStore
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import jp.juggler.util.LogCategory
import jp.juggler.util.notEmpty
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.CoroutineContext


class ActViewer : AppCompatActivity(), CoroutineScope, View.OnClickListener {

    companion object {
        private val log = LogCategory("${App1.tagPrefix}/ActViewer")

        const val EXTRA_FILE_OR_URI = "fileOrUri"

        val baseUri: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        fun open(context: Context, pathOrUri: String) {
            context.startActivity(
                Intent(context, ActViewer::class.java)
                    .apply {
                        putExtra(ActViewer.EXTRA_FILE_OR_URI, pathOrUri)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
            )
        }
    }


    private lateinit var activityJob: Job

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + activityJob

    private lateinit var tvDesc: TextView
    private lateinit var ivImage: ImageView
    private var bitmap: Bitmap? = null

    private var fileOrUri:String =""

    override fun onCreate(savedInstanceState: Bundle?) {
        activityJob = Job()
        super.onCreate(savedInstanceState)
        App1.prepareAppState(this)

        initUI()
        if( savedInstanceState != null){
            load(savedInstanceState.getString(EXTRA_FILE_OR_URI))
        }else{
            load(intent?.getStringExtra(EXTRA_FILE_OR_URI))
        }
    }

    override fun onNewIntent(intent:Intent?){
        super.onNewIntent(intent)
        load(intent?.getStringExtra(EXTRA_FILE_OR_URI))
    }

    override fun onDestroy() {
        activityJob.cancel()
        ivImage.setImageDrawable(null)
        bitmap?.recycle()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle, outPersistentState: PersistableBundle) {
        super.onSaveInstanceState(outState, outPersistentState)
        outState.saveState()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.saveState()
    }

    private fun Bundle.saveState(){
        putString(EXTRA_FILE_OR_URI,fileOrUri)
    }

    override fun onBackPressed() {
        log.d("onBackPressed")
        super.onBackPressed()
        // finish()
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btnBack -> finish()

            R.id.btnDelete -> {
                MediaInfo.find(this, fileOrUri)?.let{media->
                    if (fileOrUri.startsWith("/")) File(fileOrUri).delete()
                    try {
                        contentResolver.delete(media.uri, null, null)
                    } catch (ex: SecurityException) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            (ex as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender?.let {
                                startIntentSenderForResult(it, 0, null, 0, 0, 0, null)
                                return
                            }
                        }
                        log.eToast(this, ex, "delete failed.")
                    }
                }
            }

            R.id.btnShare -> {
                try {
                    MediaInfo.find(this, fileOrUri)?.let { media ->
                        val intent = Intent(Intent.ACTION_SEND)
                        media.mimeType?.notEmpty()?.let{ intent.type = it}
                        intent.putExtra(Intent.EXTRA_STREAM, media.uri)
                        startActivity(Intent.createChooser(intent,getString(R.string.share)))
                    }
                } catch(ex : Throwable) {
                    log.eToast(this,ex,"share failed.")
                }
            }
        }
    }

    private fun initUI(){
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.act_viewer)

        findViewById<View>(R.id.btnBack).setOnClickListener(this)
        findViewById<View>(R.id.btnDelete).setOnClickListener(this)
        findViewById<View>(R.id.btnShare).setOnClickListener(this)

        ivImage = findViewById(R.id.ivImage)
        tvDesc = findViewById(R.id.tvDesc)
    }

    private fun load(spec:String?){
        if( spec?.isEmpty() !=false ) return

        fileOrUri = spec

        launch {
            try {
                tvDesc.text = "$fileOrUri\nloading…"

                val bitmap = if(spec.startsWith("/") ){
                    withContext(Dispatchers.IO) {
                        FileInputStream(File(fileOrUri))
                            .use { BitmapFactory.decodeStream(it) }
                    }
                }else{
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(Uri.parse(spec)).use{
                            BitmapFactory.decodeStream(it)
                        }
                    }
                }?: error("bitmap is null")
                if(coroutineContext.isActive){
                    ivImage.setImageBitmap(bitmap)
                    this@ActViewer.bitmap = bitmap

                    tvDesc.text = "$fileOrUri\n${bitmap.width}x${bitmap.height}"
                }
            } catch (ex: Throwable) {
                log.eToast(this@ActViewer, ex, "load failed.")
                if(coroutineContext.isActive){
                    ivImage.setImageResource(R.drawable.ic_error)
                    tvDesc.text = "$fileOrUri\nload error"
                }
            }
        }
    }
}