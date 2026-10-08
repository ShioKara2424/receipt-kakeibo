package com.shiokara.receiptkakeibo

import com.shiokara.receiptkakeibo.parse.OcrLine
import com.shiokara.receiptkakeibo.parse.OcrText
import com.shiokara.receiptkakeibo.parse.Orientation
import com.shiokara.receiptkakeibo.parse.ReceiptDetector
import com.shiokara.receiptkakeibo.scan.Settings
import com.shiokara.receiptkakeibo.scan.SheetPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptDetectionTest {

    private fun rows(vararg lines: String) = lines.map { OcrText.normalize(it) }

    @Test
    fun スーパーのレシートはレシートと判定する() {
        val r = ReceiptDetector.detect(
            rows(
                "サンプルマート 東京駅前店",
                "TEL 03-0000-0000",
                "2026年3月1日(日) 12:34",
                "牛乳 1000ml ¥236",
                "鶏もも肉 ¥248",
                "小計 ¥484",
                "合計 ¥484",
                "楽天ペイ ¥484",
            ),
        )
        assertTrue(r.isReceipt)
    }

    @Test
    fun 全角の数字や円記号でも判定できる() {
        val r = ReceiptDetector.detect(rows("ブロッコリー　￥１０９", "キャベツ　１０５円", "合計　￥２１４"))
        assertTrue(r.isReceipt)
    }

    @Test
    fun 円記号がバックスラッシュとして読まれても判定できる() {
        val r = ReceiptDetector.detect(rows("豆腐 \\109", "ブロッコリー \\109", "お買上計 \\218"))
        assertTrue(r.isReceipt)
    }

    @Test
    fun 適格請求書の登録番号があればレシートと判定する() {
        val r = ReceiptDetector.detect(rows("登録番号 T1234567890123", "コーラ Sサイズ 100"))
        assertTrue(r.isReceipt)
    }

    @Test
    fun 文字の少ない写真はレシートではない() {
        assertFalse(ReceiptDetector.detect(rows("STOP")).isReceipt)
        assertFalse(ReceiptDetector.detect(emptyList()).isReceipt)
    }

    @Test
    fun 値段の書かれたメニューや看板だけではレシートと判定しない() {
        val r = ReceiptDetector.detect(rows("本日のおすすめ", "からあげ定食 850円", "焼き魚定食 900円"))
        assertFalse(r.isReceipt)
    }

    @Test
    fun 左右の列に分かれた文字を同じ行にまとめる() {
        val lines = listOf(
            OcrLine("¥109", left = 300, top = 100, right = 360, bottom = 120),
            OcrLine("ブロッコリー", left = 10, top = 102, right = 150, bottom = 122),
            OcrLine("¥105", left = 300, top = 140, right = 360, bottom = 160),
            OcrLine("キャベツ", left = 10, top = 141, right = 110, bottom = 161),
        )
        assertEquals(listOf("ブロッコリー ¥109", "キャベツ ¥105"), OcrText.toRows(lines))
    }

    @Test
    fun 縦向きに読めた文字は正しい向きとみなさない() {
        val sideways = List(5) { OcrLine("合計 ¥1,234", 0, it * 30, 20, it * 30 + 200, angle = 90f) }
        val upright = List(5) { OcrLine("合計 ¥1,234", 0, it * 30, 200, it * 30 + 20, angle = 1f) }
        assertFalse(Orientation.looksUpright(sideways))
        assertTrue(Orientation.looksUpright(upright))
        assertTrue(Orientation.uprightScore(upright) > Orientation.uprightScore(sideways))
    }

    @Test
    fun チャットのURLを確認する() {
        assertTrue(Settings.isValidChatUrl("https://claude.ai/chat/0b1c2d3e-aaaa-bbbb-cccc-1234567890ab"))
        assertTrue(Settings.isValidChatUrl(" https://claude.ai/project/abc123 "))
        assertFalse(Settings.isValidChatUrl("https://claude.ai/share/abc"))
        assertFalse(Settings.isValidChatUrl("claude.ai/chat/abc"))
        assertFalse(Settings.isValidChatUrl(""))
    }

    @Test
    fun スプレッドシートのURLを確認する() {
        assertTrue(Settings.isValidSheetUrl("https://docs.google.com/spreadsheets/d/1AbC_dEf-123/edit#gid=0"))
        assertFalse(Settings.isValidSheetUrl("https://docs.google.com/document/d/1AbC/edit"))
        assertFalse(Settings.isValidSheetUrl(""))
    }

    @Test
    fun 指示文にスプレッドシートのURLを埋め込む() {
        val url = "https://docs.google.com/spreadsheets/d/1AbC/edit"
        val text = SheetPrompt.build(SheetPrompt.DEFAULT, " $url ")
        assertTrue(text.contains(url))
        assertFalse(text.contains(SheetPrompt.PLACEHOLDER))
    }
}
