package de.jobisoft.hitsterconnector.util

/**
 * Utility class for parsing QR code URLs and extracting CSV file and row information
 */
object CSVUrlParser {

    /** Raw URL of `playlists.csv` (lists all Hitster CSV filenames in column "File"). */
    const val SONGSEEKER_PLAYLIST_INDEX_URL: String =
        "https://raw.githubusercontent.com/andygruber/songseeker-hitster-playlists/refs/heads/main/playlists.csv"

    /**
     * Parse QR code URL to extract CSV filename and index
     * Examples:
     * - https://www.hitstergame.com/de/00099 -> CSV: hitster-de.csv, Index: 99
     * - https://www.hitstergame.com/de/aaaa0039/00181 -> CSV: hitster-de-aaaa0039.csv, Index: 181
     * Note: Index refers to the value in the first column of the CSV file, not the row number
     */
    data class CSVInfo(
        val csvFileName: String,
        val index: Int  // Index from first column of CSV, not row number
    )
    
    fun parseHitsterUrl(qrCodeUrl: String): CSVInfo? {
        return try {
            if (!isHitsterDomain(qrCodeUrl)) {
                return null
            }
            
            val parts = qrCodeUrl.split("/").filter { it.isNotBlank() }
            
            // Find the index of the domain.
            val domainIndex = parts.indexOfFirst { 
                isHitsterDomain(it)
            }
            
            if (domainIndex == -1) {
                return null
            }
            
            // Language is the next part after the domain
            val language = parts.getOrNull(domainIndex + 1) ?: return null
            
            // Get the remaining parts after language
            val remainingParts = parts.subList(domainIndex + 2, parts.size)
            
            val csvFileName: String
            val index: Int
            
            if (remainingParts.size == 2) {
                // Format: /de/aaaa0039/00181 (language, prefix, index)
                val prefix = remainingParts[0]
                val cardIndex = remainingParts[1]
                
                // Check if prefix matches the pattern (like aaaa0039)
                if (prefix.matches(Regex("^[a-z]{4}\\d{4}$"))) {
                    csvFileName = "hitster-$language-$prefix.csv"
                    index = cardIndex.toIntOrNull() ?: return null
                } else {
                    // If prefix doesn't match pattern, treat first as index
                    csvFileName = "hitster-$language.csv"
                    index = prefix.toIntOrNull() ?: return null
                }
            } else if (remainingParts.size == 1) {
                // Format: /de/00099 (language, index)
                csvFileName = "hitster-$language.csv"
                index = remainingParts[0].toIntOrNull() ?: return null
            } else {
                return null
            }
            
            CSVInfo(csvFileName, index)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Convert GitHub repo URL to raw content URL
     */
    fun toGitHubRawUrl(csvFileName: String, baseRepoUrl: String = "https://github.com/andygruber/songseeker-hitster-playlists"): String {
        val repoPath = baseRepoUrl
            .replace("github.com", "raw.githubusercontent.com")
            .replace("/tree/main", "")
            .replace("/tree/master", "")
        return "$repoPath/main/$csvFileName"
    }

    private fun isHitsterDomain(value: String): Boolean {
        val lower = value.lowercase()
        return lower.contains("hitstergame.com") || lower.contains("hitster.com")
    }
}
