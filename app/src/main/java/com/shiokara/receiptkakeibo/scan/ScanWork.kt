package com.shiokara.receiptkakeibo.scan

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * 常駐せずに新しい写真を調べる仕組み。
 * - 写真フォルダに変化があったら OS がアプリを起こす (コンテンツ監視)
 * - 取りこぼし対策として 15 分おきにも確認する
 */
object ScanScheduler {
    private const val WORK_CONTENT = "scan-on-new-photo"
    private const val WORK_PERIODIC = "scan-periodic"
    const val TAG_CONTENT = "content-trigger"

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES).build(),
        )
        watchPhotos(context, ExistingWorkPolicy.KEEP)
    }

    /**
     * 写真が追加されたら 1 回だけ動く処理を登録する。
     * 1 回動くと監視が外れるので、動いた後に次の監視を後ろにつなげる。
     */
    fun watchPhotos(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            // 保存が終わるのを少し待ってから調べる
            .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
            .setTriggerContentMaxDelay(Duration.ofSeconds(30))
            .build()
        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setConstraints(constraints)
            .addTag(TAG_CONTENT)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_CONTENT, policy, request)
    }
}

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            ReceiptScanner(applicationContext).scanNew()
        } catch (e: Exception) {
            Log.w("ScanWorker", "写真の確認に失敗しました", e)
        }
        if (TAG_CONTENT in tags) {
            ScanScheduler.watchPhotos(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }

    private companion object {
        const val TAG_CONTENT = ScanScheduler.TAG_CONTENT
    }
}
