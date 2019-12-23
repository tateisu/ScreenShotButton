package jp.juggler.util

import android.annotation.TargetApi
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import jp.juggler.screenshotbutton.MediaInfo

@Suppress("unused")
private val log = LogCategory("StorageUtils")

fun isExternalStorageDocument(uri: Uri): Boolean {
    return "com.android.externalstorage.documents" == uri.authority
}

private const val PATH_TREE = "tree"
private const val PATH_DOCUMENT = "document"

// throw error if can'f find ID
private fun getDocumentId(documentUri: Uri): String {
    val paths = documentUri.pathSegments
    if (paths.size >= 2 && PATH_DOCUMENT == paths[0]) {
        // document
        return paths[1]
    }

    if (paths.size >= 4 && PATH_TREE == paths[0]
        && PATH_DOCUMENT == paths[2]
    ) {
        // document in tree
        return paths[3]
    }

    if (paths.size >= 2 && PATH_TREE == paths[0]) {
        // tree
        return paths[1]
    }
    error("getDocumentId() can'f find ID from $documentUri")
}

private fun StorageVolume.getPathCompat(): String?
    =javaClass.getMethod("getPath").invoke(this) as? String
// API 29 だとグレーリストに入っていた
// Accessing hidden method Landroid/os/storage/StorageVolume;->getPath()Ljava/lang/String; (greylist, reflection, allowed)

// throw error if volume is not found.
@TargetApi(24)
fun pathFromDocumentUri(context: Context, src:String): String? {

    try {
        if(src.startsWith("/")) return src
        val uri = Uri.parse(src)
        if( uri.authority=="file") return uri.path

        if (isExternalStorageDocument(uri)) {
            val split = getDocumentId(uri).split(":").dropLastWhile { it.isEmpty() }
            if (split.size >= 2) {
                val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                val volume = when (val uuid = split[0]) {
                    "primary" -> storageManager.primaryStorageVolume // API 24
                    else ->
                        storageManager.storageVolumes.find { it.uuid == uuid }
                            ?: error("can't find volume for uuid $uuid")
                }
                return "${volume.getPathCompat()}/${split[1]}"
            }
        }

        return MediaInfo.find(context,src)?.path
    } catch (ex: Throwable) {
        log.eToast(context, ex, "pathFromDocumentUri failed.")
        return null
    }
}



