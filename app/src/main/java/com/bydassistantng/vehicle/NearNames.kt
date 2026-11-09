package com.bydassistantng.vehicle

import kotlin.math.abs

/**
 * What the car's own table of features has that resembles a name the app looked for and did not find, for the helper's log
 * line. [FeatureNameCheck] says which of the names the app uses the car does not have (`absent=`); that does not say whether the
 * car has the same thing under another name, and a feature that works on one car can sit under a different name on the next
 * (the exterior speaker and the drive modes were found under one name on a Leopard 5; a DiLink 3.0 owner has them on a car whose
 * table has neither). Two fields answer it from the owner's log, without a cable:
 *
 *  - `similar=` — for each missing name, up to [PER_NAME] names of the car's table that share the words that say what the name
 *    is about (`PRESELECTED_DRIVING_MODE` shares `DRIVING_MODE` with `SETTING_TARGET_DRIVING_MODE`), each with the car's number;
 *  - `scan.<topic>=` — when a feature the app can offer is missing, every name of the table (up to [MAX_SCANNED]) that has to do
 *    with its topic, for the cases where the other name shares no word with ours (the drive mode's other home is
 *    `ENERGY_OPERATION_MODE_SET`; the exterior speaker's is `AUDIO_EXTERIOR_SPEAKER_SWITCH_SET`).
 *
 * Only names and numbers of the car's table are written, nothing is read from or written to the car, and a car that has every name
 * gets neither field.
 */
internal object NearNames {
    /** At most this many missing names are looked up, so that a car that lacks most of the names stays one readable line. */
    private const val MAX_MISSING = 24

    /** At most this many resembling names are written for one missing name. */
    private const val PER_NAME = 3

    /** At most this many names are written for one topic. */
    private const val MAX_SCANNED = 40

    /** Words that say what kind of entry a name is, not what it is about. */
    private val NOISE = setOf("SET", "STATE", "STATUS", "SIGN", "FLAG", "CONFIG", "CONFIGURATION", "VALUE", "HAL", "ONLY")

    /**
     * Names that only look like a topic's, which a topic's pattern starts by ruling out: a seat massage has an operation mode, a factory
     * test has an AVAS entry, a fault flag has the speaker's name in it.
     */
    private const val NOT_A_DRIVE_MODE = "^(?!TEST_|.*(MASSAGE|SEAT|PROMPT|FAULT|INDICATOR|WARN))"
    private const val NOT_A_SPEAKER = "^(?!TEST_|.*(FAULT|WARN|NAVP))"

    /**
     * A feature that is offered only when the car's table names it as it was verified ([feature] is its label in [OtherFeatures]),
     * and the words of the names that may hold the same thing on a car that names it otherwise ([pattern], matched against the
     * name without its group).
     */
    class Topic(val key: String, val feature: String, val pattern: Regex)

    val topics: List<Topic> = listOf(
        Topic("drive", "driveMode.preselect", Regex("$NOT_A_DRIVE_MODE.*(DRIV(E|ING)_MODE|ENERGY_(OPERATION_|ROAD_SURFACE_)?MODE|ECO_MODE|SPORT_MODE|NORMAL_MODE)")),
        Topic("speaker", "exteriorSpeaker.switch", Regex("$NOT_A_SPEAKER.*(EXTERIOR_(SPEAKER|PROMPT)|EXTERNAL_SPEAKER|UE_BROADCAST|LOCK_CAR_SOUND|AVAS|OUTSIDE_(CAR|VOICE|SPEAKER)|VOICE_BROADCAST)")),
    )

    /**
     * The `similar=` and `scan.<topic>=` fields for [results] (what [FeatureNameCheck.check] made of the car's [table]), joined by
     * spaces; null where the car has every name or nothing in its table resembles what is missing.
     */
    fun fields(results: List<FeatureNameCheck.Result>, table: Map<String, Int>): String? {
        val featureLabels = topics.map { it.feature }.toSet()
        // The features the app can offer come first, so that they are never the ones cut off by the limit.
        val missing = results.filter { it.verdict == FeatureNameCheck.Verdict.ABSENT }
            .sortedBy { if (it.reference.owner in featureLabels) 0 else 1 }
            .map { it.reference.name }
            .distinct()
        val parts = ArrayList<String>()
        similar(missing, table)?.let { parts += "similar=$it" }
        for (topic in topics) {
            val offered = results.none { it.reference.owner == topic.feature && it.verdict != FeatureNameCheck.Verdict.SAME }
            if (!offered) scan(topic.pattern, table)?.let { parts += "scan.${topic.key}=$it" }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** `missing:near=number|near=number,...` for the names of [missing] that have a resembling name in [table]; null where none has. */
    internal fun similar(missing: List<String>, table: Map<String, Int>): String? {
        if (missing.isEmpty() || table.isEmpty()) return null
        val index = entries(table)
        val lines = missing.take(MAX_MISSING).mapNotNull { name ->
            val wanted = Entry(name, 0)
            if (wanted.core.isEmpty()) return@mapNotNull null
            // Two shared words, or the one it has: a single common word such as MODE would match half the table.
            val need = minOf(2, wanted.core.size)
            val best = index.asSequence()
                .filter { it.name != name }
                .map { it to it.core.count { word -> word in wanted.core } }
                .filter { (_, shared) -> shared >= need }
                .sortedWith(
                    compareByDescending<Pair<Entry, Int>> { it.second }
                        .thenByDescending { it.first.group == wanted.group }
                        .thenBy { abs(it.first.core.size - wanted.core.size) }
                        .thenBy { it.first.name },
                )
                .take(PER_NAME)
                .toList()
            if (best.isEmpty()) null else name + ":" + best.joinToString("|") { (entry, _) -> "${entry.name}=${entry.number}" }
        }
        if (lines.isEmpty()) return null
        val skipped = missing.size - MAX_MISSING
        return lines.joinToString(",") + if (skipped > 0) ",+$skipped not looked up" else ""
    }

    /** `name=number,...` for the names of [table] whose words (without the group) match [pattern]; null where none does. */
    internal fun scan(pattern: Regex, table: Map<String, Int>): String? {
        val hits = entries(table).filter { pattern.containsMatchIn(it.name.substringAfter('.')) }.sortedBy { it.name }
        if (hits.isEmpty()) return null
        return hits.take(MAX_SCANNED).joinToString(",") { "${it.name}=${it.number}" } +
            if (hits.size > MAX_SCANNED) ",+${hits.size - MAX_SCANNED} more" else ""
    }

    /**
     * The table as entries, without the constants of the class itself that a group repeats under the same name, and without the
     * `_hal_only` copies the table holds of many names, which would fill the few places there are with the same thing twice.
     */
    private fun entries(table: Map<String, Int>): List<Entry> {
        val grouped = table.keys.filter { '.' in it }.mapTo(HashSet()) { it.substringAfter('.') }
        return table.entries
            .filter { !it.key.endsWith("_hal_only") && ('.' in it.key || it.key !in grouped) }
            .map { Entry(it.key, it.value) }
    }

    private class Entry(val name: String, val number: Int) {
        val group: String = name.substringBefore('.', "")

        /** The words that say what the name is about: without its group's word, the words of [NOISE] and numbers. */
        val core: Set<String> = name.substringAfter('.').split('_')
            .map { it.uppercase() }
            .filter { it.length > 1 && it != group.uppercase() && it !in NOISE && !it.all(Char::isDigit) }
            .toSet()
    }
}
