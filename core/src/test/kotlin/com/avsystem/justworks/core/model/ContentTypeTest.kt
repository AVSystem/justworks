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
                ContentType.TEXT_PLAIN,
                ContentType.OCTET_STREAM,
            ),
            ContentType.entries,
            "ContentType declaration order matters: SpecParser.find picks the first matching entry, " +
                "so more specific content types must come before JSON",
        )
    }

    @Test
    fun `request types keep JSON and form priority order`() {
        assertEquals(
            listOf(
                ContentType.MULTIPART_FORM_DATA,
                ContentType.FORM_URL_ENCODED,
                ContentType.JSON_CONTENT_TYPE,
            ),
            ContentType.REQUEST_TYPES,
            "Only these content types are accepted as request bodies",
        )
    }
}
