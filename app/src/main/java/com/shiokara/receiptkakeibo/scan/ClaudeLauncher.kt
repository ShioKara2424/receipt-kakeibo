package com.shiokara.receiptkakeibo.scan

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * レシートを Claude に渡す。
 *
 * 送り先のチャットを指定する標準の仕組みはないため、
 * 「画像をクリップボードに用意してから、設定したチャットの URL を Claude アプリで開く」形にしている。
 * 開いたチャットの入力欄で貼り付けて送信すれば完了。
 * うまく開けない端末向けに、共有機能で Claude アプリに渡す方法も用意している (この場合は新しいチャットになる)。
 */
object ClaudeLauncher {
    const val CLAUDE_PACKAGE = "com.anthropic.claude"

    enum class Result { OPENED_IN_APP, OPENED_IN_BROWSER, NO_CHAT_URL, FAILED }

    fun imageUri(context: Context, imagePath: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(imagePath))

    /** 画像をクリップボードに入れる (Claude の入力欄で貼り付けられるようにする) */
    fun copyImage(context: Context, imagePath: String) {
        val clip = ClipData.newUri(context.contentResolver, "レシート", imageUri(context, imagePath))
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    fun copyText(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** 設定したチャットを開く。Claude アプリがあればアプリで、なければブラウザで開く */
    fun openChat(context: Context, chatUrl: String): Result {
        if (!Settings.isValidChatUrl(chatUrl)) return Result.NO_CHAT_URL
        val uri = Uri.parse(chatUrl)
        val inApp = Intent(Intent.ACTION_VIEW, uri).setPackage(CLAUDE_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(inApp)
            return Result.OPENED_IN_APP
        } catch (_: ActivityNotFoundException) {
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result.OPENED_IN_BROWSER
        } catch (_: ActivityNotFoundException) {
            Result.FAILED
        }
    }

    /** レシート 1 枚を送る準備: 画像をコピーしてチャットを開く */
    fun send(context: Context, imagePath: String, chatUrl: String): Result {
        copyImage(context, imagePath)
        return openChat(context, chatUrl)
    }

    /** 共有機能で Claude アプリに画像を渡す (チャットは指定できず、新しいチャットになる) */
    fun share(context: Context, imagePaths: List<String>) {
        val uris = ArrayList(imagePaths.map { imageUri(context, it) })
        val base = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        val intent = base.setType("image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            context.startActivity(Intent(intent).setPackage(CLAUDE_PACKAGE))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent.createChooser(intent, "レシートを送る").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun isClaudeInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE) != null
}
