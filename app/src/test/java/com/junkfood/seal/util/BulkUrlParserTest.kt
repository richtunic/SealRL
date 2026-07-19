package com.junkfood.seal.util

import org.junit.Assert.assertEquals
import org.junit.Test

class BulkUrlParserTest {
    @Test
    fun `single pasted character gets a trailing newline`() {
        assertEquals("x\n", BulkUrlParser.addTrailingNewlineAfterUrlPaste("", "x"))
    }

    @Test
    fun `arbitrary pasted text gets a trailing newline`() {
        assertEquals(
            "existing\nanything\n",
            BulkUrlParser.addTrailingNewlineAfterUrlPaste("existing", "existinganything"),
        )
    }

    @Test
    fun `pasted text does not duplicate an existing trailing newline`() {
        assertEquals("anything\n", BulkUrlParser.addTrailingNewlineAfterUrlPaste("", "anything\n"))
    }
}
