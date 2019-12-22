package jp.juggler.screenshotbutton

/*
    アプリケーション全体で重複を避けるべきIDの定義

    通知のID

    PendingIntentのrequestCode

 */

const val NOTIFICATION_CHANNEL_RUNNING = "Capture Standby"
const val NOTIFICATION_ID_RUNNING = 1
const val PI_CODE_RUNNING_TAP = 0
const val PI_CODE_RUNNING_DELETE = 1
