package de.jobisoft.amster.data.media

import android.service.notification.NotificationListenerService

/**
 * Does nothing itself. Android only lets an app list the media sessions of other apps
 * (MediaSessionManager.getActiveSessions) if the user has given it "notification access",
 * and that permission belongs to a NotificationListenerService.
 */
class AmazonMusicListener : NotificationListenerService()
