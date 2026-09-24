package com.infodive.braid.companion

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.OsConstants
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.transition.Fade
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.infodive.braid.relay.LocalScope
import com.infodive.braid.relay.RelayServer
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var column: LinearLayout
    private val addComputer by lazy { AddComputer(this) { shown = null; render() } }
    private var shown: String? = null
    private var shape: String? = null
    private var pulse: ObjectAnimator? = null
    private var barProgress = 0

    /** Figures that change every second are updated in place, so the page is rebuilt only when its structure changes. */
    private val live = mutableListOf<() -> Unit>()
    private var dialog: AlertDialog? = null
    private val main = Handler(Looper.getMainLooper())
    private val redraw: () -> Unit = { main.post(::render) }
    private val tick = object : Runnable {
        override fun run() {
            render()
            live.forEach { it() }
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
        openLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    /** A braid://pair link, from the camera app or anywhere else, goes through the same checks as a scan. */
    private fun openLink(intent: Intent?) {
        val link = intent?.data?.takeIf { it.scheme == "braid" } ?: return
        setIntent(Intent(this, MainActivity::class.java))
        addComputer.handle(link.toString())
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
        pulse?.cancel()
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
            lanes.ids.map { listOf(it, lanes.isWanted(it), lanes.isExhausted(it), lanes.isOffered(it), lanes.limitMb(it)) },
            phone.pairings.list().map { it.id },
            if (RelayHost.isRunning) addresses() else emptyList(),
        ).toString()
    }

    /** The page's structure without the usage figures, so a transfer ticking along does not trigger a transition. */
    private fun shape(): String {
        val lanes = phone.lanes
        return listOf(RelayHost.isRunning, lanes.ids.map { listOf(lanes.isWanted(it), lanes.isExhausted(it), lanes.isOffered(it), lanes.limitMb(it)) },
            phone.pairings.list().map { it.id }).toString()
    }

    private fun render() {
        val now = state()
        if (now == shown) return
        val structure = shape()
        if (shown != null && structure != shape) {
            TransitionManager.beginDelayedTransition(column, Fade().setDuration(180))
        }
        shown = now
        shape = structure
        pulse?.cancel()
        live.clear()
        column.removeAllViews()
        val serving = RelayHost.isRunning

        column.addView(hero(serving))

        if (serving) {
            column.addView(sectionTitle("Activity"))
            column.addView(activity())
        }

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
        column.addView(scanButton(), spaced(12))
        column.addView(footnote(PAIRING_STEPS))

        if (serving) {
            val found = addresses()
            if (found.isNotEmpty()) {
                column.addView(sectionTitle("If Scan can’t find this phone"))
                column.addView(group(listOf(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    addView(label("On your computer, choose Add phone, then add it by address.", 14, muted = true))
                    found.forEach { address -> addView(addressField(address), spaced(10)) }
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
                if (serving) pulse = breathe(this)
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

    /** A slow pulse on the live dot, the one thing on the page that moves by itself, and only while the phone is shared. */
    private fun breathe(dot: View) = ObjectAnimator.ofPropertyValuesHolder(
        dot,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f),
        PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.55f),
    ).apply {
        duration = 1_100
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        start()
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
            addView(label(status, 14, muted = !offered, tint = if (offered && serving) color(R.color.live) else null).apply {
                tabular()
                if (offered && serving && !lanes.isExhausted(lane)) {
                    live += { text = "Shared, ${RelayHost.size(RelayHost.used(lane))} used this session" }
                }
            }, spaced(2))
            if (lane == NetworkLanes.CELL) {
                if (limit > 0) addView(usageBar(lane, limit), spaced(10))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(if (limit > 0) "${RelayHost.size(used)} of ${RelayHost.size(limit)}" else "No data limit", 13, muted = true).apply {
                        tabular()
                        if (limit > 0) live += { text = "${RelayHost.size(RelayHost.used(lane))} of ${RelayHost.size(limit)}" }
                    },
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

    private fun usageBar(lane: String, limit: Long) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progress = barProgress
        fun slide() {
            val target = ((RelayHost.used(lane).toDouble() / limit) * 1000).toInt().coerceIn(0, 1000)
            if (target != barProgress) ObjectAnimator.ofInt(this, "progress", barProgress, target).setDuration(450).start()
            barProgress = target
        }
        slide()
        live += ::slide
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
        addView(label("Add one below to let it download through this phone.", 14, muted = true), spaced(4))
    }

    private fun scanButton() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(52)
        val shape = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(color(R.color.copper_soft))
        }
        background = RippleDrawable(ColorStateList.valueOf(color(R.color.copper_soft)), shape, null)
        isClickable = true
        setOnClickListener { addComputer.scan() }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_qr)
            imageTintList = ColorStateList.valueOf(color(R.color.copper_strong))
        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        addView(label("Add to a computer by code", 16, weight = Typeface.BOLD, tint = color(R.color.copper_strong)))
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

    /** One line per address, cut in the middle if it must be, since the copy button always carries all of it. */
    private fun addressField(address: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(4), dp(4), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(color(R.color.neutral_soft))
        }
        addView(label(address, 14).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MIDDLE
            tabular()
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_copy)
            imageTintList = ColorStateList.valueOf(color(R.color.copper_strong))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Copy address"
            background = RippleDrawable(ColorStateList.valueOf(color(R.color.copper_soft)), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(R.color.surface)) })
        }
        icon.setOnClickListener {
            copy(address)
            icon.setImageResource(R.drawable.ic_check)
            icon.postDelayed({ icon.setImageResource(R.drawable.ic_copy) }, 1_500)
        }
        addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
    }

    private fun copy(address: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Braid phone address", address))
        // Android 13 and later confirm a copy themselves; a second message would be noise.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Address copied", Toast.LENGTH_SHORT).show()
    }

    /** Global addresses only: a link-local one needs a scope id that means nothing on the computer. */
    private fun addresses(): List<String> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !LocalScope.isCellular(it.name) && !it.name.startsWith("dummy") }
        .flatMap { it.inetAddresses.toList() }
        .filter { !it.isLinkLocalAddress && !it.isLoopbackAddress && it !in temporaryAddresses() }
        .map { if (it is Inet6Address) "[${it.hostAddress!!.substringBefore('%')}]:${RelayServer.DEFAULT_PORT}" else "${it.hostAddress}:${RelayServer.DEFAULT_PORT}" }

    /** IPv6 privacy addresses rotate within a day; an address typed into the desktop should outlast that. */
    private fun temporaryAddresses(): Set<InetAddress> {
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.mapNotNull { cm.getLinkProperties(it) }
            .flatMap { it.linkAddresses }
            .filter { (it.flags and OsConstants.IFA_F_TEMPORARY) != 0 }
            .map { it.address }
            .toSet()
    }

    /**
     * The last minute of traffic per network, labelled directly, then the
     * session's headline figures. Touching the chart reads out that second.
     */
    private fun activity(): View {
        val lanes = phone.lanes.ids
        val seriesColor = mapOf(NetworkLanes.CELL to color(R.color.series_cell), NetworkLanes.WIFI to color(R.color.series_wifi))
        val caption = label("Last minute", 13, muted = true)
        val rates = lanes.associateWith { label("", 15, weight = Typeface.BOLD).apply { tabular() } }
        val chart = ThroughputChart(this)
        var scrubbed: Int? = null

        fun refresh() {
            val history = lanes.associateWith { RelayHost.history(it) }
            chart.series = lanes.map { ThroughputChart.Series(seriesColor.getValue(it), history.getValue(it)) }
            val at = scrubbed
            caption.text = if (at == null) "Last minute" else if (at == 0) "Now" else "$at s ago"
            for (lane in lanes) {
                val values = history.getValue(lane)
                rates.getValue(lane).text = ThroughputChart.rate(values[values.size - 1 - (at ?: 0)])
            }
        }
        chart.onScrub = { scrubbed = it; refresh() }

        val legend = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (lane in lanes) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(View(context).apply {
                            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(seriesColor.getValue(lane)) }
                        }, LinearLayout.LayoutParams(dp(12), dp(4)).apply { marginEnd = dp(8) })
                        addView(label(phone.lanes.label(lane), 13, muted = true))
                    })
                    addView(rates.getValue(lane), spaced(2))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        val used = stat("Used this session")
        val peak = stat("Fastest")
        val time = stat("Sharing for")
        val open = stat("Connections now")
        live += {
            if (scrubbed == null) refresh()
            used.second.text = RelayHost.size(lanes.sumOf { RelayHost.used(it) })
            peak.second.text = ThroughputChart.rate(RelayHost.peak)
            time.second.text = DateUtils.formatElapsedTime((SystemClock.elapsedRealtime() - RelayHost.sessionStart) / 1000)
            open.second.text = RelayHost.connections.toString()
        }
        live.last()()

        return group(listOf(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(16))
                addView(caption)
                addView(legend, spaced(8))
                addView(chart, spaced(12))
            },
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(tiles(used.first, peak.first), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(tiles(time.first, open.first), spaced(14))
            },
        ), inset = 0)
    }

    private fun stat(name: String): Pair<View, TextView> {
        val value = label("", 20, weight = Typeface.BOLD).apply { tabular() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(name, 13, muted = true))
            addView(value, spaced(2))
        } to value
    }

    private fun tiles(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun sectionTitle(value: String) = label(value, 15, weight = Typeface.BOLD, muted = true).apply {
        setPadding(dp(4), dp(32), 0, dp(10))
    }

    private fun footnote(value: String) = label(value, 13, muted = true).apply {
        setPadding(dp(4), dp(8), dp(4), 0)
        setLineSpacing(0f, 1.15f)
    }

    /** Rows share one panel with hairlines between them, as the desktop's settings do. */
    private fun group(rows: List<View>, inset: Int = 68) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(color(R.color.surface))
        }
        clipToOutline = true
        rows.forEachIndexed { i, row ->
            if (i > 0) addView(View(context).apply { setBackgroundColor(color(R.color.divider)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(inset) })
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
        const val PAIRING_STEPS = "On your computer, click Add phone at the bottom of Braid\u2019s interfaces list. " +
            "Scan the code it shows, or press Scan there and allow the request on this phone."
    }
}
