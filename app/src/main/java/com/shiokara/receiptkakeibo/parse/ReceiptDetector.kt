package com.shiokara.receiptkakeibo.parse

import kotlin.math.abs

/**
 * 写真がレシートかどうかを、文字認識の結果だけで判定する。
 * 外部に送る前の「関所」なので、迷ったら送らない (= レシートではない) 側に倒す。
 */
object ReceiptDetector {

    /** あれば強くレシートらしいと言える語 */
    private val STRONG = listOf("合計", "小計", "お買上", "お買い上げ", "現計", "領収", "レシート", "お釣", "釣銭", "おつり")

    /** レシートによく出る語 */
    private val WEAK = listOf(
        "消費税", "内税", "外税", "税込", "税抜", "対象", "点数", "お預", "預り", "登録番号",
        "軽減税率", "税率", "TEL", "電話", "レジ", "取引", "ポイント", "支払",
    )

    /** 行末が金額で終わる行 (例: "ブロッコリー ¥109", "合計 1,234円") */
    val PRICE_AT_END = Regex("""(?:¥\s*)?(?:\d{1,3}(?:,\d{3})+|\d{2,6})\s*円?\s*[*※軽外内]?\s*$""")

    private val INVOICE_NUMBER = Regex("""T\d{13}""")

    data class Result(val isReceipt: Boolean, val score: Int)

    fun detect(rows: List<String>): Result {
        val text = rows.joinToString("\n")
        val upper = text.uppercase()
        val strongHits = STRONG.count { text.contains(it) }
        val weakHits = WEAK.count { upper.contains(it.uppercase()) }
        val priceRows = rows.count { PRICE_AT_END.containsMatchIn(it) }
        val yenMarks = text.count { it == '¥' } + Regex("""\d\s*円""").findAll(text).count()
        val invoice = INVOICE_NUMBER.containsMatchIn(text)

        val score = strongHits * 3 + weakHits + minOf(priceRows, 8) + minOf(yenMarks, 5) + if (invoice) 4 else 0
        val isReceipt = when {
            strongHits >= 1 && priceRows >= 2 -> true
            invoice && priceRows >= 1 -> true
            weakHits >= 3 && priceRows >= 2 && yenMarks >= 1 -> true
            else -> false
        }
        return Result(isReceipt, score)
    }
}

/** 写真の向きを判定するための指標 */
object Orientation {
    /**
     * 正しい向きで読めているほど大きくなる値。
     * 水平に近い行の文字数の合計 (斜めや縦に並んだ行は数えない)。
     */
    fun uprightScore(lines: List<OcrLine>): Int =
        lines.filter { abs(it.angle) <= 20f }.sumOf { it.text.count { c -> !c.isWhitespace() } }

    /** 回転せずに読めていると判断してよいか */
    fun looksUpright(lines: List<OcrLine>): Boolean {
        val total = lines.sumOf { it.text.length }
        if (total < 15) return false
        return uprightScore(lines) * 10 >= total * 8
    }
}
