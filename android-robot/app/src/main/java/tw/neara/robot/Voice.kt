package tw.neara.robot

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import tw.neara.core.Answer
import tw.neara.core.Replies
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 說話（TTS）與聆聽（語音辨識 STT）。
 * 注意：SpeechRecognizer 必須在主執行緒建立與使用，所以 listen() 要在 Dispatchers.Main 呼叫。
 */
class Voice(private val ctx: Context, private val prefs: Prefs) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            val r = tts.setLanguage(Locale.TAIWAN)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.CHINESE)
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { id?.let { pending.remove(it)?.complete(Unit) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { id?.let { pending.remove(it)?.complete(Unit) } }
            override fun onError(id: String?, errorCode: Int) { id?.let { pending.remove(it)?.complete(Unit) } }
        })
    }

    /** 說完才回來；最多等「4 秒＋每字 0.4 秒」，避免 TTS 沒回報而卡住。 */
    suspend fun speak(text: String) {
        if (!ready) { delay(300); if (!ready) return }
        tts.setSpeechRate(prefs.speechRate)
        tts.setPitch(prefs.speechPitch)
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        withTimeoutOrNull(4000L + text.length * 400L) { done.await() }
        pending.remove(id)
    }

    fun stopSpeaking() { tts.stop() }

    data class Heard(val text: String, val answer: Answer)

    private var current: SpeechRecognizer? = null
    private var abort: CompletableDeferred<Unit>? = null

    /**
     * 聆聽最多 sec 秒。earlyStop=true（跌倒詢問）：一聽到「沒事／救命」之類就提早結束；
     * false（聊天）：講完一句就結束。辨識器提早停止時會自動重開，直到時間用完。
     */
    suspend fun listen(sec: Int, earlyStop: Boolean): Heard {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return Heard("", Answer.NONE)
        val endAt = System.currentTimeMillis() + sec * 1000L
        var text = ""
        val stopSignal = CompletableDeferred<Unit>().also { abort = it }
        while (System.currentTimeMillis() < endAt - 500 && !stopSignal.isCompleted) {
            val round = CompletableDeferred<Boolean>() // true = 該結束了
            val sr = SpeechRecognizer.createSpeechRecognizer(ctx).also { current = it }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(b: Bundle?) {
                    val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                    text = t
                    if (earlyStop && Replies.classify(t) != Answer.NONE) round.complete(true)
                }
                override fun onResults(b: Bundle?) {
                    b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { text = it }
                    round.complete(text.isNotBlank())
                }
                override fun onError(error: Int) { round.complete(false) }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            val finished = try {
                sr.startListening(intent)
                val remain = endAt - System.currentTimeMillis()
                withTimeoutOrNull(remain.coerceAtLeast(1)) {
                    kotlinx.coroutines.selects.select<Boolean> {
                        round.onAwait { it }
                        stopSignal.onAwait { true }
                    }
                } ?: true
            } finally {
                sr.cancel(); sr.destroy(); current = null // 被中斷（例如聊天中跌倒）也一定釋放麥克風
            }
            if (finished) break
            delay(50)
        }
        abort = null
        return Heard(text, Replies.classify(text))
    }

    /** 中斷聆聽（例如聊天中偵測到跌倒）。 */
    fun cancelListening() { abort?.complete(Unit); current?.cancel() }

    fun shutdown() { tts.stop(); tts.shutdown(); current?.destroy() }
}

/** 警報聲（系統鬧鐘音量）。 */
class AlarmSound {
    private var tone: ToneGenerator? = null
    fun start() {
        stop()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()
        tone?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK)
    }
    fun stop() { tone?.stopTone(); tone?.release(); tone = null }
}
