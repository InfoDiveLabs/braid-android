package com.infodive.braid.companion

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import com.infodive.braid.relay.RelayServer
import com.infodive.braid.relay.Upstream
import java.net.NetworkInterface

/**
 * Task 2 shell: runs the proxy on port 8710 over the default network while
 * this screen is open. No pairing yet, so nothing here is safe to leave
 * running on a shared network.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val status = try {
            Relay.ensureStarted()
            "Relay listening on port ${RelayServer.DEFAULT_PORT}\n\n" + addresses().joinToString("\n")
        } catch (e: Exception) {
            "Relay failed to start: $e"
        }
        setContentView(TextView(this).apply {
            text = status
            textSize = 16f
            setPadding(48, 160, 48, 48)
            setTextIsSelectable(true)
        })
    }

    private fun addresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif -> nif.inetAddresses.toList().map { "${nif.name}  ${it.hostAddress}" } }
}

/** One relay per process, so recreating the activity does not rebind the port. */
private object Relay {
    private var server: RelayServer? = null

    @Synchronized
    fun ensureStarted() {
        if (server == null) server = RelayServer(Upstream.DIRECT).also { it.start() }
    }
}
