package net.kdt.pojavlaunch.swiftclient;

import android.service.notification.NotificationListenerService;

/**
 * Exists so {@link SwiftNowPlaying} can call
 * {@code MediaSessionManager.getActiveSessions()}: Android only hands out other apps'
 * media sessions to a notification listener. The service itself reads nothing and
 * posts nothing - the user grants it on the system notification-access page.
 */
public class SwiftMediaListener extends NotificationListenerService {
}
