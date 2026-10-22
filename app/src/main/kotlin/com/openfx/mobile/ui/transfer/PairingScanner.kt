package com.openfx.mobile.ui.transfer

import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.openfx.core.sync.PairingCode
import com.openfx.core.sync.TransferManager.PairResult
import com.openfx.mobile.R

/**
 * 開啟相機掃描接收端的 QR Code。
 * 取消掃描不回呼；掃到不是 OpenFX 的碼時 [onScanned] 收到 null。
 */
@Composable
fun rememberPairingScanner(onScanned: (PairingCode?) -> Unit): () -> Unit {
    val resources = LocalResources.current
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        onScanned(PairingCode.parse(text))
    }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(resources.getString(R.string.qr_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }
}

fun PairResult.message(resources: Resources): String = when (this) {
    is PairResult.Paired -> resources.getString(R.string.pair_ok, device.deviceName)
    PairResult.NotFound -> resources.getString(R.string.pair_not_found)
    PairResult.WrongPin -> resources.getString(R.string.pair_wrong_pin)
    PairResult.Locked -> resources.getString(R.string.pairing_pin_locked)
    PairResult.OtherDevice -> resources.getString(R.string.pair_other_device)
}
