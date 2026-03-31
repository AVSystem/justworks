package com.avsystem.justworks.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentTypeTest {
    @Test
    fun `ContentType entries are ordered by priority for content negotiation`() {
        assertEquals(
            listOf(
                ContentType.MULTIPART_FORM_DATA,
                ContentType.FORM_URL_ENCODED,
                ContentType.JSON_CONTENT_TYPE,
            ),
            ContentType.entries,
            "ContentType declaration order matters: SpecParser.find picks the first matching entry, " +
                "so more specific content types must come before JSON",
        )
    }
}
