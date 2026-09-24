package com.infodive.braid.companion

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * Hands a pairing request from a relay thread to whatever screen can show it,
 * and waits for a person to answer. One at a time: a second request while one
 * is on screen is refused rather than queued, so nobody can stack dialogs
 * behind the one the owner is reading.
 */
object PairingPrompt {
    class Request(val desktop: String, val from: String) {
        val answer = CompletableFuture<Boolean>()
    }

    private val current = AtomicReference<Request?>()

    @Volatile
    var listener: ((Request) -> Unit)? = null

    fun pending(): Request? = current.get()

    fun ask(desktop: String, from: String, timeoutMs: Long): Boolean {
        val request = Request(desktop, from)
        if (!current.compareAndSet(null, request)) return false
        return try {
            listener?.invoke(request)
            request.answer.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            false
        } finally {
            request.answer.complete(false)
            current.set(null)
        }
    }
}
