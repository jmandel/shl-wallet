package me.fhir.shcwallet.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

/**
 * Minimal parser for SMART Health Cards that ignores trust & signatures.
 *
 * Usage examples
 * --------------
 *   val fhir = SmartHealthCardParser.fromQr("shc:/5676290952…")
 *   val fhir = SmartHealthCardParser.fromJws(jwsString)
 *   val bundles = SmartHealthCardParser.fromSmartHealthCardFile(jsonText)
 */
object SmartHealthCardParser {

    /** Entry‑point for a `shc:/…` QR payload (single‑chunk). */
    fun fromQr(shc: String): JSONObject? {
        val jws = numericShcToJws(shc)
        return fromJws(jws)
    }

    /** Entry‑point for a compact JWS string. */
    fun fromJws(jws: String): JSONObject? = runCatching {
        // 1) Split header.payload.signature
        val parts = jws.split('.')
        require(parts.size == 3) { "Invalid JWS format" }

        // 2) Base64‑URL decode **payload** only
        val payloadDeflated = Base64.getUrlDecoder().decode(parts[1])

        // 3) Raw DEFLATE inflate (nowrap = true)
        val payloadJson = String(inflate(payloadDeflated))

        // 4) Parse JSON & pluck the FHIR bundle
        val vc = JSONObject(payloadJson)
            .getJSONObject("vc")
            .getJSONObject("credentialSubject")

        vc.getJSONObject("fhirBundle")
    }.getOrNull()

    /** Entry‑point for the text of a `.smart‑health‑card` file. */
    fun fromSmartHealthCardFile(fileText: String): List<JSONObject> {
        val root = JSONObject(fileText)
        val vcs = root.optJSONArray("verifiableCredential") ?: return emptyList()
        return (0 until vcs.length())
            .mapNotNull { fromJws(vcs.getString(it)) }
    }

    // ---------- helpers ----------

    /** Converts a numeric‑mode `shc:/` string to its underlying JWS. */
    private fun numericShcToJws(raw: String): String {
        // Strip scheme
        var digits = raw.removePrefix("shc:/")

        // Drop deprecated chunk metadata (e.g., "2/3/")
        if (digits.contains('/')) {
            digits = digits.substringAfterLast('/')
        }

        // Pair each two digits, add 45, convert to char
        return digits.chunked(2)
            .joinToString("") { ((it.toInt()) + 45).toChar().toString() }
    }

    /** Raw‑inflate helper. */
    private fun inflate(deflated: ByteArray): ByteArray {
        val inflater = Inflater(true)          // "true" == nowrap (raw DEFLATE)
        inflater.setInput(deflated)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            out.write(buffer, 0, count)
        }
        inflater.end()
        return out.toByteArray()
    }
} 