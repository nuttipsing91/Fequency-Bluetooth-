package com.geowave.vision;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ProfilePlayerTest {
    @Test public void acceptsRiffWaveAndRejectsOtherOrTruncatedHeaders() throws Exception {
        assertTrue(ProfilePlayer.isWav(input("RIFFxxxxWAVE")));
        assertFalse(ProfilePlayer.isWav(input("RIFFxxxxWEBP")));
        assertFalse(ProfilePlayer.isWav(input("RIFF")));
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }
}
