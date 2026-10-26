import processing.core.PApplet;
import processing.sound.*;
import com.synthbot.jasiohost.AsioDriver;
import com.google.resonanceaudio.*;
import java.util.List;

public class AsioLiveTest {
  public static void main(String[] a) throws Exception {
    PApplet p = new PApplet();

    // must be the first Sound library call
    boolean asio = MultiChannel.useJASIO();
    System.out.println("useJASIO=" + asio);

    String driver = null;
    if (asio) {
      List<String> names = AsioDriver.getDriverNames();
      System.out.println("registered ASIO drivers: " + names);
      for (String n : names) {
        if (a.length > 0 ? n.contains(a[0]) : n.contains("US-16x08")) driver = n;
      }
      if (driver == null && !names.isEmpty()) driver = names.get(0);
      if (driver == null) { System.out.println("NO US-16x08 DRIVER"); System.exit(1); }
      Sound.outputDevice(driver);
    }

    Sound sound = new Sound(p);
    int ch = MultiChannel.availableChannels();
    System.out.println("driver=" + driver + " rate=" + Sound.sampleRate()
        + " availableChannels=" + ch);
    if (ch < 8) { System.out.println("TOO FEW CHANNELS: " + ch); System.exit(1); }

    SpeakerDecoder dec = new SpeakerDecoder(
        "ResonanceAudio/examples/ResonanceDecoderSoundLib/data/decoder_8ring.json");
    ResonanceAudio.InitOptions o = new ResonanceAudio.InitOptions();
    o.numFramesPerBuffer = 512;
    o.sampleRate = Sound.sampleRate();
    o.numOutputChannels = ResonanceAudio.OUTPUT_DECODER;
    ResonanceAudio ra = new ResonanceAudio(o, dec);
    ResonanceAudioEngine engine = new ResonanceAudioEngine(p, ra, 4);
    System.out.println("engine output channels = "
        + engine.getNumOutputChannels());

    // run 5 s; Watch stderr for "Wait timed out" / ASIO errors meanwhile.
    java.lang.reflect.Field h =
        ResonanceAudio.class.getDeclaredField("handle");
    h.setAccessible(true);
    long validHandle = h.getLong(ra);
    System.out.println("handle = 0x" + Long.toHexString(validHandle));
    long corruptAt = -1;
    for (int i = 0; i < 50; i++) {
      Thread.sleep(100);
      long cur = h.getLong(ra);
      if (cur != validHandle && corruptAt < 0) {
        corruptAt = i;
        System.out.println("!!! handle CLOBBERED during run: 0x"
            + Long.toHexString(cur) + " at t=" + (i * 100) + "ms");
      }
    }
    System.out.println("handle still intact after 5 s: " + (corruptAt < 0));
    // Watch the handle field closely around the dispose() sequence.
    Thread watcher = new Thread(() -> {
      long last = validHandle;
      while (!Thread.currentThread().isInterrupted()) {
        long cur = 0;
        try {
          cur = h.getLong(ra);
        } catch (Throwable t) {
          return;
        }
        if (cur != last) {
          System.out.println("!!! handle CHANGED: 0x" + Long.toHexString(last)
              + " -> 0x" + Long.toHexString(cur) + " (t=" + System.nanoTime()
              + ")");
          last = cur;
        }
        try {
          Thread.sleep(2);
        } catch (InterruptedException e) {
          return;
        }
      }
    });
    watcher.setDaemon(true);
    watcher.start();
    Thread.sleep(20);
    long t0 = System.nanoTime();
    // Standard ASIO host teardown: stop the stream/callbacks FIRST ...
    System.out.println("t0=" + t0 + " stopping synth...");
    Sound.getSynthesisEngine().stop();
    Thread.sleep(50);
    // ... and only then free the DSP graph.
    System.out.println("disposing...");
    engine.dispose();
    System.out.println("disposed");
    Thread.sleep(100);
    // stop the synth thread + ASIO stream and join the callback thread
    // before the JVM goes away
    Sound.getSynthesisEngine().stop();
    System.out.println("ASIO LIVE TEST FINISHED OK");
    System.exit(0);
  }
}


