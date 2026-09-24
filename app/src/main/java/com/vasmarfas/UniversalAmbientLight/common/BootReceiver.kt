package com.vasmarfas.UniversalAmbientLight.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteControlService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            // «Быстрый старт» части ТВ и приставок (MTK, HTC-наследие): выход из глубокого сна
            // без настоящей перезагрузки, BOOT_COMPLETED в этом случае не приходит
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT_POWERON,
            // Обновление из стора убивает процесс вместе с подсветкой посреди фильма
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                RemoteControlService.startIfEnabled(context)
                AutoStart.onBoot(context, intent.action.orEmpty())
            }

            AutoStart.ACTION_WATCHDOG -> AutoStart.onWatchdog(context)
        }
    }

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
