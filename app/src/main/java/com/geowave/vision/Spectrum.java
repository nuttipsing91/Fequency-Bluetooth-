package com.geowave.vision;

final class Spectrum {
    static final int SIZE = 2048;
    static final int RATE = 16000;

    static double[] analyze(short[] pcm) {
        double[] real = new double[SIZE];
        double[] imaginary = new double[SIZE];
        double squares = 0;
        for (int i = 0; i < SIZE; i++) {
            double value = pcm[i] / 32768.0;
            squares += value * value;
            int reversed = Integer.reverse(i) >>> (32 - Integer.numberOfTrailingZeros(SIZE));
            real[reversed] = value * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (SIZE - 1)));
        }
        for (int length = 2; length <= SIZE; length *= 2) {
            for (int j = 0; j < length / 2; j++) {
                double angle = -2 * Math.PI * j / length;
                double cosine = Math.cos(angle);
                double sine = Math.sin(angle);
                for (int i = j; i < SIZE; i += length) {
                    int peer = i + length / 2;
                    double r = cosine * real[peer] - sine * imaginary[peer];
                    double q = sine * real[peer] + cosine * imaginary[peer];
                    real[peer] = real[i] - r;
                    imaginary[peer] = imaginary[i] - q;
                    real[i] += r;
                    imaginary[i] += q;
                }
            }
        }
        int peak = 0;
        double best = 0;
        for (int i = 1; i < SIZE / 2; i++) {
            double power = real[i] * real[i] + imaginary[i] * imaginary[i];
            if (power > best) {
                best = power;
                peak = i;
            }
        }
        double db = 20 * Math.log10(Math.max(1e-6, Math.sqrt(squares / SIZE)));
        return new double[]{db, db <= -100 ? Double.NaN : peak * (double) RATE / SIZE};
    }
}
