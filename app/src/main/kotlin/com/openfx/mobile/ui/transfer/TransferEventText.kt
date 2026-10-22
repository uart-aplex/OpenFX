package com.openfx.mobile.ui.transfer

import android.content.res.Resources
import com.openfx.core.sync.TransferEvent
import com.openfx.mobile.R

/** 把 TransferManager 的事件組成目前語系的狀態文字 (畫面與通知共用) */
fun TransferEvent.message(res: Resources): String = when (this) {
    is TransferEvent.Received ->
        res.getString(R.string.event_received, senderDeviceName, fileName)
    is TransferEvent.ReceiveError ->
        res.getString(R.string.event_receive_error, detail)
    is TransferEvent.ReceiveFailed ->
        res.getString(R.string.event_receive_failed, fileName, detail)
    is TransferEvent.NotEnoughSpace ->
        res.getString(R.string.event_not_enough_space, fileName)
    is TransferEvent.SendFailed ->
        if (detail == null) res.getString(R.string.event_send_failed, fileName)
        else res.getString(R.string.event_send_failed_detail, fileName, detail)
    is TransferEvent.SendUnreachable ->
        res.getString(R.string.event_send_unreachable, fileName, deviceName)
    is TransferEvent.SendCancelledNoPin ->
        res.getString(R.string.event_send_cancelled_no_pin, fileName)
    is TransferEvent.SendPinExpired ->
        res.getString(R.string.event_send_pin_expired, fileName)
}
