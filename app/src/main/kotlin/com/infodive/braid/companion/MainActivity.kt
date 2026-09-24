package com.infodive.braid.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.infodive.braid.relay.RelayServer
import java.net.Inet6Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var column: LinearLayout
    private var shown: String? = null
    private var dialog: AlertDialog? = null
    private val main = Handler(Looper.getMainLooper())
    private val redraw: () -> Unit = { main.post(::render) }
    private val tick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, 1_000)
        }
    }

    private val phone get() = RelayHost.phone(this)
    private val density get() = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(0xF6, 0xF6, 0xF4))
            addView(column)
        })
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (RelayService.isServing(this) && !RelayHost.isRunning) RelayService.start(this)
    }

    override fun onResume() {
        super.onResume()
        shown = null
        RelayHost.watch(redraw)
        main.post(tick)
        PairingPrompt.listener = { request -> main.post { prompt(request) } }
        PairingPrompt.pending()?.let(::prompt)
    }

    override fun onPause() {
        RelayHost.unwatch(redraw)
        main.removeCallbacks(tick)
        PairingPrompt.listener = null
        dialog?.dismiss()
        dialog = null
        super.onPause()
    }

    /** What the screen shows, so it is rebuilt only when that changes and a tap is never lost to a redraw. */
    private fun state(): String {
        val lanes = phone.lanes
        return listOf(
            RelayHost.isRunning,
            lanes.ids.map { listOf(it, lanes.isWanted(it), lanes.isExhausted(it), lanes.isOffered(it), lanes.limitMb(it), RelayHost.size(RelayHost.used(it))) },
            phone.pairings.list().map { it.id },
            if (RelayHost.isRunning) addresses() else emptyList(),
        ).toString()
    }

    private fun render() {
        val now = state()
        if (now == shown) return
        shown = now
        column.removeAllViews()
        column.addView(text("Braid", 30, bold = true))
        column.addView(text("Lend this phone's connection to Braid on your computer.", 15, muted = true).padTop(4))

        val serving = RelayHost.isRunning
        column.addView(card {
            addView(row(
                text("Share this phone", 18, bold = true),
                Switch(context).apply {
                    isChecked = serving
                    setOnCheckedChangeListener { _, on -> if (on) RelayService.start(context) else RelayService.stop(context) }
                },
            ))
            addView(text(
                if (serving) "On. Computers on this network can find this phone as “${phone.name}”. " +
                    "Only computers you have paired can use it."
                else "Off. Computers can't see or use this phone.",
                14, muted = true,
            ).padTop(6))
        }.padTop(20))

        column.addView(heading("Networks"))
        column.addView(text("Paired computers can download through the networks you switch on here.", 14, muted = true))
        for (lane in phone.lanes.ids) column.addView(laneCard(lane, serving).padTop(10))

        column.addView(heading("Paired computers"))
        val pairings = phone.pairings.list()
        if (pairings.isEmpty()) {
            column.addView(card {
                addView(text("No computers yet.", 16, bold = true))
                addView(text(
                    "On your computer, open Braid, go to Settings → Phones and press Scan, then Pair. " +
                        "This phone will ask you to allow it.",
                    14, muted = true,
                ).padTop(4))
            }.padTop(10))
        }
        for (p in pairings) {
            column.addView(card {
                addView(row(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(text(p.desktop, 16, bold = true))
                        addView(text("Paired ${DateUtils.getRelativeTimeSpanString(p.pairedAt)}", 13, muted = true))
                    },
                    Button(context).apply {
                        text = "Forget"
                        setOnClickListener { RelayHost.forget(context, p.id) }
                    },
                ))
            }.padTop(10))
        }

        if (serving) {
            addresses().takeIf { it.isNotEmpty() }?.let {
                column.addView(heading("If Scan can't find this phone"))
                column.addView(text("Add it on your computer by address:", 14, muted = true))
                it.forEach { address -> column.addView(text(address, 14).apply { setTextIsSelectable(true) }.padTop(4)) }
            }
        }
    }

    private fun laneCard(lane: String, serving: Boolean): View {
        val lanes = phone.lanes
        val wanted = lanes.isWanted(lane)
        val used = RelayHost.used(lane)
        val limitMb = lanes.limitMb(lane)
        val status = when {
            !wanted -> "Off"
            !serving -> "Will be shared when sharing is on"
            lanes.isExhausted(lane) -> "Limit reached, so it is not being shared. Raise the limit, or turn sharing off and on to start a new session."
            lanes.isOffered(lane) -> "Shared · ${RelayHost.size(used)} used this session"
            else -> "Waiting for ${lanes.label(lane).lowercase()}"
        }
        return card {
            addView(row(
                text(if (lane == NetworkLanes.CELL) "Mobile data" else "Wi-Fi", 17, bold = true),
                Switch(context).apply {
                    isChecked = wanted
                    setOnCheckedChangeListener { _, on -> RelayHost.setWanted(context, lane, on) }
                },
            ))
            addView(text(status, 14, muted = true).padTop(4))
            if (lane == NetworkLanes.CELL) {
                addView(row(
                    text(if (limitMb > 0) "Limit: ${RelayHost.size(limitMb * RelayHost.MB)} per session" else "No limit", 14),
                    Button(context).apply {
                        text = "Set limit"
                        setOnClickListener { askLimit(lane) }
                    },
                ).padTop(4))
            } else {
                addView(text(
                    "If your computer is on this same Wi-Fi, Braid will show this as a duplicate and leave it off.",
                    13, muted = true,
                ).padTop(4))
            }
        }
    }

    private fun askLimit(lane: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Megabytes, empty for no limit"
            phone.lanes.limitMb(lane).takeIf { it > 0 }?.let { setText(it.toString()) }
        }
        AlertDialog.Builder(this)
            .setTitle("Mobile data limit")
            .setMessage("Sharing over mobile data stops once this much has been used since sharing was turned on.")
            .setView(LinearLayout(this).apply {
                setPadding(dp(20), 0, dp(20), 0)
                addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton("Save") { _, _ -> phone.lanes.setLimitMb(lane, input.text.toString().toLongOrNull() ?: 0) }
            .setNegativeButton("Cancel", null)
            .show()
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
        request.answer.whenComplete { _, _ -> main.post { dialog?.dismiss(); render() } }
    }

    /** Global addresses only: a link-local one needs a scope id that means nothing on the computer. */
    private fun addresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.name.startsWith("rmnet") && !it.name.startsWith("v4-") && !it.name.startsWith("dummy") }
        .flatMap { it.inetAddresses.toList() }
        .filter { !it.isLinkLocalAddress && !it.isLoopbackAddress }
        .map { if (it is Inet6Address) "[${it.hostAddress!!.substringBefore('%')}]:${RelayServer.DEFAULT_PORT}" else "${it.hostAddress}:${RelayServer.DEFAULT_PORT}" }

    private fun heading(value: String) = text(value, 20, bold = true).apply { setPadding(0, dp(28), 0, dp(6)) }

    private fun text(value: String, size: Int, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(if (muted) Color.rgb(0x5F, 0x63, 0x68) else Color.rgb(0x1F, 0x1F, 0x1F))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(build: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(14).toFloat()
        }
        build()
    }

    private fun row(start: View, end: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(start, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(end)
    }

    private fun <T : View> T.padTop(value: Int): T = apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(value) }
    }
}
