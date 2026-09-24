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
            "https://tiktok.com/one\nhttps://instagram.com/two\n ",
            second.text,
        )
        assertEquals(TextRange(second.text.length), second.selection)
    }

    @Test
    fun `single pasted character also gets its own line`() {
        val result = insertPastedTextOnItsOwnLine(TextFieldValue(), "x")

        assertEquals("x\n ", result.text)
        assertEquals(TextRange(3), result.selection)
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

        val expected = (0 until 12).joinToString(separator = "\n", postfix = "\n ") {
            "https://example.com/$it"
        }
        assertEquals(expected, value.text)
        assertEquals(TextRange(expected.length), value.selection)
    }

    @Test
    fun `Samsung clipboard fallback detects a single pasted character`() {
        val previous = insertPastedTextOnItsOwnLine(TextFieldValue(), "https://example.com/one")
        val incoming =
            previous.copy(
                text = previous.text + "x",
                selection = TextRange(previous.text.length + 1),
            )

        val result = normalizeLikelyPasteImmediately(previous, incoming, clipboardText = "x")

        assertEquals("https://example.com/one\nx\n ", result.text)
        assertEquals(TextRange(result.text.length), result.selection)
    }

    @Test
    fun `normal single character typing is not treated as paste`() {
        val previous = TextFieldValue("https://example.com/", TextRange(20))
        val incoming = TextFieldValue("https://example.com/x", TextRange(21))

        val result = normalizeLikelyPasteImmediately(previous, incoming, clipboardText = "other")

        assertEquals(incoming, result)
    }

    @Test
    fun `clipboard paste replaces selected text and leaves next visual line`() {
        val previous = TextFieldValue("one old two", TextRange(4, 7))
        val incoming = TextFieldValue("one new two", TextRange(7))

        val result = normalizeLikelyPasteImmediately(previous, incoming, clipboardText = "new")

        assertEquals("one \nnew\n two", result.text)
        assertEquals(TextRange(9), result.selection)
    }

    @Test
    fun `glued links from clipboard are separated visually`() {
        val result =
            insertPastedTextOnItsOwnLine(
                TextFieldValue(),
                "https://example.com/onehttps://example.com/two",
            )

        assertEquals("https://example.com/one\nhttps://example.com/two\n ", result.text)
        assertEquals(TextRange(result.text.length), result.selection)
    }
}
