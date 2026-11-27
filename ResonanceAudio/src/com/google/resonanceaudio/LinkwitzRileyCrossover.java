package com.google.resonanceaudio;

/**
 * Complementary 4th-order Linkwitz-Riley (LR4, 24 dB/oct) crossover filter
 * implemented as two cascaded 2nd-order Butterworth biquad sections in
 * double-precision Direct Form II Transposed.
 *
 * <p>The low-pass path processes a mono buffer (the subwoofer feed derived
 * from the Ambisonic W channel). The high-pass path processes an interleaved
 * multi-channel buffer (all Ambisonic B-format channels) with identical
 * coefficients so the phase shift is uniform across all channels and the
 * magnitude sum {@code |H_LP(f) + H_HP(f)| = 1} (all-pass, 0 dB ripple) at
 * every frequency.</p>
 *
 * <p>Coefficient updates via {@link #setCutoffFrequency(float)} are published
 * atomically via a {@code volatile} snapshot so callers on another thread can
 * adjust the crossover frequency without tearing coefficients on the audio
 * thread.</p>
 */
final class LinkwitzRileyCrossover {

  private static final double INV_SQRT2 = 1.0 / Math.sqrt(2.0);
  private static final double MIN_CUTOFF_HZ = 10.0;
  private static final double DENORMAL_THRESHOLD = 1e-30;

  private static final class Coeffs {
    final float cutoffHz;
    final double a1;
    final double a2;
    final double lpB0;
    final double lpB1;
    final double lpB2;
    final double hpB0;
    final double hpB1;
    final double hpB2;

    Coeffs(double sampleRate, float requestedCutoffHz) {
      double maxCutoff = 0.45 * sampleRate;
      double fc = Math.max(MIN_CUTOFF_HZ, Math.min(maxCutoff, requestedCutoffHz));
      this.cutoffHz = (float) fc;

      double omega = 2.0 * Math.PI * fc / sampleRate;
      double cosW = Math.cos(omega);
      double sinW = Math.sin(omega);
      // 2nd-order Butterworth: Q = 1 / sqrt(2), so alpha = sin(w) / (2 * Q) = sin(w) / sqrt(2).
      double alpha = sinW * INV_SQRT2;
      double invA0 = 1.0 / (1.0 + alpha);

      this.a1 = (-2.0 * cosW) * invA0;
      this.a2 = (1.0 - alpha) * invA0;

      double oneMinusCos = 1.0 - cosW;
      this.lpB0 = (0.5 * oneMinusCos) * invA0;
      this.lpB1 = oneMinusCos * invA0;
      this.lpB2 = this.lpB0;

      double onePlusCos = 1.0 + cosW;
      this.hpB0 = (0.5 * onePlusCos) * invA0;
      this.hpB1 = (-onePlusCos) * invA0;
      this.hpB2 = this.hpB0;
    }
  }

  private final double sampleRate;
  private final int numHighPassChannels;
  private volatile Coeffs coeffs;

  // Two cascaded biquad states for mono low-pass path.
  private double lp1Z1;
  private double lp1Z2;
  private double lp2Z1;
  private double lp2Z2;

  // Two cascaded biquad states per channel for interleaved high-pass path.
  private final double[] hp1Z1;
  private final double[] hp1Z2;
  private final double[] hp2Z1;
  private final double[] hp2Z2;

  LinkwitzRileyCrossover(int sampleRate, int numHighPassChannels,
      float cutoffHz) {
    if (sampleRate <= 0) {
      throw new IllegalArgumentException("sampleRate must be positive: "
          + sampleRate);
    }
    if (numHighPassChannels <= 0) {
      throw new IllegalArgumentException(
          "numHighPassChannels must be positive: " + numHighPassChannels);
    }
    this.sampleRate = sampleRate;
    this.numHighPassChannels = numHighPassChannels;
    this.hp1Z1 = new double[numHighPassChannels];
    this.hp1Z2 = new double[numHighPassChannels];
    this.hp2Z1 = new double[numHighPassChannels];
    this.hp2Z2 = new double[numHighPassChannels];
    setCutoffFrequency(cutoffHz);
  }

  void setCutoffFrequency(float cutoffHz) {
    if (Float.isNaN(cutoffHz) || Float.isInfinite(cutoffHz) || cutoffHz <= 0f) {
      throw new IllegalArgumentException(
          "Crossover frequency must be a finite positive number: " + cutoffHz);
    }
    this.coeffs = new Coeffs(sampleRate, cutoffHz);
  }

  float getCutoffFrequency() {
    return coeffs.cutoffHz;
  }

  void reset() {
    lp1Z1 = 0.0;
    lp1Z2 = 0.0;
    lp2Z1 = 0.0;
    lp2Z2 = 0.0;
    for (int c = 0; c < numHighPassChannels; c++) {
      hp1Z1[c] = 0.0;
      hp1Z2[c] = 0.0;
      hp2Z1[c] = 0.0;
      hp2Z2[c] = 0.0;
    }
  }

  /** Applies the LR4 low-pass filter in place to {@code monoBuf[0..numFrames-1]}. */
  void processLowPass(float[] monoBuf, int numFrames) {
    Coeffs c = this.coeffs;
    double b0 = c.lpB0;
    double b1 = c.lpB1;
    double b2 = c.lpB2;
    double a1 = c.a1;
    double a2 = c.a2;

    double z1a = lp1Z1;
    double z2a = lp1Z2;
    double z1b = lp2Z1;
    double z2b = lp2Z2;

    for (int s = 0; s < numFrames; s++) {
      double x = monoBuf[s];
      double y1 = b0 * x + z1a;
      z1a = b1 * x - a1 * y1 + z2a;
      z2a = b2 * x - a2 * y1;

      double y2 = b0 * y1 + z1b;
      z1b = b1 * y1 - a1 * y2 + z2b;
      z2b = b2 * y1 - a2 * y2;

      monoBuf[s] = (float) y2;
    }

    lp1Z1 = flushDenormal(z1a);
    lp1Z2 = flushDenormal(z2a);
    lp2Z1 = flushDenormal(z1b);
    lp2Z2 = flushDenormal(z2b);
  }

  /**
   * Applies the LR4 high-pass filter in place to each channel of an
   * interleaved buffer containing {@code numFrames * numHighPassChannels}
   * samples.
   */
  void processHighPassInterleaved(float[] interleaved, int numFrames) {
    Coeffs c = this.coeffs;
    double b0 = c.hpB0;
    double b1 = c.hpB1;
    double b2 = c.hpB2;
    double a1 = c.a1;
    double a2 = c.a2;
    int chCount = this.numHighPassChannels;

    for (int ch = 0; ch < chCount; ch++) {
      double z1a = hp1Z1[ch];
      double z2a = hp1Z2[ch];
      double z1b = hp2Z1[ch];
      double z2b = hp2Z2[ch];

      int idx = ch;
      for (int s = 0; s < numFrames; s++) {
        double x = interleaved[idx];
        double y1 = b0 * x + z1a;
        z1a = b1 * x - a1 * y1 + z2a;
        z2a = b2 * x - a2 * y1;

        double y2 = b0 * y1 + z1b;
        z1b = b1 * y1 - a1 * y2 + z2b;
        z2b = b2 * y1 - a2 * y2;

        interleaved[idx] = (float) y2;
        idx += chCount;
      }

      hp1Z1[ch] = flushDenormal(z1a);
      hp1Z2[ch] = flushDenormal(z2a);
      hp2Z1[ch] = flushDenormal(z1b);
      hp2Z2[ch] = flushDenormal(z2b);
    }
  }

  private static double flushDenormal(double v) {
    return Math.abs(v) < DENORMAL_THRESHOLD ? 0.0 : v;
  }
}
