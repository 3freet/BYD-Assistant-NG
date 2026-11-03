package com.bydassistantng.util

/**
 * Hides what is private in a copy of a log, so the copy can be shared or saved: what the user said and asked for,
 * the places and song names in it, keys and tokens, e-mail addresses, phone numbers, network addresses and the like.
 * What stays is what a bug report needs: the times, the steps the app took, the car's answers and the errors.
 *
 * The log on the device is never changed; this only makes a masked copy. It works on whole entries (an entry is a
 * timestamped line with any lines that follow it, such as a stack trace or a transcript that ran over several lines),
 * and what it hides is replaced by a marker that says what kind of thing was there (`<hidden:email>`) or how long
 * the text was (`<hidden: 23 chars>`), so that "the assistant heard nothing" can still be told from "it heard a sentence".
 *
 * Three kinds of rule, in this order:
 *  - secrets (keys, tokens) wherever they appear;
 *  - content: the quoted text of an entry that is not a data dump, the text fields of one that is, and the
 *    arguments of every tool call except the car's own controls (a window, a seat, the A/C), whose arguments
 *    are the point of the log;
 *  - identifiers (addresses, numbers, ids) wherever they appear.
 */
object LogMasker {
    /** Masks every entry of [log]; a log with nothing private in it comes back unchanged. */
    fun mask(log: String): String {
        val out = StringBuilder(log.length)
        val entry = StringBuilder()
        var first = true

        fun flush() {
            if (entry.isEmpty()) return
            if (!first) out.append('\n')
            out.append(maskEntry(entry.toString()))
            entry.clear()
            first = false
        }

        for (line in log.split('\n')) {
            if (ENTRY_START.containsMatchIn(line)) flush()
            if (entry.isNotEmpty()) entry.append('\n')
            entry.append(line)
        }
        flush()
        return out.toString()
    }

    /** Masks one entry: its first line and any continuation lines. */
    internal fun maskEntry(entry: String): String {
        var text = hideSecrets(entry)
        text = hideContent(text)
        return hideIdentifiers(text)
    }

    // ── Entries ──────────────────────────────────────────────────────────────────────────────────

    /** `2026-10-31 14:03:22.123 [Tag] …` in the app log, `=== 2026-10-31 14:03:22 — thread … ===` in the crash log. */
    private val ENTRY_START = Regex("""^(?:=== )?\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")

    /** What comes before the message of an entry, which is never touched. */
    private val ENTRY_PREFIX = Regex("""^(?:=== )?\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d{3})? (?:\[[^\]\n]*\] )?""")

    // ── Secrets ─────────────────────────────────────────────────────────────────────────────────

    private val SECRET_RULES: List<Pair<Regex, String>> = listOf(
        // A Google API key, wherever it turns up (the Live API takes it in the address of the connection).
        Regex("""AIza[0-9A-Za-z_\-]{20,}""") to "<hidden:key>",
        // A key or token in an address or a header.
        Regex("""(?i)([?&](?:key|api_?key|access_token|id_token|token|auth|sig|signature)=)[^&\s"']+""") to "$1<hidden:key>",
        Regex("""(?i)\b(bearer\s+|authorization\s*[:=]\s*(?:bearer\s+)?)[A-Za-z0-9._~+/=\-]{8,}""") to "$1<hidden:token>",
        Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]*""") to "<hidden:token>",
        // `"apiKey": "…"`, `password=…` and the like.
        Regex("""(?i)("?(?:api[_-]?key|x-goog-api-key|token|secret|password|passwd)"?\s*[:=]\s*"?)[^\s",}&]{6,}""") to "$1<hidden:key>",
    )

    private fun hideSecrets(text: String): String = SECRET_RULES.fold(text) { acc, (rule, replacement) -> rule.replace(acc, replacement) }

    // ── Content ─────────────────────────────────────────────────────────────────────────────────

    /** An entry that carries data (a message from the server, an error body) rather than a sentence of the app's own. */
    private val JSON_LIKE = Regex("""[{\[]\s*"[^"\n]+"\s*:""")

    /** The tool call lines: `Tool call: name({…})` (a live conversation), `[turn 3] function call: name({…})` (the classic one). */
    private val TOOL_CALL = Regex("""(?s)((?:Tool call|function call): )([A-Za-z0-9_.]+)\((.*)\)\s*$""")

    /**
     * The tools whose arguments say nothing about the user: the car's own controls, and the few others that take only a
     * fixed word or a number. Every other tool takes something the user said (a place, a song, an app, a fact to remember).
     */
    private val OPEN_TOOLS = setOf(
        "ac", "window", "sunroof", "sunshade", "audio", "trunk", "screen", "mirrors", "ambient", "fridge", "seat",
        "steeringWheel", "dashcam", "cabin", "read_car", "set_drive_mode", "media_control", "end_conversation",
    )

    /** A `"key": "value"` pair of a data dump, for the keys whose value is what someone said or asked for. */
    private val PRIVATE_TEXT_FIELD = Regex(
        """("(?:text|transcript|transcription|query|destination|fact|facts|utterance|prompt)"\s*:\s*)"((?:[^"\\]|\\.)*)"""",
    )

    /** One string of a data dump, key or value. Read left to right, so that each quote is taken for what it is. */
    private val STRING_TOKEN = Regex(""""((?:[^"\\]|\\.)*)"""")

    /** Entries that are always a sentence someone said, whatever else is in them. */
    private val SPOKEN = Regex("""^(?:heard|said|Speaking outside the car)\b""")

    /** Lines of the app that name something on the screen without quoting it. */
    private val UNQUOTED_LABELS: List<Regex> = listOf(
        Regex("""^(Pressing the first result of \S+: )(.+)$"""),
        Regex("""^(Pressing play on the opened page of \S+ \()(.+)(\))$"""),
        Regex("""^(Quick press \d+: \S+ )(.+)$"""),
    )

    private val HOSTNAME = Regex("""^[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$""")

    /** Fixed words the app or the car's screen uses, which a log quotes and which say nothing about anyone. */
    private val FIXED_PHRASES = setOf("Allow USB debugging?")

    private fun hidden(text: String) = "<hidden: ${text.length} chars>"

    private fun hideContent(entry: String): String {
        val prefix = ENTRY_PREFIX.find(entry)?.value.orEmpty()
        var message = entry.removePrefix(prefix)

        // The first line of a crash entry names the thread, which is worth keeping: only what follows is masked.
        var kept = ""
        if (prefix.startsWith("=== ")) {
            kept = message.substringBefore('\n')
            message = message.removePrefix(kept)
        }

        message = hideToolCallArguments(message)
        message = hideLabels(message)
        message = if (JSON_LIKE.containsMatchIn(message) && !SPOKEN.containsMatchIn(message)) hideTextFields(message) else hideQuoted(message)
        return prefix + kept + message
    }

    private fun hideToolCallArguments(message: String): String {
        val call = TOOL_CALL.find(message) ?: return message
        val (lead, name, arguments) = call.destructured
        if (name in OPEN_TOOLS) return message
        // A string followed by a colon is a key and stays; any other (a value, or an item of a list) is hidden.
        val masked = STRING_TOKEN.replace(arguments) { token ->
            val isKey = arguments.substring(token.range.last + 1).trimStart().startsWith(":")
            if (isKey) token.value else "\"" + hidden(token.groupValues[1]) + "\""
        }
        return message.substring(0, call.range.first) + lead + name + "(" + masked + ")"
    }

    private fun hideLabels(message: String): String {
        for (rule in UNQUOTED_LABELS) {
            val match = rule.find(message) ?: continue
            val groups = match.groupValues
            return groups[1] + hidden(groups[2]) + groups.getOrElse(3) { "" }
        }
        return message
    }

    private fun hideTextFields(message: String): String = PRIVATE_TEXT_FIELD.replace(message) {
        // A tool call's arguments were hidden a step ago; do not hide the marker.
        if (it.groupValues[2].startsWith("<hidden")) it.value else it.groupValues[1] + "\"" + hidden(it.groupValues[2]) + "\""
    }

    /**
     * Everything between the first double quote and the last one: what was said, asked for or searched, which may itself
     * hold quotes, so a quote is not taken to end it before the last one. What is left alone is a quoted host name (an
     * error naming the server it could not reach) and the few fixed phrases of the app and the car in [FIXED_PHRASES].
     */
    private fun hideQuoted(message: String): String {
        val open = message.indexOf('"')
        val close = message.lastIndexOf('"')
        if (open < 0 || close <= open) return message
        val inside = message.substring(open + 1, close)
        if (HOSTNAME.matches(inside) || inside in FIXED_PHRASES) return message
        return message.substring(0, open + 1) + hidden(inside) + message.substring(close)
    }

    // ── Identifiers ─────────────────────────────────────────────────────────────────────────────

    private val IDENTIFIER_RULES: List<Pair<Regex, String>> = listOf(
        // Long runs of base64 or hex: audio, ids, keys.
        Regex("""[A-Za-z0-9+/_\-]{100,}={0,2}""") to "<hidden:data>",
        Regex("""(?i)\bprojects/\d+""") to "projects/<hidden>",
        // The part after `?` in an address: what was searched or opened.
        Regex("""(https?://[^\s"'?<>]+)\?[^\s"'<>]+""") to "$1?<hidden>",
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}""") to "<hidden:email>",
        Regex("""\b(?:[0-9A-Fa-f]{2}[:\-]){5}[0-9A-Fa-f]{2}\b""") to "<hidden:mac>",
        // Not a version number: "version:5.1.0.8" is the head unit's software, not an address.
        Regex("""(?<!(?i:version[:=]))\b(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\b""") to "<hidden:ip>",
        Regex("""\b(?:[0-9A-Fa-f]{1,4}:){5,7}[0-9A-Fa-f]{1,4}\b""") to "<hidden:ip>",
        // A pair of coordinates, or one named as a latitude or longitude.
        Regex("""-?\d{1,3}\.\d{4,}\s*,\s*-?\d{1,3}\.\d{4,}""") to "<hidden:location>",
        Regex("""(?i)\b(lat(?:itude)?|lon(?:gitude)?|lng)(\s*[:=]\s*)-?\d{1,3}(?:\.\d+)?""") to "$1$2<hidden:location>",
        // A phone number: international, or the common three-three-four.
        Regex("""\+\d[\d\s().\-]{6,}\d""") to "<hidden:phone>",
        Regex("""\b\d{3}[\s.\-]\d{3}[\s.\-]\d{4}\b""") to "<hidden:phone>",
        // A vehicle identification number: 17 capitals and digits (never I, O or Q), with both.
        Regex("""\b(?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-HJ-NPR-Z0-9]{17}\b""") to "<hidden:vin>",
    )

    private fun hideIdentifiers(text: String): String = IDENTIFIER_RULES.fold(text) { acc, (rule, replacement) -> rule.replace(acc, replacement) }
}
