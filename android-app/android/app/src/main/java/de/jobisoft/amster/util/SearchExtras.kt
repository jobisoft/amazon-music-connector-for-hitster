package de.jobisoft.amster.util

import android.app.SearchManager
import android.provider.MediaStore

/**
 * The fields that go along with a search for a song, as pairs of extra name and text. Kept apart from the
 * Bundle so that a test can check them. The album is sent only if the card set has one: it helps Amazon
 * Music to find the right release of the song, and a card without an album sends nothing for it.
 */
object SearchExtras {
    fun fields(title: String, artist: String, album: String?): List<Pair<String, String>> = buildList {
        add(MediaStore.EXTRA_MEDIA_FOCUS to MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
        add(MediaStore.EXTRA_MEDIA_TITLE to title)
        add(MediaStore.EXTRA_MEDIA_ARTIST to artist)
        if (!album.isNullOrBlank()) add(MediaStore.EXTRA_MEDIA_ALBUM to album.trim())
        add(SearchManager.QUERY to SearchQuery.query(title, artist))
    }
}
