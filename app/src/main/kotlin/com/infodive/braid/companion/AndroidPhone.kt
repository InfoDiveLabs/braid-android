package com.infodive.braid.companion

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.infodive.braid.relay.Credentials
import com.infodive.braid.relay.LaneTable
import com.infodive.braid.relay.Pairings
import com.infodive.braid.relay.Phone
import com.infodive.braid.relay.Route
import com.infodive.braid.relay.Router
import java.security.SecureRandom

class AndroidPhone(private val context: Context) : Phone, Router {
    val pairings = Pairings(PreferenceStorage(context, "pairings"))
    val table = LaneTable()
    val lanes = NetworkLanes(context, table)

    /** Reaches the person when no screen is open to show the pairing dialog. */
    @Volatile
    var onPairingRequested: ((PairingPrompt.Request) -> Unit)? = null

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

    override fun lanes() = table.offered()

    override fun isPaired(key: String) = pairings.isPaired(key)

    override fun pair(desktop: String, from: String): String? {
        val allowed = PairingPrompt.ask(desktop, from, PAIR_TIMEOUT_MS) { onPairingRequested?.invoke(it) }
        return if (allowed) pairings.add(desktop) else null
    }

    override fun route(credentials: Credentials?): Route = table.route(credentials, pairings::isPaired)

    private class PreferenceStorage(context: Context, private val name: String) : Pairings.Storage {
        private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        override fun load(): String? = prefs.getString(name, null)
        override fun save(text: String) {
            prefs.edit().putString(name, text).commit()
        }
    }

    companion object {
        /** Inside the desktop's 60 seconds, so a refusal by timeout reaches it as an answer rather than a dropped call. */
        private const val PAIR_TIMEOUT_MS = 55_000L
    }
}
