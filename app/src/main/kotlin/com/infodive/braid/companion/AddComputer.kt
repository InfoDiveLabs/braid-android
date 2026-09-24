package com.infodive.braid.companion

import android.app.Activity
import android.app.AlertDialog
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.system.OsConstants
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.infodive.braid.relay.LocalScope
import com.infodive.braid.relay.PairingCode
import com.infodive.braid.relay.RelayServer
import com.infodive.braid.relay.Registrar
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Pairs by the desktop's QR code: scan or open the braid://pair link, check
 * the code points somewhere local, ask once, then announce this phone to it.
 */
class AddComputer(private val activity: Activity, private val onChanged: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val phone get() = RelayHost.phone(activity)

    fun scan() {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(activity, options).startScan()
            .addOnSuccessListener { handle(it.rawValue.orEmpty()) }
            .addOnFailureListener {
                explain(
                    "Couldn’t open the scanner",
                    "Point your phone’s camera app at the code on your computer instead. Braid opens from there.",
                )
            }
    }

    fun handle(text: String) {
        val code = when (val parsed = PairingCode.parse(text)) {
            is PairingCode.Parsed.Invalid -> return explain("Can’t use this code", parsed.reason)
            is PairingCode.Parsed.Ok -> parsed.code
        }
        if (!LocalScope.contains(localLinks(), InetAddress.getByName(code.host))) {
            return explain(
                "That computer isn’t on this network",
                "The code points to ${code.host}, which isn’t on any network this phone is connected to. " +
                    "Connect this phone to the same Wi-Fi as your computer, or plug it in with USB tethering, then show a new code.",
            )
        }
        AlertDialog.Builder(activity)
            .setTitle("Add this phone to “${code.desktop}”?")
            .setMessage(
                "The computer at ${code.host} will be able to download through the networks you share here, including mobile data." +
                    if (RelayHost.isRunning) "" else "\n\nSharing will turn on.",
            )
            .setPositiveButton("Add") { _, _ -> register(code) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun register(code: PairingCode) {
        val progress = AlertDialog.Builder(activity).setMessage("Adding to “${code.desktop}”…").setCancelable(false).show()
        thread(name = "register") {
            if (!RelayHost.isRunning) {
                RelayService.start(activity)
                repeat(30) { if (!RelayHost.isRunning) Thread.sleep(100) }
            }
            val key = phone.pairings.add(code.desktop)
            val outcome = Registrar.register(code, phone.deviceId, phone.name, RelayServer.DEFAULT_PORT, key, ::stableAddress)
            if (outcome != Registrar.Outcome.Paired) phone.pairings.forgetKey(key)
            main.post {
                progress.dismiss()
                onChanged()
                when (outcome) {
                    Registrar.Outcome.Paired -> explain("Added to “${code.desktop}”", "Switch on the networks you want it to use.")
                    Registrar.Outcome.Expired -> explain("That code has expired", "Ask the computer for a new one and scan again.")
                    Registrar.Outcome.Unreachable -> explain(
                        "Couldn’t reach “${code.desktop}”",
                        "Make sure this phone is on the same network as the computer, then show a new code.",
                    )
                    is Registrar.Outcome.Failed -> explain("Couldn’t add this phone", "The computer answered with error ${outcome.status}. Show a new code and try again.")
                }
            }
        }
    }

    /** Every address on this phone's non-cellular interfaces, with its prefix, for the local-network check. */
    private fun localLinks(): List<LocalScope.Link> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { nif -> nif.interfaceAddresses.map { LocalScope.Link(nif.name, it.address, it.networkPrefixLength.toInt()) } }

    /**
     * The socket's IPv6 source is normally a temporary privacy address that
     * rotates within a day, which would leave the desktop holding a dead
     * address. Prefer the stable one on the same /64.
     */
    private fun stableAddress(local: InetAddress): InetAddress {
        if (local !is Inet6Address) return local
        val cm = activity.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks.asSequence()
            .mapNotNull { cm.getLinkProperties(it) }
            .flatMap { it.linkAddresses.asSequence() }
            .firstOrNull { la ->
                la.address is Inet6Address && !la.address.isLinkLocalAddress &&
                    (la.flags and OsConstants.IFA_F_TEMPORARY) == 0 &&
                    LocalScope.sameSubnet(la.address, local, 64)
            }?.address ?: local
    }

    private fun explain(title: String, message: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }
}
