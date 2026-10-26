import com.google.resonanceaudio.WavUtil;

/** Generates the mono demo loop used by the example sketches. */
public class GenWav {
  public static void main(String[] args) throws Exception {
    int sr = 48000;
    double beat = 0.4;
    int beats = 16;
    int total = (int) (sr * beat * beats);
    float[] out = new float[total];
    double[] scale = {220.0, 261.63, 293.66, 329.63, 392.0, 440.0};
    int[] pattern = {0, 2, 4, 2, 5, 4, 2, 1, 0, 2, 4, 5, 4, 2, 1, 0};
    for (int b = 0; b < beats; b++) {
      double f = scale[pattern[b % pattern.length]];
      int start = (int) (b * beat * sr);
      int len = (int) (beat * sr * 1.4);
      for (int i = 0; i < len && start + i < total; i++) {
        double t = i / (double) sr;
        double env = Math.exp(-4.5 * t);
        double v = Math.sin(2 * Math.PI * f * t)
            + 0.5 * Math.sin(2 * Math.PI * 2 * f * t) * Math.exp(-8 * t)
            + 0.25 * Math.sin(2 * Math.PI * 3 * f * t) * Math.exp(-12 * t);
        out[start + i] += (float) (0.45 * env * v);
      }
    }
    for (String path : args) {
      WavUtil.write(path, out, 1, sr);
      System.out.println("wrote " + path);
    }
  }
}
