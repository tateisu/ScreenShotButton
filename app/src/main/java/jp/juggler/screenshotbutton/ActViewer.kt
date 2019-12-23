package jp.juggler.screenshotbutton

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import jp.juggler.util.*
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

class ActViewer : AppCompatActivity(), CoroutineScope, View.OnClickListener {

    companion object {
        private val log = LogCategory("${App1.tagPrefix}/ActViewer")

        const val EXTRA_URI = "uri"

        fun open(context: Context, uri: Uri) {
            context.startActivity(
                Intent(context, ActViewer::class.java)
                    .apply {
                        data = uri
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
            )
        }

        private fun delete(context: Context, uri: Uri?) {
            uri ?: return

            when {
                isExternalStorageDocument(uri) -> {
                    if(!deleteDocument(context,uri)) error("deleteDocument returns false")
                }

                else -> {
                    // may MediaStore content url
                    findMedia(context, uri)?.let { media ->
                        val count = context.contentResolver.delete(media.uri, null, null)
                        if (count != 1) error("delete() returns $count")
                        return
                    }
                    error("missing media for the uri.")
                }
            }
            // contentResolver.delete() throws SecurityException on API 29 device.
            // val count = contentResolver.delete(, null, null)
            // if(count <= 0) error("delete returns $count")
            // java.lang.SecurityException: jp.juggler.screenshotbutton has no access to content://media/external_primary/images/media/698
            // at android.os.Parcel.createException(Parcel.java:2071)
            // at android.os.Parcel.readException(Parcel.java:2039)
            // at android.database.DatabaseUtils.readExceptionFromParcel(DatabaseUtils.java:188)
            // at android.database.DatabaseUtils.readExceptionFromParcel(DatabaseUtils.java:140)
            // at android.content.ContentProviderProxy.delete(ContentProviderNative.java:553)
            // at android.content.ContentResolver.delete(ContentResolver.java:1949)

            // MediaStore.getDocumentUri() throws SecurityException on API 29 Device.
            // val documentUri = MediaStore.getDocumentUri(this,Uri.parse(fileOrUri))
            // log.d("documentUri=$documentUri")
            // java.lang.SecurityException: The app is not given any access to the document under path /storage/emulated/0/Pictures/ScreenShotButton/20191223-092119.jpg with permissions granted in []
            // at android.os.Parcel.createException(Parcel.java:2071)
            // at android.os.Parcel.readException(Parcel.java:2039)
            // at android.database.DatabaseUtils.readExceptionFromParcel(DatabaseUtils.java:188)
            // at android.database.DatabaseUtils.readExceptionFromParcel(DatabaseUtils.java:140)
            // at android.content.ContentProviderProxy.call(ContentProviderNative.java:658)
            // at android.content.ContentProviderClient.call(ContentProviderClient.java:558)
            // at android.content.ContentProviderClient.call(ContentProviderClient.java:546)
            // at android.provider.MediaStore.getDocumentUri(MediaStore.java:3471)
        }

        private fun share(context: Context, uri: Uri?) {
            uri ?: return

            fun actionSend(uri: Uri, mimeTypeArg: String?) {
                val mimeType = mimeTypeArg ?: context.contentResolver.getType(uri)
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            putExtra(Intent.EXTRA_STREAM, uri)
                            if (mimeType != null) type = mimeType
                        },
                        context.getString(R.string.share)
                    )
                )
            }

            when {
                isExternalStorageDocument(uri) ->
                    actionSend(uri, DocumentFile.fromSingleUri(context, uri)?.type)

                else -> {
                    findMedia(context, uri)?.let { media ->
                        return actionSend(media.uri, media.mimeType)
                    }
                    log.eToast(context, true, "can't find media uri for $uri")
                }
            }
        }
    }

    private lateinit var activityJob: Job

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + activityJob

    private lateinit var tvDesc: TextView
    private lateinit var ivImage: ImageView
    private var bitmap: Bitmap? = null

    private var lastUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        activityJob = Job()
        super.onCreate(savedInstanceState)
        App1.prepareAppState(this)

        initUI()
        if (savedInstanceState != null) {
            savedInstanceState.getString(EXTRA_URI)?.let { load(Uri.parse(it)) }
        } else {
            intent?.data?.let { load(it) }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { load(it) }
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

    private fun Bundle.saveState() {
        lastUri?.let { putString(EXTRA_URI, it.toString()) }
    }

    override fun onBackPressed() {
        log.d("onBackPressed")
        super.onBackPressed()
        // finish()
    }


    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btnBack -> finish()

            R.id.btnDelete -> try {
                delete(this, lastUri)
                finish()
            } catch (ex: Throwable) {
                log.eToast(this, ex, "delete failed. $lastUri")
            }


            R.id.btnShare -> try {
                share(this, lastUri)
            } catch (ex: Throwable) {
                log.eToast(this, ex, "share failed. $lastUri")
            }
        }
    }

    private fun initUI() {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.act_viewer)

        findViewById<View>(R.id.btnBack).setOnClickListener(this)
        findViewById<View>(R.id.btnDelete).setOnClickListener(this)
        findViewById<View>(R.id.btnShare).setOnClickListener(this)

        ivImage = findViewById(R.id.ivImage)
        tvDesc = findViewById(R.id.tvDesc)
    }

    @SuppressLint("SetTextI18n")
    private fun load(uri: Uri) {

        this.lastUri = uri

        val path = pathFromDocumentUri(this, uri) ?: error("")

        launch {
            try {
                tvDesc.text = "loading…\n$path"

                val bitmap = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri).use {
                        BitmapFactory.decodeStream(it)
                    }
                }
                    ?: error("bitmap is null")
                if (coroutineContext.isActive) {
                    ivImage.setImageBitmap(bitmap)
                    this@ActViewer.bitmap = bitmap

                    tvDesc.text = "${bitmap.width}x${bitmap.height}\n$path"
                }
            } catch (ex: Throwable) {
                log.eToast(this@ActViewer, ex, "load failed.")
                if (coroutineContext.isActive) {
                    ivImage.setImageResource(R.drawable.ic_error)
                    tvDesc.text = "load error\n$path"
                }
            }
        }
    }
}