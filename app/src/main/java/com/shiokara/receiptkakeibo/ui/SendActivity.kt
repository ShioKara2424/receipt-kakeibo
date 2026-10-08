package com.shiokara.receiptkakeibo.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.shiokara.receiptkakeibo.data.ReceiptDatabase
import com.shiokara.receiptkakeibo.scan.ClaudeLauncher
import com.shiokara.receiptkakeibo.scan.Notifier
import com.shiokara.receiptkakeibo.scan.Settings
import kotlinx.coroutines.launch

/**
 * 通知をタップしたときに動く、画面を持たない中継役。
 * 設定した送り方で Claude にレシートを渡し、すぐに閉じる。
 */
class SendActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mediaId = intent.getLongExtra(Notifier.EXTRA_MEDIA_ID, Long.MIN_VALUE)
        lifecycleScope.launch {
            val dao = ReceiptDatabase.get(this@SendActivity).dao()
            val receipt = dao.get(mediaId)
            if (receipt == null) {
                Toast.makeText(this@SendActivity, "このレシートは削除されています", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            Notifier.cancel(this@SendActivity, mediaId)
            val result = ClaudeLauncher.sendReceipts(this@SendActivity, listOf(receipt.imagePath), Settings(this@SendActivity))
            Toast.makeText(this@SendActivity, result.message, Toast.LENGTH_LONG).show()
            if (result.done) {
                dao.markSent(mediaId, System.currentTimeMillis())
            } else {
                startActivity(Intent(this@SendActivity, MainActivity::class.java))
            }
            finish()
        }
    }
}
