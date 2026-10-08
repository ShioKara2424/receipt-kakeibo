package com.shiokara.receiptkakeibo.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiokara.receiptkakeibo.data.DetectedReceipt
import com.shiokara.receiptkakeibo.scan.ClaudeLauncher
import com.shiokara.receiptkakeibo.scan.ImageTools
import com.shiokara.receiptkakeibo.scan.Permissions
import com.shiokara.receiptkakeibo.scan.ScanScheduler
import com.shiokara.receiptkakeibo.scan.SendMode
import com.shiokara.receiptkakeibo.scan.Settings
import com.shiokara.receiptkakeibo.scan.SheetPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { MainScreen(vm) } }
    }
}

private val DATE_TIME = DateTimeFormatter.ofPattern("M/d(E) H:mm", Locale.JAPAN)

private fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE_TIME)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val receipts by vm.receipts.collectAsStateWithLifecycle()
    val chatUrl by vm.chatUrl.collectAsStateWithLifecycle()
    val sheetUrl by vm.sheetUrl.collectAsStateWithLifecycle()
    val sendMode by vm.sendMode.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // 設定画面から戻ったときに権限の状態を読み直すためのカウンタ
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val hasPhotos = remember(refresh) { Permissions.hasPhotoAccess(context) }
    val partial = remember(refresh) { Permissions.hasOnlyPartialAccess(context) }
    val canNotify = remember(refresh) { Permissions.canNotify(context) }
    val batteryOk = remember(refresh) { Permissions.isIgnoringBatteryOptimizations(context) }
    var batteryHintDismissed by remember { mutableStateOf(vm.settings.batteryHintDismissed) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        ScanScheduler.schedule(context)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.addAndSend(uri)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("レシートをClaudeへ") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasPhotos || !canNotify) {
                item {
                    StepCard(
                        title = "写真と通知の許可",
                        body = if (partial) {
                            "「選択した写真のみ」になっています。新しく撮った写真を確認できるよう、設定で写真へのアクセスを「すべて許可」にしてください。"
                        } else {
                            "新しく撮った写真からレシートを探すために、写真へのアクセスを許可してください(「すべて許可」を選んでください)。見つけたときに通知でお知らせします。"
                        },
                    ) {
                        if (partial) {
                            Button(onClick = { openAppSettings(context) }) { Text("設定を開く") }
                        } else {
                            Button(onClick = { permissionLauncher.launch(Permissions.requested) }) { Text("許可する") }
                        }
                    }
                }
            }

            item { SendModeCard(sendMode, vm::setSendMode) }
            if (sendMode == SendMode.SHEET) {
                item { SheetUrlCard(sheetUrl = sheetUrl, onSave = vm::saveSheetUrl, onOpen = vm::openSheet) }
                item { SheetPromptCard(vm) }
            } else {
                item { ChatUrlCard(chatUrl = chatUrl, onSave = vm::saveChatUrl, onOpen = vm::openChat) }
                item { SetupPromptCard(vm) }
            }

            if (!batteryOk && !batteryHintDismissed) {
                item {
                    StepCard(
                        title = "電池の最適化(おすすめ)",
                        body = "機種によっては省電力機能で写真の確認が止まることがあります。このアプリを電池の最適化の対象外にすると確実に動きます。",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { requestIgnoreBattery(context) }) { Text("設定する") }
                            TextButton(onClick = {
                                vm.settings.batteryHintDismissed = true
                                batteryHintDismissed = true
                            }) { Text("閉じる") }
                        }
                    }
                }
            }

            item {
                ActionsRow(
                    scanning = scanning,
                    onScan = { vm.scan() },
                    onScanDay = { vm.scan(lookBackHours = 24) },
                    onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
            }

            val unsent = receipts.filter { it.sentAt == null }
            val sent = receipts.filter { it.sentAt != null }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("未送信のレシート(${unsent.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (unsent.size >= 2 && sendMode == SendMode.SHEET) {
                        TextButton(onClick = { vm.send(unsent) }) { Text("まとめて送る") }
                    }
                }
            }
            if (unsent.isEmpty()) {
                item {
                    Text(
                        "レシートを撮影すると、ここに表示されて通知が届きます。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(unsent, key = { it.mediaId }) { r ->
                ReceiptCard(r, onSend = { vm.send(r) }, onDelete = { vm.delete(r) })
            }

            if (sent.isNotEmpty()) {
                item {
                    Text("送信済み(30日で自動削除)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                items(sent, key = { it.mediaId }) { r ->
                    ReceiptCard(r, onSend = { vm.send(r) }, onDelete = { vm.delete(r) })
                }
            }
        }
    }
}

@Composable
private fun StepCard(title: String, body: String, actions: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}

@Composable
private fun SendModeCard(mode: SendMode, onChange: (SendMode) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("送り方", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            ModeOption(
                selected = mode == SendMode.SHEET,
                title = "スプレッドシートに追記(おすすめ)",
                body = "画像と指示文を Claude アプリに渡し、Google スプレッドシートに追記してもらいます。毎回新しいチャットになります。",
                onClick = { onChange(SendMode.SHEET) },
            )
            ModeOption(
                selected = mode == SendMode.CHAT,
                title = "決めたチャットに貼り付け",
                body = "画像をコピーして決めたチャットを開きます。入力欄で貼り付けて送信します。",
                onClick = { onChange(SendMode.CHAT) },
            )
        }
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SheetUrlCard(sheetUrl: String, onSave: (String) -> Unit, onOpen: () -> Unit) {
    var text by rememberSaveable(sheetUrl) { mutableStateOf(sheetUrl) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("家計簿のスプレッドシート", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Google スプレッドシートの URL(https://docs.google.com/spreadsheets/d/…)を貼り付けてください。Claude アプリでは、チャットの「+」→ コネクタで Google Sheets をオンにしておいてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("スプレッドシートのURL") },
                singleLine = true,
                isError = text.isNotBlank() && !Settings.isValidSheetUrl(text),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(text) }, enabled = text != sheetUrl) { Text("保存") }
                OutlinedButton(onClick = onOpen, enabled = Settings.isValidSheetUrl(sheetUrl)) { Text("シートを開く") }
            }
        }
    }
}

@Composable
private fun SheetPromptCard(vm: MainViewModel) {
    val prompt by vm.sheetPrompt.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(prompt) { mutableStateOf(prompt) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("画像と一緒に渡す指示文", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "送るたびにこの指示文を添えます。「${SheetPrompt.PLACEHOLDER}」の部分は、設定したスプレッドシートの URL に置き換わります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveSheetPrompt(draft); editing = false }) { Text("保存") }
                    TextButton(onClick = { draft = prompt; editing = false }) { Text("やめる") }
                    TextButton(onClick = { vm.resetSheetPrompt(); editing = false }) { Text("初期値に戻す") }
                }
            } else {
                OutlinedButton(onClick = { editing = true }) { Text("編集") }
            }
        }
    }
}

@Composable
private fun ChatUrlCard(chatUrl: String, onSave: (String) -> Unit, onOpen: () -> Unit) {
    var text by rememberSaveable(chatUrl) { mutableStateOf(chatUrl) }
    val valid = Settings.isValidChatUrl(chatUrl)
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("送り先のチャット", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "ブラウザで claude.ai を開き、レシート用のチャットのアドレス(https://claude.ai/chat/…)をコピーして貼り付けてください。チャットが長くなったら、新しいチャットのアドレスに差し替えられます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("チャットのURL") },
                singleLine = true,
                isError = text.isNotBlank() && !Settings.isValidChatUrl(text),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(text) }, enabled = text != chatUrl) { Text("保存") }
                OutlinedButton(onClick = onOpen, enabled = valid) { Text("開いて確認") }
            }
        }
    }
}

@Composable
private fun SetupPromptCard(vm: MainViewModel) {
    val prompt by vm.setupPrompt.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable(prompt) { mutableStateOf(prompt) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最初に送る指示文", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "チャットを新しくしたら、最初に一度だけこの指示文を送ってください。以降はレシートの写真を貼り付けて送るだけで、家計簿に追記されます。過去の家計簿ファイルがあれば、一緒に添付してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveSetupPrompt(draft); editing = false }) { Text("保存") }
                    TextButton(onClick = { draft = prompt; editing = false }) { Text("やめる") }
                    TextButton(onClick = { vm.resetSetupPrompt(); editing = false }) { Text("初期値に戻す") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::sendSetupPrompt) { Text("コピーしてチャットを開く") }
                    OutlinedButton(onClick = { editing = true }) { Text("編集") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsRow(scanning: Boolean, onScan: () -> Unit, onScanDay: () -> Unit, onPick: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedButton(onClick = onScan, enabled = !scanning) { Text("今すぐ確認") }
        OutlinedButton(onClick = onScanDay, enabled = !scanning) { Text("過去24時間を確認") }
        OutlinedButton(onClick = onPick, enabled = !scanning) { Text("写真を選んで送る") }
        if (scanning) CircularProgressIndicator(Modifier.size(32.dp).align(Alignment.CenterVertically))
    }
}

@Composable
private fun ReceiptCard(receipt: DetectedReceipt, onSend: () -> Unit, onDelete: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, receipt.imagePath) {
        value = withContext(Dispatchers.IO) { ImageTools.thumbnail(receipt.imagePath)?.asImageBitmap() }
    }
    Card {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val image = thumb
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = "レシートの写真",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 72.dp, height = 96.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Spacer(Modifier.size(width = 72.dp, height = 96.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("撮影 ${formatTime(receipt.takenAt)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                receipt.sentAt?.let {
                    Text("送信 ${formatTime(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onSend) { Text(if (receipt.sentAt == null) "送る" else "再送") }
                    TextButton(onClick = onDelete) { Text("削除") }
                }
            }
        }
    }
}

private fun openAppSettings(context: android.content.Context) {
    context.startActivity(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

@SuppressLint("BatteryLife") // Play ストアで配布しない個人用アプリなので、直接許可を求める
private fun requestIgnoreBattery(context: android.content.Context) {
    val intent = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
        .onFailure { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}
