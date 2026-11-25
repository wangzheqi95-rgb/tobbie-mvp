package tw.neara.robot

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 智慧手錶串接：透過 Health Connect（Android 的健康資料中心）讀心率與今天步數。
 * 長輩的手錶要配對在這支機器人手機上（手錶自己的 App 會把資料寫進 Health Connect），
 * 機器人每 5 分鐘讀一次，上傳到雲端；事件報告會附上事件前後的心率。
 * 只讀不寫，也不讀心率與步數以外的資料。
 */
class HealthSync(private val ctx: Context, private val prefs: Prefs) {
    companion object {
        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
        )
        private const val MAX_BACK_MS = 2 * 3600 * 1000L // 一次最多往回讀 2 小時
    }

    /** 回報給後端的連線狀態：OK／NO_PERMISSION／NOT_INSTALLED／OFF。 */
    var status = "OFF"; private set

    private fun client(): HealthConnectClient? {
        if (!prefs.healthConnect) { status = "OFF"; return null }
        return when (HealthConnectClient.getSdkStatus(ctx)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectClient.getOrCreate(ctx)
            else -> { status = "NOT_INSTALLED"; null }
        }
    }

    data class Reading(val hr: List<Pair<Long, Long>>, val stepsToday: Long?)

    /** 讀上次同步之後的新資料；沒有權限或沒安裝就回傳 null（並更新 status）。 */
    suspend fun read(now: Long = System.currentTimeMillis()): Reading? {
        val c = client() ?: return null
        if (!c.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)) { status = "NO_PERMISSION"; return null }
        val from = maxOf(prefs.healthLastSync, now - MAX_BACK_MS)
        val range = TimeRangeFilter.between(Instant.ofEpochMilli(from), Instant.ofEpochMilli(now))
        val hr = c.readRecords(ReadRecordsRequest(HeartRateRecord::class, range)).records
            .flatMap { r -> r.samples.map { it.time.toEpochMilli() to it.beatsPerMinute } }
            .filter { it.first in from..now }
        val dayStart = ZonedDateTime.now(ZoneId.of("Asia/Taipei")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Taipei")).toInstant()
        val steps = runCatching {
            c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(dayStart, Instant.ofEpochMilli(now))))[StepsRecord.COUNT_TOTAL]
        }.getOrNull()
        status = "OK"
        return Reading(hr, steps)
    }
}
