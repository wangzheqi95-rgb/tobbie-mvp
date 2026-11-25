package tw.neara.robot

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * 視訊來電推播：後端送來只帶資料的高優先訊息 {type:"call", callId, fromName, autoAnswer}。
 * 機器人 App 一直開在前景（專用裝置），收到後交給主畫面響鈴。
 */
class NearaMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Prefs(this).fcmToken = token // 下次心跳回報給後端
    }

    override fun onMessageReceived(m: RemoteMessage) {
        val d = m.data
        if (d["type"] == "call" && !d["callId"].isNullOrBlank()) {
            CallBus.post(IncomingCall(d["callId"]!!, d["fromName"] ?: "家人", d["autoAnswer"] == "1"))
        }
    }
}
