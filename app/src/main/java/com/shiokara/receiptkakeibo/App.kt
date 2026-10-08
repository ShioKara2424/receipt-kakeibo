package com.shiokara.receiptkakeibo

import android.app.Application
import com.shiokara.receiptkakeibo.scan.Notifier
import com.shiokara.receiptkakeibo.scan.ScanScheduler
import com.shiokara.receiptkakeibo.scan.Settings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
        val settings = Settings(this)
        // 初回起動時は「今」から調べ始める (過去の写真を全部調べないように)
        if (settings.lastScanSec == 0L) settings.lastScanSec = System.currentTimeMillis() / 1000
        ScanScheduler.schedule(this)
    }
}
