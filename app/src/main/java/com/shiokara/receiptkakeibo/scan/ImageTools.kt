package com.shiokara.receiptkakeibo.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

object ImageTools {
    /** 送る画像の長辺。Claude が細かい文字まで読める大きさにとどめ、容量を抑える */
    const val MAX_EDGE = 2000

    /** 写真を読み込み、撮影時の向き情報 (EXIF) に合わせて回転し、長辺 [maxEdge] 以下に縮小する */
    fun load(context: Context, uri: Uri, maxEdge: Int = MAX_EDGE): Bitmap? {
        val resolver = context.contentResolver
        // 大きさだけを先に調べる (この呼び出しは画像を作らず常に null を返すので、戻り値は見ない)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val exifDegrees = resolver.openInputStream(uri)?.use {
            ExifInterface(it).rotationDegrees
        } ?: 0
        return scaleDown(rotate(decoded, exifDegrees), maxEdge)
    }

    /** 時計回りに [degrees] 度回転する (0 なら同じものを返す) */
    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return bitmap
        val m = Matrix().apply { postRotate(d.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val edge = max(bitmap.width, bitmap.height)
        if (edge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

    fun saveJpeg(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    /** 一覧表示用の小さな画像 */
    fun thumbnail(path: String, maxEdge: Int = 320): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
