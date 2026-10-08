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
import com.shiokara.receiptkakeibo.scan.Settings
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

    val chatUrl = MutableStateFlow(settings.chatUrl)
    val setupPrompt = MutableStateFlow(settings.setupPrompt)
    val scanning = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

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

    fun send(receipt: DetectedReceipt) {
        val app = getApplication<Application>()
        Notifier.cancel(app, receipt.mediaId)
        when (ClaudeLauncher.send(app, receipt.imagePath, settings.chatUrl)) {
            ClaudeLauncher.Result.NO_CHAT_URL -> _messages.tryEmit("先に送り先のチャットを設定してください")
            ClaudeLauncher.Result.FAILED -> _messages.tryEmit("チャットを開けませんでした。「共有」から送ってください")
            else -> {
                _messages.tryEmit("画像をコピーしました。入力欄で貼り付けて送信してください")
                markSent(receipt)
            }
        }
    }

    fun share(receipts: List<DetectedReceipt>) {
        if (receipts.isEmpty()) return
        val app = getApplication<Application>()
        ClaudeLauncher.share(app, receipts.map { it.imagePath })
        receipts.forEach {
            Notifier.cancel(app, it.mediaId)
            markSent(it)
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
