package com.pooyan.nyxora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;

public class PlayerService extends Service {
    static final String CH = "nyx_play";
    static final String A_PLAY = "nyx.play", A_PAUSE = "nyx.pause", A_NEXT = "nyx.next", A_PREV = "nyx.prev", A_STOP = "nyx.stop";
    static PlayerService svc;
    static long tid = -1;
    static String title = "", artist = "";
    static boolean playing = false;
    static long pos = 0, dur = 0;

    private MediaSession ms;
    private Bitmap art;
    private long artId = -2;
    private AudioManager am;
    private AudioFocusRequest focus;
    private boolean lostTransient = false;
    private BroadcastReceiver noisy;

    static void update(Context c, long id, String t, String a, boolean p, long ps, long d) {
        tid = id; title = t == null ? "" : t; artist = a == null ? "" : a; playing = p; pos = ps; dur = d;
        try {
            if (svc != null) {
                svc.refresh();
            } else if (p) {
                c.startForegroundService(new Intent(c, PlayerService.class));
            }
        } catch (Exception e) { }
    }

    static void halt(Context c) {
        try {
            if (svc != null) svc.shutdown();
            else c.stopService(new Intent(c, PlayerService.class));
        } catch (Exception e) { }
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        svc = this;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CH, "NYXORA", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        am = (AudioManager) getSystemService(AUDIO_SERVICE);
        ms = new MediaSession(this, "NYXORA");
        ms.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { cmd("play"); }
            @Override public void onPause() { cmd("pause"); }
            @Override public void onSkipToNext() { cmd("next"); }
            @Override public void onSkipToPrevious() { cmd("prev"); }
            @Override public void onSeekTo(long p) { cmd("seek:" + p); }
        });
        ms.setActive(true);
        noisy = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { cmd("pause"); }
        };
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    static void cmd(String c) {
        MainActivity.js("window.__cmd&&window.__cmd('" + c + "')");
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        String a = i == null ? null : i.getAction();
        if (A_PLAY.equals(a)) cmd("play");
        else if (A_PAUSE.equals(a)) cmd("pause");
        else if (A_NEXT.equals(a)) cmd("next");
        else if (A_PREV.equals(a)) cmd("prev");
        else if (A_STOP.equals(a)) { cmd("pause"); shutdown(); return START_NOT_STICKY; }
        refresh();
        return START_NOT_STICKY;
    }

    private PendingIntent act(String a) {
        Intent i = new Intent(this, PlayerService.class).setAction(a);
        return PendingIntent.getService(this, a.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void loadArt() {
        if (artId == tid) return;
        artId = tid;
        art = null;
        if (tid < 0) return;
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            Uri u = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, tid);
            m.setDataSource(this, u);
            byte[] b = m.getEmbeddedPicture();
            if (b != null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                art = BitmapFactory.decodeByteArray(b, 0, b.length, o);
            }
        } catch (Exception e) {
            art = null;
        } finally {
            try { m.release(); } catch (Exception e) { }
        }
    }

    private void wantFocus() {
        try {
            if (!playing) return;
            if (focus == null) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                            public void onAudioFocusChange(int ch) {
                                if (ch == AudioManager.AUDIOFOCUS_LOSS) { lostTransient = false; cmd("pause"); }
                                else if (ch == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { lostTransient = playing; cmd("pause"); }
                                else if (ch == AudioManager.AUDIOFOCUS_GAIN && lostTransient) { lostTransient = false; cmd("play"); }
                            }
                        }).build();
            }
            am.requestAudioFocus(focus);
        } catch (Exception e) { }
    }

    void refresh() {
        loadArt();
        wantFocus();
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
        if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        ms.setMetadata(mb.build());
        long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
        ms.setPlaybackState(new PlaybackState.Builder().setActions(acts)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f).build());
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title.length() > 0 ? title : "NYXORA")
                .setContentText(artist)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "prev", act(A_PREV)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                        playing ? "pause" : "play", act(playing ? A_PAUSE : A_PLAY)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "next", act(A_NEXT)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(ms.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (art != null) b.setLargeIcon(art);
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(1, n);
        } catch (Exception e) { }
        if (!playing) {
            try { stopForeground(false); } catch (Exception e) { }
        }
    }

    void shutdown() {
        playing = false;
        try { stopForeground(true); } catch (Exception e) { }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        try { if (focus != null) am.abandonAudioFocusRequest(focus); } catch (Exception e) { }
        try { unregisterReceiver(noisy); } catch (Exception e) { }
        try { ms.setActive(false); ms.release(); } catch (Exception e) { }
        svc = null;
        super.onDestroy();
    }
}
