package tw.neara.robot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 開機自動開啟。Android 10 以後系統限制背景啟動畫面，所以最可靠的做法是
 * 把 Neara 設為「預設主畫面」（AndroidManifest 已宣告 HOME），這裡只是補強。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
