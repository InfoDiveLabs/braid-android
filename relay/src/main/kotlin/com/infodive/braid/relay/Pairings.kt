package com.infodive.braid.relay

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The desktops allowed to use this phone, one key each so that forgetting one
 * leaves the others working.
 *
 * Only a SHA-256 of each key is kept. The phone never needs a key back, only
 * to recognise one, and a hash of 256 random bits cannot be reversed, so
 * nothing secret is ever written to storage.
 */
class Pairings(private val storage: Storage) {
    interface Storage {
        fun load(): String?
        fun save(text: String)
    }

    data class Pairing(val id: String, val desktop: String, val hash: String, val pairedAt: Long)

    private val random = SecureRandom()
    private val pairings = ArrayList(decode(storage.load()))

    @Synchronized
    fun list(): List<Pairing> = pairings.toList()

    /** Issues a new key for [desktop] and returns it. The only time the key exists outside the desktop. */
    @Synchronized
    fun add(desktop: String): String {
        val key = hex(32)
        pairings.add(Pairing(hex(8), desktop, sha256(key), System.currentTimeMillis()))
        persist()
        return key
    }

    @Synchronized
    fun forget(id: String) {
        pairings.removeAll { it.id == id }
        persist()
    }

    /** Withdraws a key that was issued but never accepted, such as one a desktop refused during registration. */
    @Synchronized
    fun forgetKey(key: String) {
        val hash = sha256(key)
        if (pairings.removeAll { it.hash == hash }) persist()
    }

    fun isPaired(key: String): Boolean {
        val candidate = sha256(key).toByteArray()
        return list().fold(false) { found, p -> MessageDigest.isEqual(candidate, p.hash.toByteArray()) or found }
    }

    private fun persist() {
        storage.save(
            Json.obj(
                "pairings" to Json.array(
                    pairings.map {
                        Json.obj("id" to it.id, "desktop" to it.desktop, "hash" to it.hash, "paired_at" to it.pairedAt)
                    },
                ),
            ),
        )
    }

    private fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).toHex()

    private companion object {
        fun sha256(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).toHex()

        fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        fun decode(text: String?): List<Pairing> {
            val entries = text?.let(Json::parseObject)?.get("pairings") as? List<*> ?: return emptyList()
            return entries.mapNotNull { entry ->
                val map = entry as? Map<*, *> ?: return@mapNotNull null
                Pairing(
                    id = map["id"] as? String ?: return@mapNotNull null,
                    desktop = map["desktop"] as? String ?: return@mapNotNull null,
                    hash = map["hash"] as? String ?: return@mapNotNull null,
                    pairedAt = (map["paired_at"] as? Double)?.toLong() ?: 0,
                )
            }
        }
    }
}
