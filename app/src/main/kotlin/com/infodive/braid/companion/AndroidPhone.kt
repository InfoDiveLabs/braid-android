package com.infodive.braid.companion

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.infodive.braid.relay.Credentials
import com.infodive.braid.relay.Lane
import com.infodive.braid.relay.Pairings
import com.infodive.braid.relay.Phone
import com.infodive.braid.relay.Route
import com.infodive.braid.relay.Router
import com.infodive.braid.relay.Upstream
import java.security.SecureRandom

class AndroidPhone(private val context: Context) : Phone, Router {
    val pairings = Pairings(PreferenceStorage(context, "pairings"))

    override val name: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Generated once and kept, so the desktop recognises this phone across renames and new addresses. */
    override val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: ByteArray(16).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it) }
            .also { prefs.edit().putString("device_id", it).commit() }
    }

    /** Honest about what the proxy does until lanes bind to networks: it leaves over whatever network is default. */
    override fun lanes() = listOf(Lane(id = DEFAULT_LANE, kind = null, label = "Default network"))

    override fun isPaired(key: String) = pairings.isPaired(key)

    override fun pair(desktop: String, from: String): String? =
        if (PairingPrompt.ask(desktop, from, PAIR_TIMEOUT_MS)) pairings.add(desktop) else null

    /** A lane this build cannot honestly serve is unavailable, never quietly served over another network. */
    override fun route(credentials: Credentials?): Route = when {
        credentials == null || !pairings.isPaired(credentials.key) -> Route.Refused
        credentials.lane != DEFAULT_LANE -> Route.Unavailable
        else -> Route.Via(Upstream.DIRECT)
    }

    private class PreferenceStorage(context: Context, private val name: String) : Pairings.Storage {
        private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        override fun load(): String? = prefs.getString(name, null)
        override fun save(text: String) {
            prefs.edit().putString(name, text).commit()
        }
    }

    companion object {
        const val DEFAULT_LANE = "default"

        /** Inside the desktop's 60 seconds, so a refusal by timeout reaches it as an answer rather than a dropped call. */
        private const val PAIR_TIMEOUT_MS = 55_000L
    }
}
