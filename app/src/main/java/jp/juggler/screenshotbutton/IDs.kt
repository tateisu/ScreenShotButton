package jp.juggler.screenshotbutton


/*
    API Level による実装の切り替え
 */
const val API_STORAGE_VOLUME = 24
const val API_EXTRA_INITIAL_URI = 26
const val API_NOTIFICATION_CHANNEL = 26
const val API_APPLICATION_OVERLAY = 26
const val API_MEDIA_MUXER_FILE_DESCRIPTER = 26
@Suppress("unused")
const val API_SYSTEM_GESTURE_EXCLUSION = 29

/*
    アプリケーション全体で重複を避けるべきIDの定義

    通知のID

    PendingIntentのrequestCode

 */

const val NOTIFICATION_CHANNEL_RUNNING = "Capture Standby"
const val NOTIFICATION_ID_RUNNING_STILL = 1
const val NOTIFICATION_ID_RUNNING_VIDEO = 2

const val PI_CODE_RUNNING_TAP = 0
const val PI_CODE_RUNNING_DELETE_STILL = 1
const val PI_CODE_RUNNING_DELETE_VIDEO = 2
const val PI_CODE_VIDEO_START = 1
const val PI_CODE_VIDEO_STOP = 2
