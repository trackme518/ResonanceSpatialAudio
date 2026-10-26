import com.google.resonanceaudio.*;

public class Ring8Test {
  static final int FRAMES = 512;
  static final int SR = 48000;
  static final int BLOCKS = 150;

  static SoundSource newSource(ResonanceAudio ra) {
    SoundSource s = ra.addSoundObjectSource(
        RenderingMode.BINAURAL_HIGH_QUALITY);
    s.setDistanceRolloff(DistanceRolloffModel.NONE, 0, 0);
    s.setDistanceAttenuation(1f);
    return s;
  }

  static void fillSine(float[] in, double ph) {
    for (int i = 0; i < in.length; i++) {
      in[i] = 0.25f * (float) Math.sin(ph + i * 2 * Math.PI * 440.0 / SR);
    }
  }

  static double[] rmsAt(ResonanceAudio raDec, SoundSource src, float x,
      float z, double ph0) {
    src.setPosition(x, 0, z);
    int nc = raDec.getOutputChannels();
    float[] spk = new float[FRAMES * nc];
    float[] in = new float[FRAMES];
    double[] acc = new double[nc];
    double ph = ph0;
    for (int b = 0; b < BLOCKS; b++) {
      fillSine(in, ph);
      ph += 2 * Math.PI * 440.0 * FRAMES / SR;
      src.send(in, 1);
      raDec.fillOutputBuffer(spk);
      if (b >= BLOCKS - 90) {
        for (int i = 0; i < spk.length; i++) acc[i % nc] += spk[i] * spk[i];
      }
    }
    double[] out = new double[nc];
    for (int c = 0; c < nc; c++) out[c] = Math.sqrt(acc[c] / (90.0 * FRAMES));
    return out;
  }

  public static void main(String[] a) throws Exception {
    SpeakerDecoder dec = new SpeakerDecoder(
        "ResonanceAudio/examples/ResonanceDecoderSoundLib/data/decoder_8ring.json");
    ResonanceAudio.InitOptions o = new ResonanceAudio.InitOptions();
    o.numFramesPerBuffer = FRAMES;
    o.sampleRate = SR;
    o.numOutputChannels = ResonanceAudio.OUTPUT_DECODER;
    ResonanceAudio raDec = new ResonanceAudio(o, dec);
    SoundSource src = newSource(raDec);
    raDec.update(0, 0, 0, 0, 0, -1, 0, 1, 0);
    System.out.println("channels=" + raDec.getOutputChannels());
    String[] names = {"front(0)", "left(+90)", "back(180)", "right(-90)"};
    float[][] pos = {{0, -3}, {-3, 0}, {0, 3}, {3, 0}};
    for (int p = 0; p < pos.length; p++) {
      double[] r = rmsAt(raDec, src, pos[p][0], pos[p][1], p * 100.0);
      double max = 0;
      for (double v : r) max = Math.max(max, v);
      System.out.print(names[p] + ":");
      for (double v : r) System.out.printf(" %.4f(%+4.1fdB)", v,
          20 * Math.log10(Math.max(v, 1e-9) / max));
      System.out.println();
    }
    raDec.dispose();
  }
}
