package tw.neara.robot

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** 視訊來電（從推播或心跳來），交給主畫面響鈴。 */
data class IncomingCall(val callId: String, val fromName: String, val autoAnswer: Boolean)

object CallBus {
    private val flow = MutableSharedFlow<IncomingCall>(extraBufferCapacity = 4)
    val calls: SharedFlow<IncomingCall> = flow
    fun post(c: IncomingCall) { flow.tryEmit(c) }
}
