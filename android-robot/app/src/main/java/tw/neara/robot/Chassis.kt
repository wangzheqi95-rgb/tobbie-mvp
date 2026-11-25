package tw.neara.robot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import tw.neara.core.MiioClient
import tw.neara.core.MiioCodec
import tw.neara.core.RoborockS6
import java.util.UUID

/**
 * 底盤統一介面。換底盤（石頭 S6、Tobbie、之後代工廠的白牌底盤）只要多寫一個實作。
 * 每個動作都可能失敗，呼叫端要接住例外，不可以讓底盤問題卡住跌倒詢問流程。
 */
interface Chassis {
    val name: String
    suspend fun connect()
    /** 提案 4.4 NAVIGATE：偵測到異常 → 停止打掃、轉向長者、慢速靠近一小段。dir：L / R / null（已在正前方）。 */
    suspend fun navigate(dir: Char?, turnMs: Int, approachMs: Int)
    suspend fun alarm(dir: Char?, turnMs: Int) {}
    suspend fun normal() {}
    suspend fun turn(dir: Char, ms: Int)
    suspend fun forward(ms: Int) {}
    suspend fun stop() {}
    suspend fun dock() {}
    fun close() {}

    companion object {
        fun create(ctx: Context, prefs: Prefs): Chassis = when (prefs.chassis) {
            "s6" -> S6Chassis(prefs.s6Ip, prefs.s6Token)
            "tobbie" -> TobbieChassis(ctx)
            else -> NoChassis()
        }
    }
}

class NoChassis : Chassis {
    override val name = "無底盤"
    override suspend fun connect() {}
    override suspend fun navigate(dir: Char?, turnMs: Int, approachMs: Int) {}
    override suspend fun turn(dir: Char, ms: Int) {}
}

/** 石頭 S6：小米 miIO 區網協定直接控制（不經雲端、不 root）。 */
class S6Chassis(ip: String, tokenHex: String) : Chassis {
    override val name = "石頭 S6"
    private val s6: RoborockS6 = RoborockS6(MiioClient(ip, MiioCodec.tokenFromHex(tokenHex)))
    private val lock = Mutex()

    private suspend fun <T> io(block: () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    override suspend fun connect() { io { s6.status() } }
    override suspend fun turn(dir: Char, ms: Int) {
        val sign = if (dir == 'L') 1.0 else -1.0
        io { s6.remoteMove(sign * RoborockS6.TURN_DEG_PER_SEC, 0.0, ms) }
    }
    override suspend fun forward(ms: Int) { io { s6.remoteMove(0.0, 0.1, ms) } }
    override suspend fun stop() { io { s6.pause() } }
    override suspend fun dock() { io { s6.dock() } }
    override suspend fun navigate(dir: Char?, turnMs: Int, approachMs: Int) {
        stop()
        if (dir != null) turn(dir, turnMs)
        if (approachMs > 0) forward(approachMs.coerceAtMost(RoborockS6.MAX_DURATION_MS))
    }
    // S6 在 navigate 已轉向；警報時不再移動，避免碰到倒地的長者
}

/**
 * Tobbie II（micro:bit 藍牙 UART）：指令「字母＋#」。
 * L 左轉、R 右轉、S 停、H 搖頭、A 警示臉、N 正常臉（對應 tobbie_ble.ts）。
 */
@SuppressLint("MissingPermission") // 權限由 MainActivity 事先要求
class TobbieChassis(private val ctx: Context) : Chassis {
    override val name = "Tobbie II"
    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private val writeLock = Mutex()
    private var writeDone: CompletableDeferred<Unit>? = null

    override suspend fun connect() {
        val mgr = ctx.getSystemService(BluetoothManager::class.java) ?: error("這台手機沒有藍牙")
        val adapter = mgr.adapter ?: error("這台手機沒有藍牙")
        if (!adapter.isEnabled) error("請先打開藍牙")
        val found = CompletableDeferred<android.bluetooth.BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val n = r.device.name ?: r.scanRecord?.deviceName ?: return
                if (n.startsWith("BBC micro:bit")) found.complete(r.device)
            }
        }
        val scanner = adapter.bluetoothLeScanner ?: error("無法掃描藍牙")
        scanner.startScan(cb)
        val dev = try { withTimeout(15_000) { found.await() } } finally { scanner.stopScan(cb) }

        val ready = CompletableDeferred<Unit>()
        gatt = dev.connectGatt(ctx, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) { rx = null; if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("藍牙連線中斷")) }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(UART_SERVICE)
                rx = svc?.characteristics?.firstOrNull {
                    it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }
                if (rx != null) ready.complete(Unit) else ready.completeExceptionally(IllegalStateException("找不到 micro:bit UART 服務，請確認燒錄的是藍牙版程式"))
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writeDone?.complete(Unit) }
        })
        withTimeout(15_000) { ready.await() }
    }

    private suspend fun send(cmd: String) = writeLock.withLock {
        val g = gatt ?: return@withLock
        val c = rx ?: return@withLock
        val data = (cmd + "#").toByteArray()
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        writeDone = CompletableDeferred()
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, type)
        } else {
            @Suppress("DEPRECATION")
            run { c.writeType = type; c.value = data; g.writeCharacteristic(c) }
        }
        withTimeoutOrNull(1000) { writeDone?.await() }
    }

    override suspend fun turn(dir: Char, ms: Int) { send(dir.toString()); delay(ms.toLong()); send("S") }
    override suspend fun stop() { send("S") }
    override suspend fun navigate(dir: Char?, turnMs: Int, approachMs: Int) { send("A") } // 走路馬達故障，只顯示警示臉
    override suspend fun alarm(dir: Char?, turnMs: Int) { if (dir != null) turn(dir, turnMs); send("H") }
    override suspend fun normal() { send("S"); send("N") }
    override fun close() { gatt?.close(); gatt = null }

    companion object { val UART_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e") }
}
