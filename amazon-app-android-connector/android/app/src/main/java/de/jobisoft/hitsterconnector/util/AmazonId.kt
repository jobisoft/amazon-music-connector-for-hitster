package de.jobisoft.hitsterconnector.util

/**
 * The id of an Amazon Music track (the ASIN). The session of Amazon Music reports it as the plain `MEDIA_ID` and as
 * `asin://<ASIN>/` in the metadata key `com.amazon.alexa.externalmediaplayer.metadata.TRACK_ID`.
 */
object AmazonId {
    fun mediaId(asin: String) = "asin://$asin/"

    /**
     * The web address of a track with the parameter `agent=google`. Handed to the session with `playFromUri`, the
     * session loads the track without a screen. [tld] is the ending of the
     * store, for example `de` or `co.uk`.
     */
    fun playUri(asin: String, tld: String) = "https://music.amazon.$tld/tracks/$asin?agent=google"

    private val bare = Regex("^B0[0-9A-Z]{8}$", RegexOption.IGNORE_CASE)
    private val inLink = Regex("(?:/tracks/|trackAsin=)(B0[0-9A-Z]{8})", RegexOption.IGNORE_CASE)

    /** What a field for the id makes of the text: the ASIN in upper case, "" for a blank text (no id), null for anything else. */
    fun fromInput(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return ""
        if (bare.matches(t)) return t.uppercase()
        return inLink.find(t)?.groupValues?.get(1)?.uppercase()
    }
}
