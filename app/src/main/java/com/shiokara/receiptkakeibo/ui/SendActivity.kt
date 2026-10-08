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
 * 画像をクリップボードに用意して Claude のチャットを開き、すぐに閉じる。
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
            val settings = Settings(this@SendActivity)
            when (ClaudeLauncher.send(this@SendActivity, receipt.imagePath, settings.chatUrl)) {
                ClaudeLauncher.Result.OPENED_IN_APP, ClaudeLauncher.Result.OPENED_IN_BROWSER -> {
                    dao.markSent(mediaId, System.currentTimeMillis())
                    Toast.makeText(this@SendActivity, "画像をコピーしました。入力欄で貼り付けて送信してください", Toast.LENGTH_LONG).show()
                }
                ClaudeLauncher.Result.NO_CHAT_URL, ClaudeLauncher.Result.FAILED -> {
                    Toast.makeText(this@SendActivity, "送り先のチャットを設定してください", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@SendActivity, MainActivity::class.java))
                }
            }
            finish()
        }
    }
}
