package com.amusic.ui.nav

import java.net.URLEncoder

object Routes {
    const val MUSIC_HALL = "music_hall"
    const val DISCOVER = "discover"
    const val MINE = "mine"
    const val ARTIST = "artist/{name}"
    const val PLAYLIST = "playlist/{id}"
    const val NOW_PLAYING = "now_playing"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val TRASH = "trash"
    const val PLAY_QUEUE = "play_queue"

    /**
     * Build the concrete route for one artist. Names come from ID3 tags and routinely
     * contain `/`, `&` or CJK text, so they must be percent-encoded — [AppNav] decodes
     * the argument again before handing it to the screen.
     */
    fun artist(name: String): String = "artist/" + URLEncoder.encode(name, "UTF-8")

    fun playlist(id: Long): String = "playlist/$id"
}
