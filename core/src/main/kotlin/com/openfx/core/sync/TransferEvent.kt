package com.openfx.core.sync

/**
 * 傳輸事件 — TransferManager 只描述「發生了什麼」，文字由 app 層依語系組出來
 *
 * [detail] 類欄位是例外訊息等技術細節 (可能是英文)，UI 只當補充說明顯示。
 */
sealed interface TransferEvent {

    /** 已從 [senderDeviceName] 收到 [fileName] */
    data class Received(val fileName: String, val senderDeviceName: String) : TransferEvent

    /** 接收端本身出錯 (例如 socket 中斷) */
    data class ReceiveError(val detail: String) : TransferEvent

    /** 單一檔案接收中斷 */
    data class ReceiveFailed(val fileName: String, val detail: String) : TransferEvent

    /** 空間不足，拒收 [fileName] */
    data class NotEnoughSpace(val fileName: String) : TransferEvent

    /** 傳送失敗，[detail] 為 null 表示對方回應非成功狀態 */
    data class SendFailed(val fileName: String, val detail: String? = null) : TransferEvent

    /** 連不到接收端 [deviceName] */
    data class SendUnreachable(val fileName: String, val deviceName: String) : TransferEvent

    /** 使用者沒有輸入配對碼，傳送已取消 */
    data class SendCancelledNoPin(val fileName: String) : TransferEvent

    /** 預檢後對方更換了配對碼，這次傳送失敗 */
    data class SendPinExpired(val fileName: String) : TransferEvent
}
