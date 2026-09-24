package com.infodive.braid.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonTest {
    @Test
    fun quotesWhatAPersonCanTypeIntoADeviceName() {
        assertEquals("\"Suraj = \\\"phone\\\"\"", Json.quote("Suraj = \"phone\""))
        assertEquals("\"a\\\\b\\nc\\td\\r\"", Json.quote("a\\b\nc\td\r"))
        assertEquals("\"\\u0001\\u001f\"", Json.quote("\u0001\u001f"))
        assertEquals("\"Pixel 📱 é\"", Json.quote("Pixel 📱 é"))
    }

    @Test
    fun objectOmitsNullFields() {
        assertEquals("{\"a\":\"x\",\"n\":1}", Json.obj("a" to "x", "skip" to null, "n" to 1))
    }

    @Test
    fun parsesWhatSerdeWrites() {
        assertEquals(mapOf("desktop" to "Suraj's MacBook"), Json.parseObject("{\"desktop\":\"Suraj's MacBook\"}"))
    }

    @Test
    fun roundTripsEveryAwkwardName() {
        val names = listOf("Suraj = \"phone\"", "back\\slash", "new\nline", "\u0000nul", "Pixel 📱 é", "")
        for (name in names) {
            assertEquals(name, Json.parseObject(Json.obj("desktop" to name))!!["desktop"])
        }
    }

    @Test
    fun decodesEscapesIncludingSurrogatePairs() {
        assertEquals("é📱/", Json.parseObject("{\"k\":\"\\u00e9\\ud83d\\udcf1\\/\"}")!!["k"])
    }

    @Test
    fun toleratesWhitespaceAndFieldsItDoesNotKnow() {
        val parsed = Json.parseObject(" {\n \"desktop\" : \"Studio\", \"extra\": {\"a\": [1, 2.5e3, true, null]}, \"n\": -3 } ")!!
        assertEquals("Studio", parsed["desktop"])
    }

    @Test
    fun rejectsWhatIsNotOneObject() {
        listOf("", "[]", "\"s\"", "{", "{\"a\"}", "{\"a\":}", "{\"a\":\"x\"} trailing", "{\"a\":\"\\q\"}", "{'a':'x'}")
            .forEach { assertNull(it, Json.parseObject(it)) }
    }
}
