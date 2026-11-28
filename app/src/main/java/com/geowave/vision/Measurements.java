package com.geowave.vision;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.*;
import android.media.*;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.Map;

final class Measurements implements SensorEventListener {
    record Reading(long nanos, int accuracy, double[] values) {}
    static final int[] TYPES = {Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE, Sensor.TYPE_LIGHT, -1};
    final SensorManager manager;
    private final Map<Integer, Reading> current = new HashMap<>();
    private AudioRecord mic;

    Measurements(Context context) {
        manager = context.getSystemService(SensorManager.class);
    }

    void start() {
        for (int type : TYPES) {
            if (type < 0) continue;
            Sensor sensor = manager.getDefaultSensor(type);
            if (sensor != null) manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @SuppressLint("MissingPermission")
    synchronized boolean startMic() {
        if (mic != null) return true;
        int size = AudioRecord.getMinBufferSize(Spectrum.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (size <= 0) return false;
        AudioRecord recorder = null;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, Spectrum.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(size, Spectrum.SIZE * 4));
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release();
                return false;
            }
        } catch (RuntimeException e) {
            if (recorder != null) recorder.release();
            return false;
        }
        AudioRecord active = recorder;
        mic = active;
        new Thread(() -> readMic(active), "GeoWave microphone").start();
        return true;
    }

    private void readMic(AudioRecord recorder) {
        try {
            short[] samples = new short[Spectrum.SIZE];
            int filled = 0;
            while (true) {
                synchronized (this) { if (mic != recorder) break; }
                int count = recorder.read(samples, filled, samples.length - filled);
                if (count < 0) break;
                filled += count;
                if (filled == samples.length) {
                    long captured = SystemClock.elapsedRealtimeNanos();
                    double[] spectrum = Spectrum.analyze(samples);
                    synchronized (this) {
                        if (mic == recorder) current.put(-1, new Reading(captured, -1, spectrum));
                    }
                    filled = 0;
                }
            }
        } finally {
            synchronized (this) {
                if (mic == recorder) {
                    mic = null;
                    current.remove(-1);
                }
                recorder.release();
            }
        }
    }

    synchronized void stop() {
        manager.unregisterListener(this);
        current.clear();
        if (mic != null) {
            AudioRecord recorder = mic;
            mic = null;
            try { recorder.stop(); } catch (IllegalStateException ignored) {}
        }
    }

    @Override public synchronized void onSensorChanged(SensorEvent event) {
        double[] values = new double[event.values.length];
        for (int i = 0; i < values.length; i++) values[i] = event.values[i];
        current.put(event.sensor.getType(), new Reading(event.timestamp, event.accuracy, values));
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    synchronized Map<Integer, Reading> snapshot() {
        Map<Integer, Reading> result = new HashMap<>();
        long now = SystemClock.elapsedRealtimeNanos();
        current.forEach((type, reading) -> {
            if (now - reading.nanos() < 2_000_000_000L) result.put(type, reading);
        });
        return result;
    }

    static double magnitude(Reading reading) {
        if (reading == null) return Double.NaN;
        double[] v = reading.values();
        return v.length >= 3 ? Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]) : v[0];
    }
}
