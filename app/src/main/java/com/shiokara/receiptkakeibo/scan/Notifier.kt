package com.shiokara.receiptkakeibo.scan

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shiokara.receiptkakeibo.R
import com.shiokara.receiptkakeibo.data.DetectedReceipt
import com.shiokara.receiptkakeibo.data.ReceiptDatabase
import com.shiokara.receiptkakeibo.ui.SendActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

object Notifier {
    private const val CHANNEL_ID = "receipts"
    const val EXTRA_MEDIA_ID = "media_id"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "見つけたレシート", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "レシートの写真を見つけたときに、Claude に送るための通知を出します"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notificationId(mediaId: Long): Int = (mediaId xor (mediaId ushr 32)).toInt()

    @SuppressLint("MissingPermission") // 権限は canNotify で確認している
    fun notifyReceipt(context: Context, receipt: DetectedReceipt) {
        if (!Permissions.canNotify(context)) return
        val id = notificationId(receipt.mediaId)

        val send = PendingIntent.getActivity(
            context, id,
            Intent(context, SendActivity::class.java).putExtra(EXTRA_MEDIA_ID, receipt.mediaId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            context, id,
            Intent(context, NotReceiptReceiver::class.java).putExtra(EXTRA_MEDIA_ID, receipt.mediaId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val thumb = ImageTools.thumbnail(receipt.imagePath, 640)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("レシートを見つけました")
            .setContentText(
                if (Settings(context).sendMode == SendMode.SHEET) "タップすると Claude に画像と指示文を渡します"
                else "タップすると Claude のチャットを開きます。入力欄で貼り付けて送信してください",
            )
            .setContentIntent(send)
            .setAutoCancel(true)
            .addAction(0, "Claudeに送る", send)
            .addAction(0, "レシートではない", dismiss)
        if (thumb != null) {
            builder.setLargeIcon(thumb)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumb).bigLargeIcon(null as android.graphics.Bitmap?))
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    fun cancel(context: Context, mediaId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(mediaId))
    }
}

/** 通知の「レシートではない」: 一覧から外して画像を消す */
class NotReceiptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mediaId = intent.getLongExtra(Notifier.EXTRA_MEDIA_ID, Long.MIN_VALUE)
        if (mediaId == Long.MIN_VALUE) return
        Notifier.cancel(context, mediaId)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = ReceiptDatabase.get(context).dao()
                dao.get(mediaId)?.let { File(it.imagePath).delete() }
                dao.delete(mediaId)
            } finally {
                pending.finish()
            }
        }
    }
}
