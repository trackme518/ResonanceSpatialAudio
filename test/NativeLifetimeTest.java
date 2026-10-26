import com.google.resonanceaudio.*;

/** Bisection harness for the nativeDestroy crash, no JSyn / Sound library. */
public class NativeLifetimeTest {
  public static void main(String[] a) throws Exception {
    String mode = a.length > 0 ? a[0] : "withSource";
    ResonanceAudio.InitOptions o = new ResonanceAudio.InitOptions();
    o.numFramesPerBuffer = 512;
    o.sampleRate = 48000;
    o.numOutputChannels = ResonanceAudio.OUTPUT_BINAURAL;
    ResonanceAudio ra = new ResonanceAudio(o);
    SoundSource silent = null;
    float[] staticBuf = new float[512];
    if (!mode.equals("fillOnly")) {
      silent = ra.addSoundObjectSource(
          RenderingMode.BINAURAL_HIGH_QUALITY);
    }
    float[] out = new float[512 * 2];
    ra.update(0, 0, 0, 0, 0, -1, 0, 1, 0);
    long end = System.currentTimeMillis() + 5000;
    long blocks = 0;
    while (System.currentTimeMillis() < end) {
      if (silent != null) {
        silent.send(mode.equals("withSource") ? new float[512] : staticBuf, 1);
      }
      ra.fillOutputBuffer(out);
      blocks++;
      Thread.sleep(0, 200000); // 0.2 ms pause
    }
    System.out.println("blocks=" + blocks + ", disposing...");
    ra.dispose();
    System.out.println("OK (" + mode + ")");
    System.exit(0);
  }
}
