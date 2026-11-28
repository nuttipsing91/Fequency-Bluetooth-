package com.geowave.vision;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

final class ScanLog {
    static final int LIMIT = 18000;
    private static final String HEADER = "sample_utc,sample_elapsed_ns,ar_frame_ns,space,ar_session_id,x_m,y_m,z_m,"
        + "mag_elapsed_ns,mag_accuracy,mag_x_uT,mag_y_uT,mag_z_uT,"
        + "accel_elapsed_ns,accel_accuracy,accel_x_m_s2,accel_y_m_s2,accel_z_m_s2,"
        + "gyro_elapsed_ns,gyro_accuracy,gyro_x_rad_s,gyro_y_rad_s,gyro_z_rad_s,"
        + "light_elapsed_ns,light_accuracy,light_lux,mic_read_elapsed_ns,mic_rms_dBFS,mic_peak_hz\n";
    private final StringBuilder csv = new StringBuilder(HEADER);
    private int count;
    private boolean active;

    synchronized void start() {
        count = 0;
        csv.setLength(0);
        csv.append(HEADER);
        active = true;
    }

    synchronized boolean toggle() {
        if (active) active = false;
        else start();
        return active;
    }

    synchronized boolean active() { return active; }
    synchronized int count() { return count; }
    synchronized void stop() { active = false; }

    synchronized void append(long elapsed, long frame, String session, float[] point, Map<Integer, Measurements.Reading> readings) {
        if (!active) return;
        csv.append(Instant.now()).append(',').append(elapsed).append(',');
        if (frame != 0) csv.append(frame);
        csv.append(',').append(point == null ? "unlocated" : "arcore_local").append(',');
        if (point != null) csv.append(session);
        for (int i = 0; i < 3; i++) {
            csv.append(',');
            if (point != null) csv.append(point[i]);
        }
        for (int type : Measurements.TYPES) {
            Measurements.Reading reading = readings.get(type);
            csv.append(',');
            if (reading != null) csv.append(reading.nanos());
            if (type != -1) {
                csv.append(',');
                if (reading != null) csv.append(reading.accuracy());
            }
            int channels = type == -1 ? 2 : type == android.hardware.Sensor.TYPE_LIGHT ? 1 : 3;
            for (int i = 0; i < channels; i++) {
                csv.append(',');
                if (reading != null && i < reading.values().length && Double.isFinite(reading.values()[i])) {
                    csv.append(reading.values()[i]);
                }
            }
        }
        csv.append('\n');
        if (++count >= LIMIT) active = false;
    }

    synchronized byte[] snapshot() { return csv.toString().getBytes(StandardCharsets.UTF_8); }

    static void save(File destination, byte[] data) throws IOException {
        AtomicFile file = new AtomicFile(destination);
        FileOutputStream out = file.startWrite();
        try {
            out.write(data);
            file.finishWrite(out);
        } catch (IOException e) {
            file.failWrite(out);
            throw e;
        }
    }
}
