package jp.juggler.screenshotbutton

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import jp.juggler.util.notZero
import kotlin.math.max

data class MediaCodecInfoAndType(
    val info: MediaCodecInfo,
    val type: String,
    val vc: MediaCodecInfo.VideoCapabilities
) : Comparable<MediaCodecInfoAndType?> {


    override fun compareTo(other: MediaCodecInfoAndType?): Int {
        return comparator.compare(this, other)
    }

    val maxSize :Int
        get()=max( vc.supportedWidths.upper, vc.supportedHeights.upper )

    val maxBitRate :Int
        get()=vc.bitrateRange.upper

    val maxFrameRate :Int
        get()=vc.supportedFrameRates.upper

    val id:String
        get()="$type ${info.name}"

    override fun toString(): String =
        "${type}\n${info.name}\n${maxSize}px, ${maxFrameRate}fps, ${maxBitRate}bps\n--"

    companion object {

        // private val log = LogCategory("MediaCodecInfoAndType")

        private fun compareNull(a:Any?,b:Any?) =
            if (a == null) {
                if (b == null)
                    0
                else
                    -1 // null is smaller than not null
            } else {
                if (b == null)
                    1 // null is smaller than not null
                else
                    0
            }

        private val comparator = Comparator<MediaCodecInfoAndType> { a, b ->
            if(a==null || b==null) {
                compareNull(a, b)
            }else{
                a.type.compareTo(b.type).notZero() ?: a.info.name.compareTo(b.info.name)
            }
        }

        private val preferredType = arrayOf(
            "video/avc",
            "video/3gpp",
            "video/hevc"
        )

        private fun checkPreferredType(type:String):Int{
            for(i in preferredType.indices){
                if( type == preferredType[i]) return i
            }
            return preferredType.size
        }

        private val preferredComparator = Comparator<MediaCodecInfoAndType> { a, b ->
            if(a==null || b==null) {
                compareNull(a, b)
            }else{
                (b.maxSize -a.maxSize ).notZero()
                    ?: (b.maxFrameRate - a.maxFrameRate).notZero()
                    ?: (b.maxBitRate - a.maxBitRate).notZero()
                    ?: (checkPreferredType(a.type) - checkPreferredType(b.type)).notZero()
                    ?: a.type.compareTo(b.type)
                    ?: a.info.name.compareTo(b.info.name)
            }
        }

        val LIST = ArrayList<MediaCodecInfoAndType>().apply {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    val vc = info.getCapabilitiesForType(type)?.videoCapabilities ?: continue
                    add( MediaCodecInfoAndType(info, type,vc))
                }
            }
            sortWith(preferredComparator)
        }
    }

}