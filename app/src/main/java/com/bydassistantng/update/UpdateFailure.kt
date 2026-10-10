package com.bydassistantng.update

import java.io.IOException

/** Why an update could not be checked, downloaded or installed. The UI turns [kind] into a sentence in the app language. */
class UpdateFailure(val kind: Kind, val detail: String? = null, cause: Throwable? = null) : IOException("$kind${detail?.let { ": $it" } ?: ""}", cause) {
    enum class Kind {
        /** This build doesn't know where updates are published (or is a local developer build). */
        UNSUPPORTED,

        /** A voice conversation is running; installing would cut it off. */
        BUSY,
        OFFLINE,
        RATE_LIMITED,
        SERVER_ERROR,
        BAD_RESPONSE,
        NOT_ENOUGH_SPACE,
        DOWNLOAD_FAILED,

        /** The download's size or checksum didn't match what the release says. */
        CORRUPT,
        WRONG_PACKAGE,
        WRONG_SIGNATURE,
        NOT_NEWER,

        /** No local ADB connection to install silently, and no system installer to fall back on. */
        NO_INSTALLER,
        INSTALL_REJECTED,
        INSTALL_TIMEOUT,
    }

    companion object {
        /** How a non-success reply from GitHub's API is explained; [rateLimitRemaining] is its `x-ratelimit-remaining` header. */
        fun forHttp(code: Int, rateLimitRemaining: String?): UpdateFailure = when {
            code == 429 || (code == 403 && rateLimitRemaining == "0") -> UpdateFailure(Kind.RATE_LIMITED)
            else -> UpdateFailure(Kind.SERVER_ERROR, "HTTP $code")
        }

        /**
         * Reads what `pm install` printed. Null means it reported success; otherwise the failure, carrying the
         * system's own code (`INSTALL_FAILED_...`) as the detail when there is nothing friendlier to say.
         */
        fun fromInstallOutput(output: String): UpdateFailure? {
            if (output.contains("Success")) return null
            val code = Regex("""INSTALL_[A-Z_]+""").find(output)?.value
            return when (code) {
                "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> UpdateFailure(Kind.NOT_ENOUGH_SPACE)
                "INSTALL_FAILED_VERSION_DOWNGRADE" -> UpdateFailure(Kind.NOT_NEWER)
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE", "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" ->
                    UpdateFailure(Kind.WRONG_SIGNATURE)
                else -> UpdateFailure(Kind.INSTALL_REJECTED, code ?: output.trim().take(120).ifEmpty { "no output" })
            }
        }
    }
}
