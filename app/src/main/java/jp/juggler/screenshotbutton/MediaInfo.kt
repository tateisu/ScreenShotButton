package jp.juggler.screenshotbutton

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import jp.juggler.util.LogCategory

class MediaInfo(
    val uri: Uri,
    val mimeType: String?,
    val path:String?
) {
    companion object {
        private val log = LogCategory("${App1.tagPrefix}/MediaInfo")

        private fun Cursor.getStringOrNull(idx:Int)= when {
            isNull(idx) -> null
            else -> getString(idx)
        }

        @SuppressLint("Recycle")
        @Suppress("DEPRECATION")
        fun find(context: Context, src: String): MediaInfo? {
            return try {
                if (src.startsWith("/")) {
                    context.contentResolver.query(
                        ActViewer.baseUri,
                        null,
                        "${MediaStore.MediaColumns.DATA}=?",
                        arrayOf(src),
                        null
                    )
                } else {
                    context.contentResolver.query(
                        Uri.parse(src),
                        null,
                        null,
                        null,
                        null
                    )
                } ?.use { cursor ->
                    val idxId = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val idxMimeType = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val idxPath = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (cursor.moveToNext()) {
                            MediaInfo(
                                ContentUris.withAppendedId(ActViewer.baseUri, cursor.getLong(idxId)),
                                cursor.getStringOrNull(idxMimeType),
                                cursor.getStringOrNull(idxPath)
                            )
                        } else {
                            log.eToast(context, false, "can't find content uri.")
                            null
                        }
                    }
            } catch (ex: Throwable) {
                log.eToast(context, ex, "findMedia() failed. $src")
                null
            }
        }
    }
}
