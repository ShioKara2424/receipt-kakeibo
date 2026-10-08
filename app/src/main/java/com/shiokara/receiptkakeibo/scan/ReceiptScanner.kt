package com.shiokara.receiptkakeibo.scan

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.shiokara.receiptkakeibo.data.DetectedReceipt
import com.shiokara.receiptkakeibo.data.ProcessedImage
import com.shiokara.receiptkakeibo.data.ReceiptDatabase
import com.shiokara.receiptkakeibo.parse.OcrLine
import com.shiokara.receiptkakeibo.parse.OcrText
import com.shiokara.receiptkakeibo.parse.Orientation
import com.shiokara.receiptkakeibo.parse.ReceiptDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 新しく撮った写真を調べ、レシートだったら向きを直してアプリ内に保存し、通知する。
 * 文字認識はすべてスマホ内で行い、写真が外に送られることはない。
 */
class ReceiptScanner(context: Context) {
    private val context = context.applicationContext
    private val dao = ReceiptDatabase.get(context).dao()
    private val settings = Settings(context)

    /**
     * 前回以降に追加された写真を調べる。
     * @param lookBackHours 0 より大きければ、前回の位置に関係なくこの時間分さかのぼって調べる
     * @return 新しく見つけたレシートの数
     */
    suspend fun scanNew(lookBackHours: Int = 0): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!Permissions.hasPhotoAccess(context)) return@withContext 0
            val nowSec = System.currentTimeMillis() / 1000
            val since = if (lookBackHours > 0) nowSec - lookBackHours * 3600L
            // 保存処理中だった写真を取りこぼさないよう、少しさかのぼる (調べ済みの写真は飛ばす)
            else settings.lastScanSec - 600

            val photos = queryCameraPhotos(since)
            var found = 0
            val recognizer = newRecognizer()
            try {
                for (photo in photos) {
                    if (dao.isProcessed(photo.id)) continue
                    val receipt = runCatching { examine(recognizer, photo) }
                        .onFailure { Log.w(TAG, "写真 ${photo.id} を調べられませんでした", it) }
                        .getOrNull()
                    dao.markProcessed(ProcessedImage(photo.id, receipt != null))
                    if (receipt != null) {
                        dao.upsert(receipt)
                        Notifier.notifyReceipt(context, receipt)
                        found++
                    }
                }
            } finally {
                recognizer.close()
            }
            val newest = photos.maxOfOrNull { it.dateAddedSec } ?: 0L
            if (newest > settings.lastScanSec) settings.lastScanSec = newest
            cleanUpOld()
            found
        }
    }

    /** ギャラリーから手で選んだ写真を、判定なしでレシートとして登録する (向きの補正だけ行う) */
    suspend fun addManually(uri: Uri): DetectedReceipt? = withContext(Dispatchers.IO) {
        val bitmap = ImageTools.load(context, uri) ?: return@withContext null
        val recognizer = newRecognizer()
        try {
            val upright = correctOrientation(recognizer, bitmap).bitmap
            // ギャラリー以外から選ばれた写真にも重ならない ID を振る
            val id = -System.currentTimeMillis()
            val file = File(context.filesDir, "receipts/$id.jpg")
            ImageTools.saveJpeg(upright, file)
            DetectedReceipt(mediaId = id, imagePath = file.path, takenAt = System.currentTimeMillis())
                .also { dao.upsert(it) }
        } finally {
            recognizer.close()
        }
    }

    private suspend fun examine(recognizer: TextRecognizer, photo: Photo): DetectedReceipt? {
        val bitmap = ImageTools.load(context, photo.uri) ?: return null
        val best = correctOrientation(recognizer, bitmap)
        if (!ReceiptDetector.detect(OcrText.toRows(best.lines)).isReceipt) return null

        val file = File(context.filesDir, "receipts/${photo.id}.jpg")
        ImageTools.saveJpeg(best.bitmap, file)
        return DetectedReceipt(mediaId = photo.id, imagePath = file.path, takenAt = photo.takenAtMillis)
    }

    private class Reading(val bitmap: Bitmap, val lines: List<OcrLine>)

    /**
     * 文字が水平に並ぶ向きを探す。
     * そのままで読めればそれを使い、だめなら 90°/180°/270° 回したものを読んで、
     * 水平な文字がいちばん多く読めた向きを採用する。
     */
    private suspend fun correctOrientation(recognizer: TextRecognizer, bitmap: Bitmap): Reading {
        val original = Reading(bitmap, recognize(recognizer, bitmap))
        val chars = original.lines.sumOf { it.text.length }
        if (chars < MIN_CHARS_TO_TRY_ROTATION) return original // 文字がほとんどない写真は回しても無駄
        if (Orientation.looksUpright(original.lines) &&
            ReceiptDetector.detect(OcrText.toRows(original.lines)).isReceipt
        ) return original

        var best = original
        for (degrees in listOf(90, 270, 180)) {
            val rotated = ImageTools.rotate(bitmap, degrees)
            val reading = Reading(rotated, recognize(recognizer, rotated))
            if (Orientation.uprightScore(reading.lines) > Orientation.uprightScore(best.lines)) best = reading
        }
        return best
    }

    private suspend fun recognize(recognizer: TextRecognizer, bitmap: Bitmap): List<OcrLine> {
        val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(line.text, box.left, box.top, box.right, box.bottom, line.angle)
        }
    }

    private fun newRecognizer(): TextRecognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    private data class Photo(val id: Long, val uri: Uri, val dateAddedSec: Long, val takenAtMillis: Long)

    /** カメラで撮った写真 (DCIM フォルダ) のうち、[sinceSec] 以降に追加されたもの */
    private fun queryCameraPhotos(sinceSec: Long): List<Photo> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val folderColumn =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH
            else MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            folderColumn,
        )
        val result = mutableListOf<Photo>()
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Images.Media.DATE_ADDED} > ?", arrayOf(sinceSec.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val folderCol = c.getColumnIndexOrThrow(folderColumn)
            while (c.moveToNext()) {
                val folder = c.getString(folderCol).orEmpty()
                val isCamera = if (Build.VERSION.SDK_INT >= 29) folder.startsWith("DCIM/") else folder.equals("Camera", true)
                if (!isCamera) continue
                val id = c.getLong(idCol)
                val added = c.getLong(addedCol)
                val taken = c.getLong(takenCol).takeIf { it > 0 } ?: (added * 1000)
                result += Photo(id, ContentUris.withAppendedId(collection, id), added, taken)
            }
        }
        return result
    }

    /** 送信済みで 30 日以上たったレシートの画像を消す */
    private suspend fun cleanUpOld() {
        val before = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        for (r in dao.olderThan(before)) {
            if (r.sentAt != null) {
                File(r.imagePath).delete()
                dao.delete(r.mediaId)
            }
        }
    }

    companion object {
        private const val TAG = "ReceiptScanner"
        private const val MIN_CHARS_TO_TRY_ROTATION = 8
        private val mutex = Mutex()
    }
}
