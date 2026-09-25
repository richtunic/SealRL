package com.junkfood.seal.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstagramEmbedParserTest {
    @Test
    fun `public carousel preserves every photo in order`() {
        val html = """
            <script>s.handle({"payload":{"gql_data":{"shortcode_media":{
              "shortcode":"DdrlfpdCId6","owner":{"username":"upminaa"},
              "edge_sidecar_to_children":{"edges":[
                {"node":{"id":"one","is_video":false,"display_url":"https://scontent.cdninstagram.com/one.jpg?token=1"}},
                {"node":{"id":"two","is_video":false,"display_url":"https://scontent.cdninstagram.com/two.jpg?token=2"}}
              ]}
            }}}});</script>
        """.trimIndent()

        val media = InstagramEmbedParser.parse(html, "DdrlfpdCId6")!!
        assertEquals(listOf("one", "two"), media.map { it.id })
        assertEquals(listOf("Foto 1", "Foto 2"), media.map { it.title })
        assertEquals("upminaa", media.first().author)
    }

    @Test
    fun `rejects partial or unrelated posts`() {
        val html = """<script>s.handle({"gql_data":{"shortcode_media":{
            "shortcode":"other","display_url":"https://example.com/image.jpg"
        }}});</script>"""
        assertNull(InstagramEmbedParser.parse(html, "DdrlfpdCId6"))
    }

    @Test
    fun `provided public post has sixteen photos when live fixture is available`() {
        val fixture = System.getenv("INSTAGRAM_EMBED_FIXTURE") ?: return
        val media = InstagramEmbedParser.parse(File(fixture).readText(), "DdrlfpdCId6")!!
        assertEquals(16, media.size)
        assertEquals(16, media.count { !it.isVideo })
    }
}
