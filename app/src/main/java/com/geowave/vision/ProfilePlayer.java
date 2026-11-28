package com.geowave.vision;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class ProfilePlayer {
    interface Listener { void changed(boolean active, boolean error); }

    private final Handler timer = new Handler(Looper.getMainLooper());
    private final Context context;
    private final Listener listener;
    private MediaPlayer player;
    private long end;
    private float volume = .5f;
    private final Runnable timeout = this::stop;

    ProfilePlayer(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean active() { return player != null; }

    long remaining() {
        return active() ? Math.max(0, (end - SystemClock.elapsedRealtime() + 999) / 1000) : 0;
    }

    void amplitude(float value) {
        volume = value;
        if (player != null) player.setVolume(volume, volume);
    }

    void play(Uri uri, int seconds, boolean loop) {
        stop();
        try {
            if (uri == null) uri = Uri.fromFile(createProfile());
            MediaPlayer next = new MediaPlayer();
            player = next;
            next.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            next.setDataSource(context, uri);
            next.setOnPreparedListener(ready -> {
                if (player != ready) return;
                ready.setVolume(volume, volume);
                ready.setLooping(loop);
                ready.start();
                end = SystemClock.elapsedRealtime() + seconds * 1000L;
                timer.postDelayed(timeout, seconds * 1000L);
                listener.changed(true, false);
            });
            next.setOnErrorListener((failed, what, extra) -> {
                if (failed == player) {
                    stop();
                    listener.changed(false, true);
                }
                return true;
            });
            next.setOnCompletionListener(done -> { if (done == player) stop(); });
            next.prepareAsync();
        } catch (IOException | RuntimeException e) {
            stop();
            listener.changed(false, true);
        }
    }

    void stop() {
        timer.removeCallbacks(timeout);
        if (player != null) {
            player.release();
            player = null;
            listener.changed(false, false);
        }
    }

    static boolean isWav(InputStream stream) throws IOException {
        if (stream == null) return false;
        try (InputStream input = stream) {
            byte[] header = new byte[12];
            try {
                new DataInputStream(input).readFully(header);
            } catch (EOFException e) {
                return false;
            }
            return header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E';
        }
    }

    private File createProfile() throws IOException {
        File file = new File(context.getCacheDir(), "geowave-symbolic-v4.wav");
        if (file.exists() && file.length() == 160044) return file;
        int rate = 16000, samples = rate * 5;
        ByteBuffer wav = ByteBuffer.allocate(44 + samples*2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + samples*2);
        wav.put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16).putShort((short)1)
            .putShort((short)1).putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16);
        wav.put(new byte[]{'d','a','t','a'}).putInt(samples*2);
        for (int i = 0; i < samples; i++) {
            double t = (double) i / rate;
            double envelope = Math.min(1, Math.min(t, 5-t) * 4);
            double modulation = .6 + .4*Math.sin(2*Math.PI*.6*t);
            double tone = .7*Math.sin(2*Math.PI*220*t) + .3*Math.sin(2*Math.PI*330*t);
            wav.putShort((short)(8000 * envelope * modulation * tone));
        }
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(wav.array()); }
        return file;
    }
}
