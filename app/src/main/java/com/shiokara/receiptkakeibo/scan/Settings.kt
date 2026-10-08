package com.shiokara.receiptkakeibo.scan

import android.content.Context
import androidx.core.content.edit

/** アプリの設定 (端末内にだけ保存) */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

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
        private const val KEY_CHAT_URL = "chat_url"
        private const val KEY_SETUP_PROMPT = "setup_prompt"
        private const val KEY_LAST_SCAN = "last_scan_sec"
        private const val KEY_BATTERY_HINT = "battery_hint_dismissed"

        fun isValidChatUrl(url: String): Boolean =
            Regex("""^https://claude\.ai/(chat|project)/[A-Za-z0-9\-]+.*$""").matches(url.trim())
    }
}
