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

    /** 画像と一緒に添付する指示文のファイル名 */
    private const val INSTRUCTION_FILE = "家計簿への記録ルール.txt"

    enum class Result(val message: String, val done: Boolean) {
        SHARED("Claude に画像と指示文のファイルを渡しました。そのまま送信してください", true),
        SHARED_IMAGE_ONLY("Claude に画像を渡しました。指示文をコピーしたので、入力欄に貼り付けて送信してください", true),
        OPENED_IN_APP("画像をコピーしました。入力欄で貼り付けて送信してください", true),
        OPENED_IN_BROWSER("画像をコピーしました。入力欄で貼り付けて送信してください", true),
        NO_SHEET_URL("先に家計簿のスプレッドシートの URL を設定してください", false),
        NO_CHAT_URL("先に送り先のチャットを設定してください", false),
        FAILED("Claude を開けませんでした", false),
    }

    /** 設定した送り方でレシートを送る */
    fun sendReceipts(context: Context, imagePaths: List<String>, settings: Settings): Result =
        when (settings.sendMode) {
            SendMode.SHEET -> {
                if (!Settings.isValidSheetUrl(settings.sheetUrl)) Result.NO_SHEET_URL
                else share(context, imagePaths, settings.sheetInstruction())
            }
            // チャットに貼り付ける方式では、画像は 1 枚ずつしか渡せない
            SendMode.CHAT -> send(context, imagePaths.first(), settings.chatUrl)
        }

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

    /**
     * 共有機能で Claude アプリに画像と指示文を渡す (チャットは指定できず、新しいチャットになる)。
     *
     * Claude アプリは画像と一緒に渡した文章 (EXTRA_TEXT) を入力欄に入れないため、
     * 指示文はテキストファイルにして画像と一緒に添付する。送信ボタンを押すだけで指示が伝わる。
     * テキストファイルを受け付けない場合は、画像だけを渡す (指示文はクリップボードから貼り付けてもらう)。
     */
    fun share(context: Context, imagePaths: List<String>, text: String?): Result {
        val images = imagePaths.map { imageUri(context, it) }
        if (!text.isNullOrBlank()) {
            copyText(context, "指示文", text)
            val file = File(context.filesDir, "receipts/$INSTRUCTION_FILE").apply {
                parentFile?.mkdirs()
                writeText(text)
            }
            val withInstructions = shareIntent(images + imageUri(context, file.path), "*/*")
            if (tryStart(context, Intent(withInstructions).setPackage(CLAUDE_PACKAGE))) return Result.SHARED
        }
        val imagesOnly = shareIntent(images, "image/jpeg")
        if (tryStart(context, Intent(imagesOnly).setPackage(CLAUDE_PACKAGE))) return Result.SHARED_IMAGE_ONLY
        return if (tryStart(context, Intent.createChooser(imagesOnly, "レシートを送る").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
            Result.SHARED_IMAGE_ONLY
        } else {
            Result.FAILED
        }
    }

    private fun shareIntent(uris: List<Uri>, type: String): Intent {
        val list = ArrayList(uris)
        val intent = if (list.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, list.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, list)
        }
        return intent.setType(type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun isClaudeInstalled(context: Context): Boolean =
        context.packageManager.getLaunchIntentForPackage(CLAUDE_PACKAGE) != null
}
