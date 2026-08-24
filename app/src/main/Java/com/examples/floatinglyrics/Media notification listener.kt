package com.example.floatinglyrics

import android.content.Intent
import android.media.MediaMetadata
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val extras = sbn?.notification?.extras ?: return

        // Extract metadata from standard media notifications
        val trackTitle = extras.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: extras.getCharSequence("android.title")?.toString()
        val artist = extras.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: extras.getCharSequence("android.text")?.toString()

        if (!trackTitle.isNullOrEmpty() && !artist.isNullOrEmpty()) {
            val updateIntent = Intent(this, FloatingLyricsService::class.java).apply {
                putExtra("TRACK_TITLE", trackTitle)
                putExtra("ARTIST_NAME", artist)
            }
            startService(updateIntent)
        }
    }
}
