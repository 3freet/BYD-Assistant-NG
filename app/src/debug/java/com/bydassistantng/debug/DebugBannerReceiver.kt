package com.bydassistantng.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydassistantng.service.BannerMode
import com.bydassistantng.service.WheelKeyService

/** `am broadcast -a com.bydassistantng.debug.BANNER --es mode LISTENING [--es text "..."]`, or `--es mode HIDE`. */
class DebugBannerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val banner = WheelKeyService.instance?.banner ?: return
        val mode = intent.getStringExtra("mode").orEmpty()
        if (mode == "HIDE") banner.hide()
        else banner.show(BannerMode.valueOf(mode), intent.getStringExtra("text"))
    }
}
