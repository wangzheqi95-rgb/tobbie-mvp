package tw.neara.robot

import android.content.Context
import android.graphics.Bitmap
import android.view.SurfaceView
import android.view.ViewGroup
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.video.AgoraVideoFrame
import io.agora.rtc2.video.VideoCanvas
import java.nio.ByteBuffer

/**
 * 機器人端視訊通話（Agora）。
 * 鏡頭同時要做跌倒偵測，所以不讓 Agora 自己開鏡頭，而是把姿勢辨識用的同一張畫面「推」給 Agora（自訂影像來源）。
 * 畫面約每秒 10 張、縮到 640 寬以內，省流量也省電。
 */
class VideoCall(
    private val ctx: Context,
    private val remoteContainer: ViewGroup,
    private val onEvent: (String) -> Unit,
    private val onRemoteLeft: () -> Unit,
) {
    private var engine: RtcEngine? = null
    private var lastPush = 0L
    @Volatile var active = false; private set

    fun join(appId: String, channel: String, token: String, uid: Int) {
        val handler = object : IRtcEngineEventHandler() {
            override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) { onEvent("已接通") }
            override fun onUserJoined(remoteUid: Int, elapsed: Int) {
                remoteContainer.post {
                    val v = SurfaceView(ctx)
                    remoteContainer.removeAllViews(); remoteContainer.addView(v)
                    engine?.setupRemoteVideo(VideoCanvas(v, VideoCanvas.RENDER_MODE_HIDDEN, remoteUid))
                }
            }
            override fun onUserOffline(remoteUid: Int, reason: Int) { remoteContainer.post { onRemoteLeft() } }
            override fun onError(err: Int) { onEvent("視訊錯誤（代碼 $err）") }
        }
        val e = RtcEngine.create(RtcEngineConfig().apply { mContext = ctx.applicationContext; mAppId = appId; mEventHandler = handler })
        e.enableVideo()
        e.setExternalVideoSource(true, false, Constants.ExternalVideoSourceType.VIDEO_FRAME)
        e.setDefaultAudioRoutetoSpeakerphone(true) // 開擴音，長輩不用拿手機
        val opts = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishCameraTrack = false
            publishCustomVideoTrack = true
            publishMicrophoneTrack = true
            autoSubscribeAudio = true
            autoSubscribeVideo = true
        }
        e.joinChannel(token, channel, uid, opts)
        engine = e
        active = true
    }

    /** 由姿勢辨識執行緒呼叫：把畫面送去視訊（最多每秒 10 張）。 */
    fun pushFrame(bmp: Bitmap) {
        val e = engine ?: return
        val now = System.currentTimeMillis()
        if (now - lastPush < 100) return
        lastPush = now
        val scale = minOf(1f, 640f / bmp.width)
        val src = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val buf = ByteBuffer.allocate(src.byteCount)
        src.copyPixelsToBuffer(buf) // ARGB_8888 在記憶體中的順序就是 RGBA
        val f = AgoraVideoFrame().apply {
            format = AgoraVideoFrame.FORMAT_RGBA
            timeStamp = now
            stride = src.width
            height = src.height
            this.buf = buf.array()
        }
        e.pushExternalVideoFrame(f)
    }

    fun leave() {
        active = false
        engine?.leaveChannel()
        engine = null
        RtcEngine.destroy()
        remoteContainer.post { remoteContainer.removeAllViews() }
    }
}
