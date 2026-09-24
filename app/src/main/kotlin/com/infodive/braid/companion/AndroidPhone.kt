package com.infodive.braid.companion

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.infodive.braid.relay.Lane
import com.infodive.braid.relay.Phone
import java.security.SecureRandom

class AndroidPhone(private val context: Context) : Phone {
    override val name: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Generated once and kept, so the desktop recognises this phone across renames and new addresses. */
    override val deviceId: String by lazy {
        val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: hex(16).also { prefs.edit().putString("device_id", it).commit() }
    }

    /** Honest about what Task 2's proxy does: it leaves over whatever network is default. */
    override fun lanes() = listOf(Lane(id = "default", kind = null, label = "Default network"))

    override fun pair(desktop: String): String? = null

    private fun hex(bytes: Int): String =
        ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
}
