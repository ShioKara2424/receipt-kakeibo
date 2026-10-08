package com.shiokara.receiptkakeibo.scan

import android.content.Context
import androidx.core.content.edit

/** レシートの送り方 */
enum class SendMode {
    /** 共有機能で Claude アプリに画像と指示文を渡し、スプレッドシートに追記してもらう (新しいチャットになる) */
    SHEET,

    /** 画像をクリップボードに入れて、決めたチャットを開く (貼り付けは手で行う) */
    CHAT,
}

/** アプリの設定 (端末内にだけ保存) */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var sendMode: SendMode
        get() = runCatching { SendMode.valueOf(prefs.getString(KEY_SEND_MODE, null)!!) }.getOrDefault(SendMode.SHEET)
        set(value) = prefs.edit { putString(KEY_SEND_MODE, value.name) }

    /** 家計簿の Google スプレッドシートの URL */
    var sheetUrl: String
        get() = prefs.getString(KEY_SHEET_URL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SHEET_URL, value.trim()) }

    /** 共有で送るときに添える指示文のひな形 ({シートURL} を含む) */
    var sheetPrompt: String
        get() = prefs.getString(KEY_SHEET_PROMPT, null) ?: SheetPrompt.DEFAULT
        set(value) = prefs.edit { putString(KEY_SHEET_PROMPT, value) }

    /** 実際に添える指示文 (シートの URL を埋め込んだもの) */
    fun sheetInstruction(): String = SheetPrompt.build(sheetPrompt, sheetUrl)

    /** レシートを送る Claude のチャットの URL (例: https://claude.ai/chat/xxxx) */
    var chatUrl: String
        get() = prefs.getString(KEY_CHAT_URL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_CHAT_URL, value.trim()) }

    /** チャットに最初に一度だけ送る指示文 */
    var setupPrompt: String
        get() = prefs.getString(KEY_SETUP_PROMPT, null) ?: SetupPrompt.DEFAULT
        set(value) = prefs.edit { putString(KEY_SETUP_PROMPT, value) }

    /** ここより後に追加された写真を調べる (エポック秒) */
    var lastScanSec: Long
        get() = prefs.getLong(KEY_LAST_SCAN, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SCAN, value) }

    /** 電池の最適化の案内を閉じたか */
    var batteryHintDismissed: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_HINT, false)
        set(value) = prefs.edit { putBoolean(KEY_BATTERY_HINT, value) }

    fun isChatUrlValid(): Boolean = isValidChatUrl(chatUrl)

    companion object {
        private const val KEY_SEND_MODE = "send_mode"
        private const val KEY_SHEET_URL = "sheet_url"
        private const val KEY_SHEET_PROMPT = "sheet_prompt"
        private const val KEY_CHAT_URL = "chat_url"
        private const val KEY_SETUP_PROMPT = "setup_prompt"
        private const val KEY_LAST_SCAN = "last_scan_sec"
        private const val KEY_BATTERY_HINT = "battery_hint_dismissed"

        fun isValidChatUrl(url: String): Boolean =
            Regex("""^https://claude\.ai/(chat|project)/[A-Za-z0-9\-]+.*$""").matches(url.trim())

        fun isValidSheetUrl(url: String): Boolean =
            Regex("""^https://docs\.google\.com/spreadsheets/d/[A-Za-z0-9_\-]+.*$""").matches(url.trim())
    }
}
