package com.bydassistantng.vehicle

/**
 * The one line the shell helper ([VehicleShellHelper]) prints so the app can read the outcome back
 * from `adb shell` output, which may also contain unrelated runtime chatter. Newlines in a detail
 * are flattened so a result is always exactly one line.
 */
object HelperProtocol {
    const val RESULT_PREFIX = "BYDRESULT "

    /** Printed once by a helper in `--serve` mode when it is initialised and waiting for commands. */
    const val READY_LINE = "BYDREADY"

    /** Prefix of the ready line's word that lists the commands this car cannot do (see [ready]). */
    private const val UNAVAILABLE_PREFIX = "unavailable="

    /**
     * What the vehicle helper says when it is up: the platform it found the car to run, the commands this car cannot do (the
     * helper reads the car's table of features, which the app cannot), and what it saw, for the log.
     */
    data class Ready(val platform: DiLinkPlatform, val unavailable: Set<String>, val details: String)

    /**
     * The ready line of a vehicle helper: [READY_LINE], the platform's key, `unavailable=` with the ids of the commands the
     * car cannot do (`-` for none), then free text for the log. A command id is only letters, digits, dots and underscores.
     */
    fun ready(platform: DiLinkPlatform, unavailable: Set<String>, details: String): String =
        listOf(READY_LINE, platform.key, UNAVAILABLE_PREFIX + unavailable.sorted().joinToString(",").ifEmpty { "-" }, flatten(details))
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** @return null if [line] is not a ready line. A bare one (a helper that does not look at the platform) is [DiLinkPlatform.UNKNOWN]. */
    fun parseReady(line: String): Ready? {
        val text = line.trim()
        if (!text.startsWith(READY_LINE)) return null
        val rest = text.removePrefix(READY_LINE).trim()
        val key = rest.substringBefore(' ')
        val platform = DiLinkPlatform.byKey(key) ?: return Ready(DiLinkPlatform.UNKNOWN, emptySet(), rest)
        val afterKey = rest.substringAfter(' ', "").trim()
        val word = afterKey.substringBefore(' ')
        if (!word.startsWith(UNAVAILABLE_PREFIX)) return Ready(platform, emptySet(), afterKey)
        val ids = word.removePrefix(UNAVAILABLE_PREFIX).split(',').filter { it.isNotEmpty() && it != "-" }.toSet()
        return Ready(platform, ids, afterKey.substringAfter(' ', "").trim())
    }

    fun encode(result: VehicleDispatchResult): String = RESULT_PREFIX + when (result) {
        is VehicleDispatchResult.Success -> "OK" + (result.note?.let { " ${flatten(it)}" } ?: "")
        is VehicleDispatchResult.Blocked -> "BLOCKED ${flatten(result.reason)}"
        is VehicleDispatchResult.Failure -> "FAIL ${result.error.name}" + (result.detail?.let { " ${flatten(it)}" } ?: "")
    }

    /** @return null if [output] contains no result line at all. If it somehow has several, the last wins. */
    fun parse(output: String): VehicleDispatchResult? {
        val line = output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(RESULT_PREFIX) } ?: return null
        val body = line.removePrefix(RESULT_PREFIX)
        val kind = body.substringBefore(' ')
        val rest = body.substringAfter(' ', "").ifEmpty { null }
        return when (kind) {
            "OK" -> VehicleDispatchResult.Success(rest)
            "BLOCKED" -> VehicleDispatchResult.Blocked(rest ?: "blocked")
            "FAIL" -> {
                val errorName = rest?.substringBefore(' ')
                val error = VehicleDispatchError.entries.find { it.name == errorName } ?: VehicleDispatchError.UNKNOWN
                VehicleDispatchResult.Failure(error, rest?.substringAfter(' ', "")?.ifEmpty { null })
            }
            else -> null
        }
    }

    private fun flatten(text: String) = text.replace(Regex("\\s+"), " ").trim()
}
