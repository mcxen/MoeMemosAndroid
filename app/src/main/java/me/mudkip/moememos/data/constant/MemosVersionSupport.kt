package me.mudkip.moememos.data.constant

import android.content.Context
import me.mudkip.moememos.R
import net.swiftzer.semver.SemVer

object MemosVersionSupport {
    const val MEMOS_V0_MIN_VERSION_NAME = "0.21.0"
    /** Includes ~0.22–0.24 workspace API and ≥0.27 instance/attachments API via runtime fallbacks. */
    const val MEMOS_V1_MIN_VERSION_NAME = "0.22.0"
    const val MEMOS_V1_MAX_VERSION_NAME = "0.29.1"

    val MEMOS_V0_MIN_VERSION = SemVer(0, 21, 0)
    val MEMOS_V1_MIN_VERSION = SemVer(0, 22, 0)
    val MEMOS_V1_MAX_VERSION = SemVer(0, 29, 1)

    fun supportedVersionsMessage(context: Context): String {
        return context.getString(
            R.string.memos_supported_versions,
            MEMOS_V0_MIN_VERSION_NAME,
            MEMOS_V1_MIN_VERSION_NAME,
            MEMOS_V1_MAX_VERSION_NAME,
        )
    }
}
