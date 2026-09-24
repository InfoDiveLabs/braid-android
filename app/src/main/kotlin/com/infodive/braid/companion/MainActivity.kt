package com.infodive.braid.companion

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = getColor(id)
    private val isNight get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(column)
        }
        val scrim = View(this).apply { setBackgroundColor(color(R.color.background)) }
        val root = FrameLayout(this).apply {
            addView(scroll)
            addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0))
        }
        // Android 15 draws every app edge to edge, so the page would scroll under the clock without this strip.
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            column.setPadding(dp(20), top + dp(20), dp(20), insets.systemWindowInsetBottom + dp(32))
            scrim.layoutParams = scrim.layoutParams.apply { height = top }
            insets
        }
        setContentView(root)
        styleSystemBars()
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

    /** Dark icons on the light theme, light ones on the dark, since the bars are transparent over the page. */
    private fun styleSystemBars() {
        val light = !isNight
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) bars else 0, bars)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (light) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                0
            }
        }
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
        val serving = RelayHost.isRunning

        column.addView(hero(serving))

        column.addView(sectionTitle("Networks"))
        column.addView(group(phone.lanes.ids.map { laneRow(it, serving) }))
        column.addView(footnote("Paired computers can download through the networks you switch on."))

        column.addView(sectionTitle("Computers"))
        val pairings = phone.pairings.list()
        column.addView(group(
            if (pairings.isEmpty()) listOf(emptyComputers())
            else pairings.map { p ->
                row(
                    glyph(R.drawable.ic_laptop, active = serving),
                    lines(p.desktop, "Paired ${DateUtils.getRelativeTimeSpanString(p.pairedAt)}"),
                    textButton("Forget", color(R.color.danger)) { RelayHost.forget(this, p.id) },
                )
            },
        ))
        if (pairings.isNotEmpty()) column.addView(footnote(PAIRING_STEPS))

        if (serving) {
            val found = addresses()
            if (found.isNotEmpty()) {
                column.addView(sectionTitle("If Scan can’t find this phone"))
                column.addView(group(listOf(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    addView(label("Add it on your computer by typing this address:", 14, muted = true))
                    found.forEach { addView(label(it, 15).apply { setTextIsSelectable(true); tabular(); setPadding(0, dp(6), 0, 0) }) }
                })))
            }
        }
    }

    /** The brand stays constant; the headline under it carries the state. */
    private fun hero(serving: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.braid_tile)
                contentDescription = "Braid"
            }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Braid", 22, weight = Typeface.BOLD))
                addView(label("Phone companion", 14, muted = true))
            })
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color(if (serving) R.color.live else R.color.track))
                }
            }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
            addView(label(if (serving) "Sharing is on" else "Sharing is off", 32, weight = Typeface.BOLD))
        }, spaced(40))
        addView(label(
            if (serving) "Braid on your computer can find \u201c${phone.name}\u201d on this network. Only computers you pair can use it."
            else "Turn sharing on to lend this phone\u2019s connection to Braid on your computer.",
            16, muted = true,
        ).apply { setLineSpacing(0f, 1.2f) }, spaced(10))
        addView(bigButton(serving), spaced(24))
    }

    private fun bigButton(serving: Boolean) = label(if (serving) "Stop sharing" else "Start sharing", 17, weight = Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        minHeight = dp(56)
        setTextColor(if (serving) color(R.color.text) else color(android.R.color.white))
        val shape = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            if (serving) {
                setColor(color(R.color.surface))
                setStroke(dp(1), color(R.color.track))
            } else {
                setColor(color(R.color.copper))
            }
        }
        background = RippleDrawable(ColorStateList.valueOf(color(R.color.copper_soft)), shape, null)
        isClickable = true
        setOnClickListener { if (serving) RelayService.stop(context) else RelayService.start(context) }
    }

    private fun laneRow(lane: String, serving: Boolean): View {
        val lanes = phone.lanes
        val wanted = lanes.isWanted(lane)
        val used = RelayHost.used(lane)
        val limit = lanes.limitMb(lane) * RelayHost.MB
        val offered = lanes.isOffered(lane)
        val status = when {
            !wanted -> "Not shared"
            !serving -> "Shared when sharing is on"
            lanes.isExhausted(lane) -> "Limit reached, so it’s paused. Raise the limit or restart sharing."
            offered -> "Shared, ${RelayHost.size(used)} used this session"
            else -> "Waiting for ${lanes.label(lane).lowercase()}"
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(if (lane == NetworkLanes.CELL) "Mobile data" else "Wi-Fi", 17, weight = Typeface.BOLD))
            addView(label(status, 14, muted = !offered, tint = if (offered && serving) color(R.color.live) else null).apply { tabular() }, spaced(2))
            if (lane == NetworkLanes.CELL) {
                if (limit > 0) addView(usageBar(used, limit), spaced(10))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(if (limit > 0) "${RelayHost.size(used)} of ${RelayHost.size(limit)}" else "No data limit", 13, muted = true).apply { tabular() },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(textButton(if (limit > 0) "Change limit" else "Set a limit", color(R.color.copper_strong)) { askLimit(lane) })
                }, spaced(4))
            } else {
                addView(label("If your computer uses this same Wi-Fi, Braid will spot the duplicate and leave it off.", 13, muted = true)
                    .apply { setLineSpacing(0f, 1.15f) }, spaced(6))
            }
        }
        return row(
            glyph(if (lane == NetworkLanes.CELL) R.drawable.ic_cellular else R.drawable.ic_wifi, active = offered && serving),
            body,
            Switch(this).apply {
                isChecked = wanted
                setOnCheckedChangeListener { _, on -> RelayHost.setWanted(context, lane, on) }
            },
            top = true,
        )
    }

    private fun usageBar(used: Long, limit: Long) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progress = ((used.toDouble() / limit) * 1000).toInt().coerceIn(0, 1000)
        val radius = dp(3).toFloat()
        progressDrawable = LayerDrawable(arrayOf(
            GradientDrawable().apply { cornerRadius = radius; setColor(color(R.color.track)) },
            ClipDrawable(GradientDrawable().apply { cornerRadius = radius; setColor(color(R.color.copper)) }, Gravity.START, ClipDrawable.HORIZONTAL),
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
        minimumHeight = dp(6)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
    }

    private fun emptyComputers() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(label("No computers yet", 17, weight = Typeface.BOLD))
        addView(label(PAIRING_STEPS, 14, muted = true).apply { setLineSpacing(0f, 1.2f) }, spaced(4))
    }

    private fun askLimit(lane: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Megabytes"
            phone.lanes.limitMb(lane).takeIf { it > 0 }?.let { setText(it.toString()); setSelection(text.length) }
        }
        AlertDialog.Builder(this)
            .setTitle("Mobile data limit")
            .setMessage("Sharing over mobile data pauses once this much has been used since sharing was turned on. Leave it empty for no limit.")
            .setView(FrameLayout(this).apply {
                setPadding(dp(20), dp(4), dp(20), 0)
                addView(input)
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

    private fun sectionTitle(value: String) = label(value, 15, weight = Typeface.BOLD, muted = true).apply {
        setPadding(dp(4), dp(32), 0, dp(10))
    }

    private fun footnote(value: String) = label(value, 13, muted = true).apply {
        setPadding(dp(4), dp(8), dp(4), 0)
        setLineSpacing(0f, 1.15f)
    }

    /** Rows share one panel with hairlines between them, as the desktop's settings do. */
    private fun group(rows: List<View>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(color(R.color.surface))
        }
        clipToOutline = true
        rows.forEachIndexed { i, row ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(color(R.color.divider)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(68) })
            addView(row)
        }
    }

    private fun row(start: View, middle: View, end: View, top: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = if (top) Gravity.TOP else Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(12), dp(14))
        addView(start, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        addView(middle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(end, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
    }

    private fun lines(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(title, 17, weight = Typeface.BOLD))
        addView(label(subtitle, 14, muted = true), spaced(2))
    }

    /** Copper while that network or computer is in use, neutral otherwise, so the page shows what is live at a glance. */
    private fun glyph(icon: Int, active: Boolean) = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(color(if (active) R.color.copper_soft else R.color.neutral_soft))
        }
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(if (active) R.color.copper_strong else R.color.text_muted))
        }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
    }

    private fun textButton(value: String, tint: Int, action: () -> Unit) = label(value, 15, weight = Typeface.BOLD, tint = tint).apply {
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(color(R.color.copper_soft)), null,
            GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(color(R.color.surface)) })
        isClickable = true
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Int, weight: Int = Typeface.NORMAL, muted: Boolean = false, tint: Int? = null) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        typeface = Typeface.create(if (weight == Typeface.BOLD) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setTextColor(tint ?: color(if (muted) R.color.text_muted else R.color.text))
    }

    /** Figures that change every second keep their width, so the line does not jitter. */
    private fun TextView.tabular() {
        fontFeatureSettings = "tnum"
    }

    private fun spaced(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(top) }

    private companion object {
        const val PAIRING_STEPS = "To pair a computer, open Braid on it, click Add phone at the bottom of the interfaces list, " +
            "then Scan and Pair. This phone will ask you to allow it."
    }
}
