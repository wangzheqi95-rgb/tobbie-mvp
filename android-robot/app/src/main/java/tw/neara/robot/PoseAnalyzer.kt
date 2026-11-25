package tw.neara.robot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import tw.neara.core.Landmark
import tw.neara.core.Pose

/**
 * 相機畫面 → MediaPipe 姿勢辨識（最多 2 人）→ 回呼 onPoses。
 * 只在記憶體中處理，影像不存檔、不上傳；事件發生時才另外截一張照片。
 */
class PoseAnalyzer(ctx: Context, private val onPoses: (List<Pose>, Int, Int) -> Unit) {
    private val landmarker: PoseLandmarker = create(ctx, Delegate.GPU) ?: create(ctx, Delegate.CPU)
        ?: error("姿勢辨識模型載入失敗（assets/pose_landmarker_lite.task 不存在？）")
    @Volatile var lastBitmap: Bitmap? = null; private set
    /** 視訊通話中：同一個鏡頭畫面也送去視訊（偵測繼續進行）。 */
    @Volatile var frameTap: ((Bitmap) -> Unit)? = null
    private var lastTs = 0L

    private fun create(ctx: Context, d: Delegate): PoseLandmarker? = runCatching {
        val base = BaseOptions.builder().setModelAssetPath("pose_landmarker_lite.task").setDelegate(d).build()
        val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumPoses(2)
            .setMinPoseDetectionConfidence(0.3f)
            .setMinPosePresenceConfidence(0.3f)
            .setMinTrackingConfidence(0.3f)
            .setResultListener { result, image ->
                val poses = result.landmarks().map { lm ->
                    lm.map { Landmark(it.x(), it.y(), it.visibility().orElse(1f)) }
                }
                onPoses(poses, image.width, image.height)
            }
            .setErrorListener { /* 單幀錯誤略過 */ }
            .build()
        PoseLandmarker.createFromOptions(ctx, opts)
    }.getOrNull()

    /** CameraX ImageAnalysis 的每一幀。 */
    fun analyze(proxy: ImageProxy) {
        try {
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val bmp = if (rot != 0) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else raw
            lastBitmap = bmp
            frameTap?.invoke(bmp)
            var ts = SystemClock.uptimeMillis()
            if (ts <= lastTs) ts = lastTs + 1 // MediaPipe 規定時間戳要遞增
            lastTs = ts
            landmarker.detectAsync(BitmapImageBuilder(bmp).build(), ts)
        } finally {
            proxy.close()
        }
    }

    fun close() { landmarker.close() }
}
