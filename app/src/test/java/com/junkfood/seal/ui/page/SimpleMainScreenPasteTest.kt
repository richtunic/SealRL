package com.junkfood.seal.ui.page

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleMainScreenPasteTest {
    @Test
    fun `paste leaves cursor on the next empty line`() {
        val first = insertPastedTextOnItsOwnLine(TextFieldValue(), "https://tiktok.com/one")
        val second = insertPastedTextOnItsOwnLine(first, "https://instagram.com/two")

        assertEquals(
            "https://tiktok.com/one\nhttps://instagram.com/two\n",
            second.text,
        )
        assertEquals(TextRange(second.text.length), second.selection)
    }

    @Test
    fun `single pasted character also gets its own line`() {
        val result = insertPastedTextOnItsOwnLine(TextFieldValue(), "x")

        assertEquals("x\n", result.text)
        assertEquals(TextRange(2), result.selection)
    }

    @Test
    fun `rapid repeated paste normalizes every link synchronously`() {
        var value = TextFieldValue()
        repeat(12) { index ->
            val link = "https://example.com/$index"
            val incoming =
                value.copy(
                    text = value.text + link,
                    selection = TextRange(value.text.length + link.length),
                )
            value = normalizeLikelyPasteImmediately(value, incoming)
        }

        val expected = (0 until 12).joinToString(separator = "\n", postfix = "\n") {
            "https://example.com/$it"
        }
        assertEquals(expected, value.text)
        assertEquals(TextRange(expected.length), value.selection)
    }
}
