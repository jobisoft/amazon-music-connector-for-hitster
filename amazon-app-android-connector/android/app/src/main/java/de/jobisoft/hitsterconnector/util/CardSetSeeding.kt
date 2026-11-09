package de.jobisoft.hitsterconnector.util

import java.security.MessageDigest

enum class SeedAction { COPY, OVERWRITE, LEAVE }

/** What to do with one bundled card set file, and which hash to record afterwards (null: leave the record as it is). */
data class SeedDecision(val action: SeedAction, val record: String?)

/**
 * The rule for the card sets that come with the app: they are app-owned and simply refreshed, the
 * corrections of the user live in `card_overrides.csv`, so overwriting loses nothing. The phone
 * remembers per bundled file the hash of the copy it seeded last (SharedPreferences).
 *
 *  - no local file, never seeded: copy
 *  - no local file, seeded before: the user deleted it, leave it
 *  - local file, the record is missing or differs from the bundled file: overwrite (and record)
 *  - local file, the record equals the bundled file: nothing to do, no write
 *
 * Files with other names (imported by the user) are not bundled and never touched, the rule is
 * applied to the bundled names only.
 */
object CardSetSeeding {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun decide(assetHash: String, localExists: Boolean, recordedHash: String?): SeedDecision = when {
        !localExists -> if (recordedHash == null) SeedDecision(SeedAction.COPY, assetHash) else SeedDecision(SeedAction.LEAVE, null)
        recordedHash == assetHash -> SeedDecision(SeedAction.LEAVE, null)
        else -> SeedDecision(SeedAction.OVERWRITE, assetHash)
    }
}
