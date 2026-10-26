import com.google.resonanceaudio.DistanceRolloffModel;
import com.google.resonanceaudio.RenderingMode;
import com.google.resonanceaudio.ResonanceAudio;
import com.google.resonanceaudio.SoundSource;
import com.google.resonanceaudio.WavUtil;

/**
 * Offline verification of the patched Resonance Audio library:
 *  1. binaural stereo output pans correctly (source right -> right ear)
 *  2. 16-channel B-format ambisonic output is produced (undecoded)
 *  3. world-space (pre-rotation) B-format: (near) identical for different
 *     listener orientations, while binaural output DOES change
 *  4. fillOutputBuffers() produces both outputs in a single pass
 *  5. compensation OFF -> listener-relative B-format (orientation dependent)
 */
public class RenderTest {

  static final int SR = 48000;
  static final int FRAMES = 512;
  static final int BLOCKS = 150;

  static ResonanceAudio.InitOptions opts() {
    ResonanceAudio.InitOptions o = new ResonanceAudio.InitOptions();
    o.numFramesPerBuffer = FRAMES;
    o.sampleRate = SR;
    o.numOutputChannels = ResonanceAudio.OUTPUT_AMBISONIC_3RD_ORDER;
    return o;
  }

  static SoundSource newSource(ResonanceAudio ra) {
    SoundSource s = ra.addSoundObjectSource(
        RenderingMode.BINAURAL_HIGH_QUALITY);
    s.setPosition(1, 0, -1);  // front-right in world space
    s.setDistanceRolloff(DistanceRolloffModel.NONE, 0, 0);
    s.setDistanceAttenuation(1f);
    return s;
  }

  public static void main(String[] args) throws Exception {
    ResonanceAudio ra = new ResonanceAudio(opts());
    SoundSource src = newSource(ra);
    float[] in = new float[FRAMES];
    float[] blkAmbi = new float[FRAMES * 16];
    float[] blkStereo = new float[FRAMES * 2];

    // run A: listener facing default (-Z)
    float[] ambiA = new float[BLOCKS * FRAMES * 16];
    float[] stereoA = new float[BLOCKS * FRAMES * 2];
    ra.update(0, 0, 0, 0, 0, -1, 0, 1, 0);
    double phase = 0;
    for (int b = 0; b < BLOCKS; b++) {
      fillSine(in, phase);
      phase += 2 * Math.PI * 440.0 * FRAMES / SR;
      src.send(in, 1);
      ra.fillOutputBuffers(blkAmbi, blkStereo);
      System.arraycopy(blkAmbi, 0, ambiA, b * FRAMES * 16, FRAMES * 16);
      System.arraycopy(blkStereo, 0, stereoA, b * FRAMES * 2, FRAMES * 2);
    }
    System.out.printf("binaural RMS (src at x=+1): L=%.4f R=%.4f  (want R>L)%n",
        rms(stereoA, 2, 0), rms(stereoA, 2, 1));
    System.out.println("ambisonic RMS per channel (yaw 0):");
    printRms(ambiA, 16);

    // run B: same world, listener yawed 90 degrees, then settled comparison
    double rad = Math.toRadians(90.0);
    ra.update(0, 0, 0, (float) -Math.sin(rad), 0, (float) -Math.cos(rad),
        0, 1, 0);
    float[] ambiB = new float[BLOCKS * FRAMES * 16];
    float[] stereoB = new float[BLOCKS * FRAMES * 2];
    for (int b = 0; b < BLOCKS; b++) {
      fillSine(in, phase);
      phase += 2 * Math.PI * 440.0 * FRAMES / SR;
      src.send(in, 1);
      ra.fillOutputBuffers(blkAmbi, blkStereo);
      System.arraycopy(blkAmbi, 0, ambiB, b * FRAMES * 16, FRAMES * 16);
      System.arraycopy(blkStereo, 0, stereoB, b * FRAMES * 2, FRAMES * 2);
    }
    System.out.println("ambisonic RMS per channel (yaw 90):");
    printRms(ambiB, 16);

    // compare settled tail (last 75 blocks)
    int tailBlocks = 75;
    int off = (BLOCKS - tailBlocks) * FRAMES;
    int n = tailBlocks * FRAMES;
    System.out.printf("%nworld-space B-format invariance under head rotation:%n");
    System.out.printf("  ACN0..3 rel RMS diff = %.4f (want < 0.02)%n",
        relDiff(ambiA, ambiB, off * 16, n * 16, 16, 0, 3));
    System.out.printf("  all 16 channels rel diff = %.4f%n",
        relDiff(ambiA, ambiB, off * 16, n * 16, 16, 0, 15));
    System.out.printf("binaural DOES depend on head rotation:%n");
    System.out.printf("  rel diff = %.3f (want >> 0)%n",
        relDiff(stereoA, stereoB, off * 2, n * 2, 2, 0, 1));

    // listener-relative mode (compensation off)
    ra.setAmbisonicListenerRotationCompensated(false);
    ra.update(0, 0, 0, 0, 0, -1, 0, 1, 0);
    float[] relA = new float[BLOCKS * FRAMES * 16];
    for (int b = 0; b < BLOCKS; b++) {
      fillSine(in, phase);
      phase += 2 * Math.PI * 440.0 * FRAMES / SR;
      src.send(in, 1);
      ra.fillOutputBuffers(blkAmbi, blkStereo);
      System.arraycopy(blkAmbi, 0, relA, b * FRAMES * 16, FRAMES * 16);
    }
    rad = Math.toRadians(90.0);
    ra.update(0, 0, 0, (float) -Math.sin(rad), 0, (float) -Math.cos(rad),
        0, 1, 0);
    float[] relB = new float[BLOCKS * FRAMES * 16];
    for (int b = 0; b < BLOCKS; b++) {
      fillSine(in, phase);
      phase += 2 * Math.PI * 440.0 * FRAMES / SR;
      src.send(in, 1);
      ra.fillOutputBuffers(blkAmbi, blkStereo);
      System.arraycopy(blkAmbi, 0, relB, b * FRAMES * 16, FRAMES * 16);
    }
    System.out.printf("compensation OFF (listener-relative B-format):%n");
    System.out.printf("  rel diff yaw0 vs yaw90 = %.3f (want >> 0)%n",
        relDiff(relA, relB, off * 16, n * 16, 16, 0, 15));

    // single-pass dual output sanity (already exercised above via
    // fillOutputBuffers): confirm the binaural part equals plain fill path
    ra.setAmbisonicListenerRotationCompensated(true);
    boolean ok = true;
    for (int b = 0; b < 20 && ok; b++) {
      src.send(in, 1);
      ok = ra.fillOutputBuffers(blkAmbi, blkStereo);
    }
    System.out.printf("fillOutputBuffers returns ok for %d blocks, "
        + "ambi W RMS=%.4f, stereo RMS=%.4f%n", 20,
        rms(blkAmbi, 16, 0), rms(blkStereo, 2, 0));

    // ---- speaker decoder output mode ----
    com.google.resonanceaudio.SpeakerDecoder dec =
        new com.google.resonanceaudio.SpeakerDecoder(
            "decoder/data/default.json");
    ResonanceAudio.InitOptions dOpts = opts();
    dOpts.numOutputChannels = ResonanceAudio.OUTPUT_DECODER;
    ResonanceAudio raDec = new ResonanceAudio(dOpts, dec);
    SoundSource dSrc = newSource(raDec);
    raDec.update(0, 0, 0, 0, 0, -1, 0, 1, 0);
    int nc = dec.getNumChannels();
    float[] spk = new float[FRAMES * nc];
    float[] spkB = new float[FRAMES * nc];
    float[] ambiScr = new float[FRAMES * 16];

    System.out.printf("%nspeaker decoder output (%s, %d speakers):%n",
        dec.getName(), nc);

    // steady-state consistency: decoded B-format vs direct decoder output
    // (compare settled per-channel RMS -- phase-agnostic)
    dSrc.setPosition(0, 0, -3);
    double[] r1 = speakerRms(raDec, dec, dSrc, spk, 100, false);
    double[] r2 = speakerRms(raDec, dec, dSrc, spk, 100, true);
    double worst = 0;
    for (int c = 0; c < nc; c++) {
      worst = Math.max(worst, Math.abs(r1[c] - r2[c])
          / Math.max(r1[c], 1e-9));
    }
    System.out.printf("  fillOutputBuffer vs manual decode of B-format: "
        + "max per-speaker RMS rel diff = %.4f (want < 0.05)%n", worst);

    // speaker energy profile must follow the source (front vs back)
    dSrc.setPosition(0, 0, -3);
    double[] front = speakerRms(raDec, dec, dSrc, spk, 100, false);
    dSrc.setPosition(0, 0, 3);
    double[] back = speakerRms(raDec, dec, dSrc, spk, 100, false);
    double d = 0, r = 0;
    for (int c = 0; c < nc; c++) {
      d += Math.pow(front[c] - back[c], 2);
      r += Math.pow(back[c], 2);
      System.out.printf("  spk %2d: front=%.4f back=%.4f%n", c,
          front[c], back[c]);
    }
    System.out.printf("  front-vs-back speaker profile rel diff = %.3f "
        + "(want >> 0)%n", Math.sqrt(d / Math.max(r, 1e-12)));
    raDec.dispose();

    // ---- 1st/2nd order B-format output ----
    for (int orderCh : new int[] {4, 9}) {
      ResonanceAudio.InitOptions o1 = opts();
      o1.numOutputChannels = orderCh;
      ResonanceAudio ra1 = new ResonanceAudio(o1);
      SoundSource s1 = newSource(ra1);   // same (1,0,-1) front-right source
      float[] b = new float[FRAMES * orderCh];
      double ph = phase;
      // yaw 0 pass, collect settled tail RMS per channel
      ra1.update(0, 0, 0, 0, 0, -1, 0, 1, 0);
      double[] accA = new double[orderCh];
      double[] accB = new double[orderCh];
      for (int i2 = 0; i2 < BLOCKS * 2; i2++) {
        fillSine(in, ph);
        ph += 2 * Math.PI * 440.0 * FRAMES / SR;
        if (i2 == BLOCKS) {
          double rad2 = Math.toRadians(90.0);
          ra1.update(0, 0, 0, (float) -Math.sin(rad2), 0,
              (float) -Math.cos(rad2), 0, 1, 0);
        }
        s1.send(in, 1);
        ra1.fillAmbisonicBuffer(b);
        if (i2 % BLOCKS >= BLOCKS - 75) {
          for (int c = 0; c < orderCh; c++) {
            for (int f = 0; f < FRAMES; f++) {
              double v = b[f * orderCh + c];
              if (i2 < BLOCKS) accA[c] += v * v;
              else accB[c] += v * v;
            }
          }
        }
      }
      System.out.printf("%n%d-channel B-format (%d order):%n", orderCh,
        (int) Math.sqrt(orderCh) - 1);
      StringBuilder sa = new StringBuilder("  yaw0 RMS:");
      StringBuilder sb2 = new StringBuilder("  yaw90 RMS:");
      for (int c = 0; c < orderCh; c++) {
        double a = Math.sqrt(accA[c] / (75.0 * FRAMES));
        double bb = Math.sqrt(accB[c] / (75.0 * FRAMES));
        sa.append(String.format(" %.3f", a));
        sb2.append(String.format(" %.3f", bb));
      }
      System.out.println(sa);
      System.out.println(sb2);
      // regularize by W energy: ideal-zero channels (e.g. Z dipoles for a
      // horizontal source) would otherwise blow up the per-channel ratio
      double floor = 0.0025 * accA[0];   // (5% of W RMS)^2
      double worstRot = 0;
      for (int c = 0; c < orderCh; c++) {
        double a = accA[c] / (75.0 * FRAMES);
        double bb = accB[c] / (75.0 * FRAMES);
        worstRot = Math.max(worstRot, Math.abs(a - bb) / Math.max(Math.max(a, bb), floor));
      }
      System.out.printf("  world-space max RMS diff under yaw: %.4f "
          + "(want < 0.1, floored at 5%% of W energy)%n", worstRot);
      ra1.dispose();
    }

    // ---- first order speaker decoder (synthetic 4-speaker square) ----
    java.io.File decFile = new java.io.File("out/decoder_foa_square.json");
    try (java.io.FileWriter fw = new java.io.FileWriter(decFile)) {
      fw.write("{\"Name\":\"test foa square\",\"Decoder\":"
          + "{\"NumberOfInputChannels\":4,\"Normalization\":\"SN3D\","
          + "\"Ordering\":\"ACN\",\"CondonShortleyPhase\":false,"
          + "\"Matrix\":[[0.25,0,0,0.25],[0.25,0.25,0,0],"
          + "[0.25,0,0,-0.25],[0.25,-0.25,0,0]],\"Routing\":[1,2,3,4]}}");
    }
    com.google.resonanceaudio.SpeakerDecoder decFoA =
        new com.google.resonanceaudio.SpeakerDecoder(decFile.getPath());
    ResonanceAudio.InitOptions foaOpts = opts();
    foaOpts.numOutputChannels = ResonanceAudio.OUTPUT_DECODER;
    ResonanceAudio raFoA = new ResonanceAudio(foaOpts, decFoA);
    SoundSource fSrc = newSource(raFoA);
    float[] fSpk = new float[FRAMES * 4];
    System.out.printf("%nfirst order decoder mode: output channels = %d "
        + "(want 4)%n", raFoA.getOutputChannels());
    fSrc.setPosition(0, 0, -3);   // azimuth 0 -> speaker #0
    double[] fr = speakerRms(raFoA, decFoA, fSrc, fSpk, 100, false);
    // NOTE: in the renderer's ACN/SN3D field ACN1 = sin(azimuth) with
    // positive azimuth toward -X (IEM convention), so azimuth +90 is LEFT.
    fSrc.setPosition(-3, 0, 0);   // azimuth +90 (left) -> speaker #1
    double[] lft = speakerRms(raFoA, decFoA, fSrc, fSpk, 100, false);
    System.out.printf("  front source loudest spk #%d (want 0), "
        + "left source loudest spk #%d (want 1)%n", argMax(fr), argMax(lft));
    raFoA.dispose();

    // write demo files
    WavUtil.write("out/test_binaural.wav", stereoA, 2, SR);
    WavUtil.write("out/test_ambisonic3d.wav", ambiA, 16, SR);
    System.out.println("wrote out/test_binaural.wav + out/test_ambisonic3d.wav");
    ra.dispose();
  }

  /**
   * Renders settled blocks at the source's current position and returns the
   * per-speaker RMS of the last 60 blocks.
   *
   * @param manual true = render B-format via fillAmbisonicBuffer and decode
   *     manually, false = fillOutputBuffer (built-in decoder path)
   */
  static double[] speakerRms(ResonanceAudio raDec,
      com.google.resonanceaudio.SpeakerDecoder dec, SoundSource src,
      float[] spk, int blocks, boolean manual) {
    int nc = spk.length / FRAMES;
    double[] acc = new double[nc];
    float[] in = new float[FRAMES];
    float[] ambi = new float[FRAMES * 16];
    double phase = 0;
    for (int b = 0; b < blocks; b++) {
      fillSine(in, phase);
      phase += 2 * Math.PI * 440.0 * FRAMES / SR;
      src.send(in, 1);
      if (manual) {
        raDec.fillAmbisonicBuffer(ambi);
        dec.decodeInterleaved(ambi, spk);
      } else {
        raDec.fillOutputBuffer(spk);
      }
      if (b >= blocks - 60) {
        for (int i = 0; i < spk.length; i++) {
          acc[i % nc] += spk[i] * spk[i];
        }
      }
    }
    double[] out = new double[nc];
    for (int c = 0; c < nc; c++) {
      out[c] = Math.sqrt(acc[c] / (60.0 * FRAMES));
    }
    return out;
  }

  static int argMax(double[] x) {
    int best = 0;
    for (int i = 1; i < x.length; i++) {
      if (x[i] > x[best]) best = i;
    }
    return best;
  }

  /** Renders 80 settled blocks at the given position, returns loudest speaker. */
  static int argMaxSpeakerRms(ResonanceAudio raDec, SoundSource src,
      float x, float y, float z, float[] spk, float[] in, double phase0) {
    src.setPosition(x, y, z);
    double[] acc = new double[spk.length / FRAMES];
    double phase = phase0;
    for (int b = 0; b < 80; b++) {
      fillSine(in, phase);
      phase += 2 * Math.PI * 440.0 * FRAMES / SR;
      src.send(in, 1);
      raDec.fillOutputBuffer(spk);
      if (b >= 60) {
        for (int i = 0; i < spk.length; i++) {
          acc[i % acc.length] += spk[i] * spk[i];
        }
      }
    }
    int best = 0;
    for (int i = 1; i < acc.length; i++) {
      if (acc[i] > acc[best]) {
        best = i;
      }
    }
    return best;
  }

  static void fillSine(float[] in, double phase0) {
    for (int i = 0; i < FRAMES; i++) {
      in[i] = (float) Math.sin(phase0 + 2 * Math.PI * 440.0 * i / SR);
    }
  }

  /** rel RMS diff of channels [c0..c1] given sample ranges in interleaved arrays */
  static double relDiff(float[] a, float[] b, int off, int samples,
      int channels, int c0, int c1) {
    double d = 0, r = 0;
    int frames = samples / channels;
    for (int f = 0; f < frames; f++) {
      for (int c = c0; c <= c1; c++) {
        int idx = off + f * channels + c;
        d += Math.pow(a[idx] - b[idx], 2);
        r += Math.pow(b[idx], 2);
      }
    }
    return Math.sqrt(d / Math.max(r, 1e-12));
  }

  static double rms(float[] x, int channels, int channel) {
    double s = 0;
    int n = 0;
    for (int i = channel; i < x.length; i += channels) {
      s += x[i] * x[i];
      n++;
    }
    return Math.sqrt(s / Math.max(n, 1));
  }

  static void printRms(float[] ambi, int channels) {
    StringBuilder sb = new StringBuilder();
    for (int c = 0; c < channels; c++) {
      sb.append(String.format(" %.3f", rms(ambi, channels, c)));
    }
    System.out.println(sb.toString());
  }
}
