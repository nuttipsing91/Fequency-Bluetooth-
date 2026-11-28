package com.geowave.vision;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ScanLogTest {
    @Test public void timestampedLocatedAndFallbackRowsMatchHeader() {
        ScanLog log = new ScanLog();
        log.start();
        log.append(1200, 1100, "example-session", new float[]{1, 2, 3},
            Map.of(-1, new Measurements.Reading(1000, -1, new double[]{-80, Double.NaN})));
        log.append(1300, 0, null, null, Map.of());
        String[] rows = new String(log.snapshot(), StandardCharsets.UTF_8).split("\n");
        String[] located = rows[1].split(",", -1);
        String[] fallback = rows[2].split(",", -1);
        assertEquals(rows[0].split(",").length, located.length);
        assertEquals(located.length, fallback.length);
        assertEquals("1100", located[2]);
        assertEquals("arcore_local", located[3]);
        assertEquals("example-session", located[4]);
        assertEquals("1.0", located[5]);
        assertEquals("1000", located[located.length-3]);
        assertEquals("-80.0", located[located.length-2]);
        assertEquals("", located[located.length-1]);
        assertEquals("unlocated", fallback[3]);
        assertEquals("", fallback[4]);
    }
}
