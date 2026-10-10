package com.bydassistantng.update

/**
 * A published version of the app: `1.2.3` for a stable build, `1.2.3-beta.4` for a beta of the next stable
 * version (so every beta sorts below the stable build it leads up to).
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val beta: Int? = null) : Comparable<AppVersion> {
    val isBeta: Boolean get() = beta != null

    /**
     * The Android `versionCode` a build of this version carries. It is computed, not counted by hand, so a newer
     * version always has a higher code and the system never refuses an update as a downgrade; app/build.gradle.kts
     * uses the same formula.
     */
    val code: Int get() = major * 10_000_000 + minor * 100_000 + patch * 1_000 + (beta ?: STABLE_SUFFIX)

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.beta ?: Int.MAX_VALUE })

    override fun toString(): String = "$major.$minor.$patch" + (beta?.let { "-beta.$it" } ?: "")

    companion object {
        private const val STABLE_SUFFIX = 999
        private val PATTERN = Regex("""^[vV]?(\d{1,2})\.(\d{1,2})\.(\d{1,2})(?:-beta\.(\d{1,3}))?$""")

        /** Reads `1.2.3`, `v1.2.3` or `1.2.3-beta.4`; null for anything else (including local `-dev` builds). */
        fun parse(text: String): AppVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, beta) = match.destructured
            val betaNumber = beta.takeIf { it.isNotEmpty() }?.toInt()
            if (betaNumber != null && betaNumber !in 1..998) return null
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt(), betaNumber)
        }
    }
}

/** Which releases the app offers as updates. */
enum class UpdateChannel(val id: String) {
    /** Only releases that were promoted to stable. */
    STABLE("stable"),

    /** Every build as soon as it is published: betas and stable releases alike, whichever is newest. */
    BETA("beta");

    fun accepts(release: UpdateRelease): Boolean = when (this) {
        STABLE -> !release.prerelease && !release.version.isBeta
        BETA -> true
    }

    companion object {
        fun fromId(id: String?): UpdateChannel? = entries.find { it.id == id }

        /** The channel an installed build came from, judged by its version name. */
        fun ofVersionName(versionName: String): UpdateChannel = if ("-beta." in versionName) BETA else STABLE
    }
}
