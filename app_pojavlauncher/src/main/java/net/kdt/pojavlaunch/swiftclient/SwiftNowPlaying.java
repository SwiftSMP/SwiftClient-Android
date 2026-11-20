package net.kdt.pojavlaunch.swiftclient;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes {@code <gamedir>/swiftclient/nowplaying.json}, the same file the Windows
 * PowerShell bridge writes. The SwiftClient mod reads it and paints the sky lyrics,
 * so the phone gets the same overlay the desktop does without touching the mod.
 *
 * <p>Reading another app's media session requires notification access. When that is
 * missing the currently playing track is simply not reported, and one notification
 * offers the system page where it can be granted. Nothing else is blocked.
 */
public final class SwiftNowPlaying {
    private static final long INTERVAL_MS = 500L;
    private static final int NOTIFICATION_ID = 6;
    private static final long PROMPT_EVERY_MS = 6L * 60L * 60L * 1000L;

    private static volatile boolean running;
    private static Thread worker;
    private static long lastPromptMs;

    private SwiftNowPlaying() {}

    /** Starts the writer for the current instance. Safe to call twice. */
    public static void start(Context context) {
        if (running) return;
        Context app = context.getApplicationContext();
        File dir = nowPlayingDir(app);
        if (dir == null) return;
        if (!dir.isDirectory() && !dir.mkdirs()) return;

        running = true;
        File target = new File(dir, "nowplaying.json");
        worker = new Thread(() -> loop(app, target), "SwiftClient-NowPlaying");
        worker.setDaemon(true);
        worker.start();

        if (!hasAccess(app)) promptForAccess(app);
    }

    public static void stop() {
        running = false;
        Thread thread = worker;
        worker = null;
        if (thread != null) thread.interrupt();
    }

    private static void loop(Context context, File target) {
        MediaSessionManager sessions =
                (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName listener = new ComponentName(context, SwiftMediaListener.class);
        while (running) {
            try {
                if (sessions != null && hasAccess(context)) write(sessions, listener, target);
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignored) {
                // A session that has gone away, a half-written file, a revoked grant:
                // all of it recovers on the next tick.
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private static void write(MediaSessionManager manager, ComponentName listener, File target)
            throws Exception {
        List<MediaController> controllers = manager.getActiveSessions(listener);
        MediaController chosen = null;
        for (MediaController controller : controllers) {
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                chosen = controller;
                break;
            }
            if (chosen == null && controller.getMetadata() != null) chosen = controller;
        }

        JSONObject out = new JSONObject();
        out.put("generatedAt", System.currentTimeMillis());

        if (chosen == null) {
            out.put("active", false);
            out.put("status", "Stopped");
            writeAtomic(target, out.toString());
            return;
        }

        MediaMetadata metadata = chosen.getMetadata();
        PlaybackState state = chosen.getPlaybackState();
        String title = read(metadata, MediaMetadata.METADATA_KEY_TITLE);
        int playback = state == null ? PlaybackState.STATE_NONE : state.getState();
        boolean playing = playback == PlaybackState.STATE_PLAYING;

        long position = 0;
        if (state != null) {
            position = state.getPosition();
            if (playing && state.getLastPositionUpdateTime() > 0) {
                position += SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            }
        }

        out.put("active", !title.isEmpty());
        out.put("title", title);
        out.put("artist", read(metadata, MediaMetadata.METADATA_KEY_ARTIST));
        out.put("album", read(metadata, MediaMetadata.METADATA_KEY_ALBUM));
        out.put("app", chosen.getPackageName());
        out.put("playing", playing);
        out.put("status", statusOf(playback));
        out.put("positionMs", Math.max(0, position));
        out.put("durationMs", metadata == null ? 0
                : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        out.put("accent", "");
        out.put("bass", 0.0);
        writeAtomic(target, out.toString());
    }

    private static String statusOf(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING:
                return "Playing";
            case PlaybackState.STATE_PAUSED:
                return "Paused";
            case PlaybackState.STATE_BUFFERING:
                return "Buffering";
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
                return "Skipping";
            default:
                return "Stopped";
        }
    }

    private static String read(MediaMetadata metadata, String key) {
        if (metadata == null) return "";
        CharSequence value = metadata.getText(key);
        if (value == null) return "";
        return value.toString().replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** Temp file then rename, so the mod never reads a half-written object. */
    private static void writeAtomic(File target, String body) {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception ignored) {
            return;
        }
        if (!temp.renameTo(target)) {
            // Some Android storage backends refuse the rename; fall back to a direct write.
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static File nowPlayingDir(Context context) {
        try {
            LauncherProfiles.load();
            File gameDir = Tools.getGameDirPath(LauncherProfiles.getCurrentProfile());
            return new File(gameDir, "swiftclient");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** True when this app is allowed to read other apps' media sessions. */
    public static boolean hasAccess(Context context) {
        ComponentName component = new ComponentName(context, SwiftMediaListener.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return manager != null && manager.isNotificationListenerAccessGranted(component);
        }
        String flat = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        return flat != null
                && flat.contains(context.getPackageName() + "/" + SwiftMediaListener.class.getName());
    }

    private static void promptForAccess(Context context) {
        long now = SystemClock.elapsedRealtime();
        if (lastPromptMs != 0 && now - lastPromptMs < PROMPT_EVERY_MS) return;
        lastPromptMs = now;
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pending = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(context, context.getString(R.string.notif_channel_id))
                            .setSmallIcon(R.drawable.notif_icon)
                            .setContentTitle(context.getString(R.string.sc_lyrics_access_title))
                            .setContentText(context.getString(R.string.sc_lyrics_access_body))
                            .setContentIntent(pending)
                            .setAutoCancel(true)
                            .setOnlyAlertOnce(true)
                            .setNotificationSilent();
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable ignored) {
            // POST_NOTIFICATIONS denied on 13+: the toggle is still reachable in settings.
        }
    }
}
