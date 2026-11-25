// 純 Kotlin 核心邏輯（跌倒偵測、miIO 協定、事件紀錄）：不依賴 Android，可在電腦上直接測試
plugins {
    id("org.jetbrains.kotlin.jvm")
}
kotlin { jvmToolchain(17) }
