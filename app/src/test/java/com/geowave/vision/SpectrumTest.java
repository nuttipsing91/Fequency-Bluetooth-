package com.geowave.vision;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SpectrumTest {
    @Test public void peakAndDigitalLevelMatchKnownTone() {
        short[] pcm = new short[Spectrum.SIZE];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) Math.round(16384 * Math.sin(2 * Math.PI * 1000 * i / Spectrum.RATE));
        }
        double[] result = Spectrum.analyze(pcm);
        assertEquals(-9.03, result[0], .01);
        assertEquals(1000, result[1], .01);
    }

    @Test public void silenceHasNoPeak() {
        double[] result = Spectrum.analyze(new short[Spectrum.SIZE]);
        assertEquals(-120, result[0], .01);
        assertTrue(Double.isNaN(result[1]));
    }
}
