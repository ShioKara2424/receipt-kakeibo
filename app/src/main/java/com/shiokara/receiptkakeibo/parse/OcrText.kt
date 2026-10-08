package com.shiokara.receiptkakeibo.parse

import java.text.Normalizer

/** 文字認識で得た 1 行と、その位置 (画像上のピクセル座標) */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** 行の傾き (度)。0 で水平 */
    val angle: Float = 0f,
) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

object OcrText {
    /** 全角数字・全角記号・半角カナを揃える (例: "￥１，２３４" → "¥1,234") */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .replace('\\', '¥') // レシートの円記号はバックスラッシュとして認識されやすい
            .replace('▲', '-')
            .replace('△', '-')
            .replace('−', '-')

    /**
     * 文字認識はレシートの左右の列 (商品名と金額) を別々の行として返すことがある。
     * 縦位置が重なる行を 1 行にまとめ、左から順に並べて「見た目どおりの行」を作る。
     */
    fun toRows(lines: List<OcrLine>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val row = rows.lastOrNull()
            if (row != null && overlapsVertically(row, line)) row.add(line) else rows.add(mutableListOf(line))
        }
        return rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { normalize(it.text).trim() } }
    }

    private fun overlapsVertically(row: List<OcrLine>, line: OcrLine): Boolean {
        val top = row.minOf { it.top }
        val bottom = row.maxOf { it.bottom }
        val overlap = minOf(bottom, line.bottom) - maxOf(top, line.top)
        val minHeight = minOf(bottom - top, line.height).coerceAtLeast(1)
        // 行の中心が既存の行の範囲内にあり、高さの半分以上が重なっていれば同じ行
        return line.centerY in top..bottom && overlap * 2 >= minHeight
    }
}
