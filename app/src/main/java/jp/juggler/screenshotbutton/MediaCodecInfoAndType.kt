package jp.juggler.screenshotbutton

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import jp.juggler.util.clipInt
import jp.juggler.util.getScreenSize
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

    val maxSize: Int
        get() = max(vc.supportedWidths.upper, vc.supportedHeights.upper)

    val maxFrameRate: Int
        get() = vc.supportedFrameRates.upper

    val maxBitRate: Int
        get() = vc.bitrateRange.upper

    val id: String
        get() = "$type ${info.name}"

    override fun toString(): String =
        "${type}\n${info.name}\n${maxSize}px, ${maxFrameRate}fps, ${maxBitRate}bps\n--"

    private var rankingSize = 0
    private var rankingFrameRate = 0
    private var rankingBitRate = 0
    private var rankingTotal = 0

    companion object {

        // private val log = LogCategory("${App1.tagPrefix}/MediaCodecInfoAndType")

        private fun compareNull(a: Any?, b: Any?) =
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
            if (a == null || b == null) {
                compareNull(a, b)
            } else {
                a.type.compareTo(b.type).notZero() ?: a.info.name.compareTo(b.info.name)
            }
        }

        private val preferredType = arrayOf(
            "video/avc",
            "video/hevc"
        )

        private fun checkPreferredType(type: String): Int {
            for (i in preferredType.indices) {
                if (type == preferredType[i]) return i
            }
            return preferredType.size
        }

        private var list_: List<MediaCodecInfoAndType>? = null

        fun getList(context: Context):List<MediaCodecInfoAndType> = synchronized(this) {
            var list = list_
            if (list == null) {
                list = ArrayList<MediaCodecInfoAndType>().apply {
                    for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                        if (!info.isEncoder) continue
                        for (type in info.supportedTypes) {
                            val vc =
                                info.getCapabilitiesForType(type)?.videoCapabilities ?: continue
                            add(MediaCodecInfoAndType(info, type, vc))
                        }
                    }

                    val realSize = getScreenSize(context)
                    val longside = max(realSize.x, realSize.y)
                    for ((i, v) in sortedBy { -clipInt(0, longside, it.maxSize) }.withIndex()) {
                        v.rankingSize = i + 1
                    }
                    for ((i, v) in sortedBy { -clipInt(0, 120, it.maxFrameRate) }.withIndex()) {
                        v.rankingFrameRate = i + 1
                    }
                    for ((i, v) in sortedBy { -clipInt(0, 30000000, it.maxBitRate) }.withIndex()) {
                        v.rankingBitRate = i + 1
                    }
                    for (v in this) {
                        v.rankingTotal = v.rankingSize + v.rankingFrameRate + v.rankingBitRate
                    }
                    sortWith(Comparator { a, b ->
                        if (a == null || b == null) {
                            compareNull(a, b)
                        } else {
                            (checkPreferredType(a.type) - checkPreferredType(b.type)).notZero()
                                ?: (a.rankingTotal - b.rankingTotal).notZero()
                                ?: (b.maxSize - a.maxSize).notZero()
                                ?: (b.maxFrameRate - a.maxFrameRate).notZero()
                                ?: (b.maxBitRate - a.maxBitRate).notZero()
                                ?: a.type.compareTo(b.type)
                                ?: a.info.name.compareTo(b.info.name)
                        }
                    })
                }
                list_ = list
            }
            list
        }
    }
}
