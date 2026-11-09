package de.jobisoft.hitsterconnector.util

/**
 * Utility class for parsing QR code URLs
 */
object QRCodeParser {
    
    /**
     * Parse a QR code URL and extract the card identifier
     * Example: http://hitstergame.com/de/aaaa0039/00181
     * Returns: aaaa0039/00181 or the full URL
     */
    fun parseHitsterUrl(qrCodeContent: String): String? {
        val normalizedContent = qrCodeContent.trim()
        return if (isHitsterUrl(normalizedContent)) {
            normalizedContent
        } else if (isYouTubeUrl(normalizedContent)) {
            normalizedContent
        } else {
            null
        }
    }

    private fun isHitsterUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("hitstergame.com/") || lower.contains("hitster.com/")
    }

    private fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/") || lower.contains("youtu.be/")
    }
    
    /**
     * Convert card identifier to JSON filename
     * Example: aaaa0039/00181 -> aaaa0039_00181.json
     */
    fun cardIdToFileName(cardId: String): String {
        return cardId.replace("/", "_") + ".json"
    }
    
    /**
     * Convert JSON filename to card identifier
     * Example: aaaa0039_00181.json -> aaaa0039/00181
     */
    fun fileNameToCardId(fileName: String): String {
        return fileName.removeSuffix(".json").replace("_", "/")
    }
}
