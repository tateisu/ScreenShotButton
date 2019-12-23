package jp.juggler.screenshotbutton

import android.annotation.SuppressLint
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
import androidx.documentfile.provider.DocumentFile
import jp.juggler.util.*
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
                        putExtra(EXTRA_FILE_OR_URI, pathOrUri)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
            )
        }

        private fun delete(context: Context, src: String): Boolean {

            if (src.startsWith('/')) {
                val file = File(src)
                if (!file.delete() && file.exists())
                    error("File.delete() failed, but exists.")

                // ファイルを消した場合、MediaStore中の項目も削除を試みる
                // ただし処理は止めない
                try {
                    MediaInfo.find(context, src)?.let { media ->
                        val count = context.contentResolver.delete(media.uri, null, null)
                        if (count != 1) log.e("delete() returns $count")
                    }
                } catch (ex: Throwable) {
                    log.e(ex, "MediaStore deletion failed.")
                }
                return true
            }

            val uri = Uri.parse(src)

            // file://
            if (uri.authority == "file") {
                val path = uri.path ?: error("missing path.")
                return delete(context, path)
            }

            if (Build.VERSION.SDK_INT >= API_USE_DOCUMENT) {
                if (isExternalStorageDocument(uri)) {
                    val df = DocumentFile.fromSingleUri(context, uri)
                        ?: error("DocumentFile.fromSingleUri() returns null.")
                    if (!df.delete() && df.exists()) error("can't delete, but exists.")
                    return true
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

            // may MediaStore content url
            MediaInfo.find(context, src)?.let { media ->
                val count = context.contentResolver.delete(media.uri, null, null)
                if (count != 1) error("delete() returns $count")
                return true
            }
            error("missing media for the uri.")
        }

        private fun shareMediaByUri(
            context: Context,
            uri:Uri,
            mimeType :String? = context.contentResolver.getType(uri)
        ) {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                if(mimeType!=null) type = mimeType
            }, context.getString(R.string.share)))
        }

        private fun shareMediaByPath(context: Context, path: String) {
            MediaInfo.find(context, path)?.let { media ->
                return shareMediaByUri(context,media.uri,media.mimeType)
            }
            log.eToast(context, true, "can't find media uri for $path")
        }

        private fun share(context: Context, src: String) {

            if (src.startsWith('/')) {
                return shareMediaByPath(context, src)
            }

            // may file:// uri
            val uri = Uri.parse(src)
            if (uri.authority == "file") {
                return shareMediaByPath(context, uri.path ?: error("missing path."))
            }

            if (Build.VERSION.SDK_INT >= API_USE_DOCUMENT) {
                if (isExternalStorageDocument(uri)) {
                    return shareMediaByUri(context,uri,DocumentFile.fromSingleUri(context, uri)?.type)
                }
            }

            return shareMediaByPath(context, src)
        }
    }


    private lateinit var activityJob: Job

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + activityJob

    private lateinit var tvDesc: TextView
    private lateinit var ivImage: ImageView
    private var bitmap: Bitmap? = null

    private var fileOrUri: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        activityJob = Job()
        super.onCreate(savedInstanceState)
        App1.prepareAppState(this)

        initUI()
        if (savedInstanceState != null) {
            load(savedInstanceState.getString(EXTRA_FILE_OR_URI))
        } else {
            load(intent?.getStringExtra(EXTRA_FILE_OR_URI))
        }
    }

    override fun onNewIntent(intent: Intent?) {
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

    private fun Bundle.saveState() {
        putString(EXTRA_FILE_OR_URI, fileOrUri)
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
                if (delete(this, fileOrUri)) finish()
            } catch (ex: Throwable) {
                log.eToast(this, ex, "delete failed. $fileOrUri")
            }


            R.id.btnShare -> try {
                share(this, fileOrUri)
            } catch (ex: Throwable) {
                log.eToast(this, ex, "share failed. $fileOrUri")
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
    private fun load(spec: String?) {
        if (spec?.isEmpty() != false) return

        fileOrUri = spec

        val path =  pathFromDocumentUri(this, fileOrUri) ?: fileOrUri

        launch {
            try {
                tvDesc.text = "loading…\n$path"

                val bitmap = if (spec.startsWith("/")) {
                    withContext(Dispatchers.IO) {
                        FileInputStream(File(fileOrUri))
                            .use { BitmapFactory.decodeStream(it) }
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(Uri.parse(spec)).use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                } ?: error("bitmap is null")
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