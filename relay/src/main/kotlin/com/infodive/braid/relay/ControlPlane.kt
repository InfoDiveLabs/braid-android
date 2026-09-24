package com.infodive.braid.relay

/** One network the phone is willing to serve. Field meanings are fixed by the desktop's `OfferedLane`. */
data class Lane(
    val id: String,
    val kind: String?,
    val label: String,
    val egress: String? = null,
    val egress6: String? = null,
    val note: String? = null,
)

/** What the control plane needs from the device it runs on. */
interface Phone {
    val name: String
    val deviceId: String

    fun lanes(): List<Lane>

    fun isPaired(key: String): Boolean

    /**
     * Asks a person whether [desktop], connecting from [from], may use this
     * phone, blocking until they answer or give up. Returns the new key, or
     * null for refusal.
     */
    fun pair(desktop: String, from: String): String?
}

class Reply(val code: Int, val reason: String, val json: String? = null)

/** `/braid/hello`, `/braid/status` and `/braid/pair`, as the desktop's `dl-net/src/control.rs` expects them. */
class ControlPlane(private val phone: Phone) {
    fun respond(head: RequestHead, path: String, body: ByteArray, from: String): Reply {
        val route = path.substringBefore('?')
        val method = when (route) {
            "/braid/hello", "/braid/status" -> "GET"
            "/braid/pair" -> "POST"
            else -> return Reply(404, "Not Found")
        }
        if (head.method != method) return Reply(405, "Method Not Allowed")
        return when (route) {
            "/braid/hello" -> ok(Json.obj("name" to phone.name, "device_id" to phone.deviceId, "version" to PROTOCOL_VERSION))
            "/braid/status" -> status(head)
            else -> pair(body, from)
        }
    }

    /** Egress addresses and allowance notes are not for whoever else is on the café network. */
    private fun status(head: RequestHead): Reply {
        val key = head.header("x-braid-key")
        if (key.isNullOrEmpty() || !phone.isPaired(key)) return Reply(401, "Unauthorized")
        return ok(Json.obj("lanes" to Json.array(phone.lanes().map(::encode))))
    }

    private fun pair(body: ByteArray, from: String): Reply {
        val desktop = Json.parseObject(body.toString(Charsets.UTF_8))?.get("desktop") as? String
            ?: return Reply(400, "Bad Request")
        val key = phone.pair(desktop, from) ?: return Reply(403, "Forbidden")
        return ok(Json.obj("key" to key))
    }

    private fun encode(lane: Lane) = Json.obj(
        "id" to lane.id,
        "kind" to lane.kind,
        "label" to lane.label,
        "egress" to lane.egress,
        "egress6" to lane.egress6,
        "note" to lane.note,
    )

    private fun ok(json: String) = Reply(200, "OK", json)

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}
