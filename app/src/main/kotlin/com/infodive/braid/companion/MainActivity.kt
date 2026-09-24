package com.infodive.braid.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.infodive.braid.relay.ControlPlane
import com.infodive.braid.relay.RelayServer
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var paired: LinearLayout
    private lateinit var lanes: LinearLayout
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val status = try {
            Relay.ensureStarted(applicationContext)
            "Relay listening on port ${RelayServer.DEFAULT_PORT}\n\n" + addresses().joinToString("\n")
        } catch (e: Exception) {
            "Relay failed to start: $e"
        }
        paired = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lanes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 160, 48, 48)
            addView(TextView(context).apply {
                text = status
                textSize = 16f
                setTextIsSelectable(true)
            })
            addView(TextView(context).apply {
                text = "Networks to share"
                textSize = 20f
                setPadding(0, 64, 0, 16)
            })
            addView(lanes)
            addView(TextView(context).apply {
                text = "Paired desktops"
                textSize = 20f
                setPadding(0, 64, 0, 16)
            })
            addView(paired)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        showPaired()
        showLanes()
        Relay.phone?.lanes?.onChange = { runOnUiThread(::showLanes) }
        PairingPrompt.listener = { request -> runOnUiThread { prompt(request) } }
        PairingPrompt.pending()?.let(::prompt)
    }

    override fun onPause() {
        PairingPrompt.listener = null
        Relay.phone?.lanes?.onChange = null
        dialog?.dismiss()
        dialog = null
        super.onPause()
    }

    /** The name is whatever the other side chose to send, so it is quoted, shortened, and shown beside the address it came from. */
    private fun prompt(request: PairingPrompt.Request) {
        if (request.answer.isDone) return
        dialog?.dismiss()
        val name = request.desktop.take(60).replace(Regex("\\p{Cntrl}"), " ")
        dialog = AlertDialog.Builder(this)
            .setTitle("Pair with “$name”?")
            .setMessage(
                "A computer at ${request.from} wants to download through this phone.\n\n" +
                    "Once paired it can use the networks you switch on here, including mobile data.\n\n" +
                    "Only allow this if you just pressed Pair on your own computer.",
            )
            .setPositiveButton("Allow") { _, _ -> request.answer.complete(true) }
            .setNegativeButton("Deny") { _, _ -> request.answer.complete(false) }
            .setOnCancelListener { request.answer.complete(false) }
            .show()
        request.answer.whenComplete { _, _ ->
            runOnUiThread {
                dialog?.dismiss()
                showPaired()
            }
        }
    }

    private fun showLanes() {
        val phone = Relay.phone ?: return
        lanes.removeAllViews()
        val offered = phone.lanes().associateBy { it.id }
        for ((id, name) in listOf(NetworkLanes.CELL to "Mobile data", NetworkLanes.WIFI to "Wi-Fi")) {
            val lane = offered[id]
            lanes.addView(Switch(this).apply {
                text = name + "\n" + when {
                    !phone.lanes.isEnabled(id) -> "Off"
                    lane == null -> "Waiting for the network"
                    else -> listOfNotNull(lane.egress, lane.egress6).joinToString("\n").ifEmpty { "Finding public address" }
                }
                isChecked = phone.lanes.isEnabled(id)
                setOnCheckedChangeListener { _, on -> phone.lanes.setEnabled(id, on) }
                setPadding(0, 16, 0, 16)
            })
        }
    }

    private fun showPaired() {
        paired.removeAllViews()
        val all = Relay.phone?.pairings?.list().orEmpty()
        if (all.isEmpty()) {
            paired.addView(TextView(this).apply { text = "None. Press Pair on a computer running Braid." })
        }
        for (p in all) {
            paired.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(context).apply {
                    text = "${p.desktop}\npaired ${DateUtils.getRelativeTimeSpanString(p.pairedAt)}"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Button(context).apply {
                    text = "Forget"
                    setOnClickListener {
                        Relay.phone?.pairings?.forget(p.id)
                        showPaired()
                    }
                })
            })
        }
    }

    private fun addresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif -> nif.inetAddresses.toList().map { "${nif.name}  ${it.hostAddress}" } }
}

/** One relay per process, so recreating the activity does not rebind the port. */
private object Relay {
    var phone: AndroidPhone? = null
        private set
    private var server: RelayServer? = null

    @Synchronized
    fun ensureStarted(context: Context) {
        if (server == null) {
            val device = AndroidPhone(context)
            device.lanes.start()
            phone = device
            server = RelayServer(device, control = ControlPlane(device)).also { it.start() }
        }
    }
}
