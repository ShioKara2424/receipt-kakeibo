package com.shiokara.receiptkakeibo.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shiokara.receiptkakeibo.data.DetectedReceipt
import com.shiokara.receiptkakeibo.data.ReceiptDatabase
import com.shiokara.receiptkakeibo.scan.ClaudeLauncher
import com.shiokara.receiptkakeibo.scan.Notifier
import com.shiokara.receiptkakeibo.scan.ReceiptScanner
import com.shiokara.receiptkakeibo.scan.SendMode
import com.shiokara.receiptkakeibo.scan.Settings
import com.shiokara.receiptkakeibo.scan.SheetPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = ReceiptDatabase.get(app).dao()
    private val scanner = ReceiptScanner(app)
    val settings = Settings(app)

    val receipts: StateFlow<List<DetectedReceipt>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sendMode = MutableStateFlow(settings.sendMode)
    val sheetUrl = MutableStateFlow(settings.sheetUrl)
    val sheetPrompt = MutableStateFlow(settings.sheetPrompt)
    val chatUrl = MutableStateFlow(settings.chatUrl)
    val setupPrompt = MutableStateFlow(settings.setupPrompt)
    val scanning = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun setSendMode(mode: SendMode) {
        settings.sendMode = mode
        sendMode.value = mode
    }

    fun saveSheetUrl(url: String) {
        settings.sheetUrl = url
        sheetUrl.value = settings.sheetUrl
        _messages.tryEmit(
            if (Settings.isValidSheetUrl(settings.sheetUrl)) "スプレッドシートを保存しました"
            else "Google スプレッドシートの URL を入力してください",
        )
    }

    fun saveSheetPrompt(text: String) {
        settings.sheetPrompt = text
        sheetPrompt.value = text
        _messages.tryEmit(
            if (text.contains(SheetPrompt.PLACEHOLDER)) "指示文を保存しました"
            else "指示文を保存しました({シートURL} がないので、シートの URL は入りません)",
        )
    }

    fun resetSheetPrompt() {
        settings.sheetPrompt = SheetPrompt.DEFAULT
        sheetPrompt.value = settings.sheetPrompt
    }

    /** スプレッドシートをブラウザ (またはスプレッドシートのアプリ) で開く */
    fun openSheet() {
        val url = settings.sheetUrl
        if (!Settings.isValidSheetUrl(url)) {
            _messages.tryEmit("先にスプレッドシートの URL を設定してください")
            return
        }
        runCatching {
            getApplication<Application>().startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun saveChatUrl(url: String) {
        settings.chatUrl = url
        chatUrl.value = settings.chatUrl
        _messages.tryEmit(if (settings.isChatUrlValid()) "送り先を保存しました" else "claude.ai のチャットの URL を入力してください")
    }

    fun saveSetupPrompt(text: String) {
        settings.setupPrompt = text
        setupPrompt.value = text
        _messages.tryEmit("指示文を保存しました")
    }

    fun resetSetupPrompt() {
        settings.setupPrompt = com.shiokara.receiptkakeibo.scan.SetupPrompt.DEFAULT
        setupPrompt.value = settings.setupPrompt
    }

    /** 初期設定の指示文をコピーして、送り先のチャットを開く */
    fun sendSetupPrompt() {
        val app = getApplication<Application>()
        ClaudeLauncher.copyText(app, "初期設定の指示文", settings.setupPrompt)
        when (ClaudeLauncher.openChat(app, settings.chatUrl)) {
            ClaudeLauncher.Result.NO_CHAT_URL -> _messages.tryEmit("指示文をコピーしました。先に送り先のチャットを設定してください")
            ClaudeLauncher.Result.FAILED -> _messages.tryEmit("指示文をコピーしました。チャットを開けませんでした")
            else -> _messages.tryEmit("指示文をコピーしました。入力欄で貼り付けて送信してください")
        }
    }

    fun openChat() {
        if (ClaudeLauncher.openChat(getApplication(), settings.chatUrl) == ClaudeLauncher.Result.NO_CHAT_URL) {
            _messages.tryEmit("先に送り先のチャットを設定してください")
        }
    }

    fun scan(lookBackHours: Int = 0) {
        if (scanning.value) return
        viewModelScope.launch {
            scanning.value = true
            try {
                val r = scanner.scanNew(lookBackHours)
                _messages.emit(
                    when {
                        r.noPermission -> "写真へのアクセスが許可されていません"
                        r.checked == 0 -> "新しい写真はありませんでした"
                        else -> buildString {
                            append("写真 ${r.checked} 枚を確認し、レシートを ${r.found} 枚見つけました")
                            if (r.failed > 0) append("(${r.failed} 枚は読み込めませんでした)")
                        }
                    },
                )
            } catch (e: Exception) {
                _messages.emit("写真の確認に失敗しました: ${e.message}")
            } finally {
                scanning.value = false
            }
        }
    }

    /** ギャラリーで選んだ写真を向きを直して登録し、そのまま送る */
    fun addAndSend(uri: Uri) {
        viewModelScope.launch {
            scanning.value = true
            try {
                val receipt = scanner.addManually(uri)
                if (receipt == null) _messages.emit("写真を読み込めませんでした") else send(receipt)
            } catch (e: Exception) {
                _messages.emit("写真を処理できませんでした: ${e.message}")
            } finally {
                scanning.value = false
            }
        }
    }

    fun send(receipt: DetectedReceipt) = send(listOf(receipt))

    /** 設定した送り方で送る。スプレッドシート方式なら複数枚をまとめて送れる */
    fun send(receipts: List<DetectedReceipt>) {
        if (receipts.isEmpty()) return
        val app = getApplication<Application>()
        val result = ClaudeLauncher.sendReceipts(app, receipts.map { it.imagePath }, settings)
        _messages.tryEmit(result.message)
        if (result.done) {
            // チャット方式は 1 枚ずつしか渡せないので、渡せた 1 枚だけを送信済みにする
            val sent = if (settings.sendMode == SendMode.CHAT) receipts.take(1) else receipts
            sent.forEach {
                Notifier.cancel(app, it.mediaId)
                markSent(it)
            }
        }
    }

    private fun markSent(receipt: DetectedReceipt) {
        viewModelScope.launch { dao.markSent(receipt.mediaId, System.currentTimeMillis()) }
    }

    fun delete(receipt: DetectedReceipt) {
        viewModelScope.launch {
            Notifier.cancel(getApplication(), receipt.mediaId)
            withContext(Dispatchers.IO) { File(receipt.imagePath).delete() }
            dao.delete(receipt.mediaId)
        }
    }
}
