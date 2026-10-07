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
