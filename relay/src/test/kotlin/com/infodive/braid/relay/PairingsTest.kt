package com.infodive.braid.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingsTest {
    private class MemoryStorage : Pairings.Storage {
        var saved: String? = null
        override fun load() = saved
        override fun save(text: String) {
            saved = text
        }
    }

    @Test
    fun aNewKeyIs256BitsOfLowercaseHex() {
        val key = Pairings(MemoryStorage()).add("Studio")
        assertTrue(key, key.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun onlyIssuedKeysAreRecognised() {
        val pairings = Pairings(MemoryStorage())
        val key = pairings.add("Studio")
        assertTrue(pairings.isPaired(key))
        assertFalse(pairings.isPaired(key.uppercase()))
        assertFalse(pairings.isPaired(key.dropLast(1)))
        assertFalse(pairings.isPaired(""))
        assertFalse(pairings.isPaired("0".repeat(64)))
    }

    @Test
    fun theKeyItselfIsNeverStored() {
        val storage = MemoryStorage()
        val key = Pairings(storage).add("Studio")
        assertFalse(storage.saved!!.contains(key))
    }

    @Test
    fun pairingsSurviveARestart() {
        val storage = MemoryStorage()
        val key = Pairings(storage).add("Suraj's \"MacBook\"")
        val reloaded = Pairings(storage)
        assertTrue(reloaded.isPaired(key))
        assertEquals(listOf("Suraj's \"MacBook\""), reloaded.list().map { it.desktop })
    }

    @Test
    fun forgettingOneDesktopLeavesTheOthers() {
        val storage = MemoryStorage()
        val pairings = Pairings(storage)
        val studio = pairings.add("Studio")
        val laptop = pairings.add("Laptop")
        pairings.forget(pairings.list().single { it.desktop == "Studio" }.id)
        assertFalse(pairings.isPaired(studio))
        assertTrue(pairings.isPaired(laptop))
        assertFalse(Pairings(storage).isPaired(studio))
    }

    @Test
    fun aKeyTheDesktopRefusedCanBeWithdrawnWithoutTouchingOthers() {
        val storage = MemoryStorage()
        val pairings = Pairings(storage)
        val kept = pairings.add("Laptop")
        val refused = pairings.add("Studio")
        pairings.forgetKey(refused)
        assertFalse(pairings.isPaired(refused))
        assertTrue(pairings.isPaired(kept))
        assertEquals(listOf("Laptop"), Pairings(storage).list().map { it.desktop })
    }

    @Test
    fun theSameDesktopPairingTwiceHoldsTwoKeys() {
        val pairings = Pairings(MemoryStorage())
        val first = pairings.add("Studio")
        val second = pairings.add("Studio")
        assertTrue(pairings.isPaired(first))
        assertTrue(pairings.isPaired(second))
    }

    @Test
    fun unreadableStorageStartsEmptyRatherThanOpen() {
        val storage = MemoryStorage().apply { saved = "not json" }
        assertFalse(Pairings(storage).isPaired(""))
        assertEquals(0, Pairings(storage).list().size)
    }
}
