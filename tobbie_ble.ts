// Tobbie II 藍牙版（插在 Tobbie 頭上的那片 micro:bit）
// 手機網頁傳來的指令（都以 # 結尾）：
//   L# 左轉　R# 右轉　S# 停止　H# 搖頭　A# 警示臉「!」　N# 正常臉
// 注意：走路馬達已故障，所以這版只用轉身、搖頭和臉部 LED。

let cmd = "S"        // 目前的動作
let shake = false    // 是否要搖頭

bluetooth.startUartService()
basic.showIcon(IconNames.Happy)

bluetooth.onBluetoothConnected(function () {
    basic.showIcon(IconNames.Yes)
})

bluetooth.onBluetoothDisconnected(function () {
    // 斷線時一律停下來，避免一直轉
    TobbieII.stopturn()
    cmd = "S"
    basic.showIcon(IconNames.No)
})

// 收到手機傳來的一段文字（讀到 # 為止）
bluetooth.onUartDataReceived(serial.delimiters(Delimiters.Hash), function () {
    let c = bluetooth.uartReadUntil(serial.delimiters(Delimiters.Hash))
    if (c == "L" || c == "R") {
        TobbieII.stopturn()      // 換方向前先停
        cmd = c
    } else if (c == "S") {
        TobbieII.stopturn()
        cmd = "S"
    } else if (c == "H") {
        TobbieII.stopturn()
        cmd = "S"
        shake = true
    } else if (c == "A") {
        basic.showString("!")
    } else if (c == "N") {
        basic.showIcon(IconNames.Happy)
    }
})

// 一直重複執行目前的動作
basic.forever(function () {
    if (shake) {
        TobbieII.shake_head(3)
        shake = false
    } else if (cmd == "L") {
        TobbieII.leftward()
    } else if (cmd == "R") {
        TobbieII.rightward()
    }
})
