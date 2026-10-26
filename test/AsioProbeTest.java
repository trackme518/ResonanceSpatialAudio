import com.synthbot.jasiohost.AsioDriver;
import com.synthbot.jasiohost.AsioChannel;

public class AsioProbeTest {
  public static void main(String[] a) throws Exception {
    System.out.println("driver names: " + AsioDriver.getDriverNames());
    try {
      AsioDriver d = AsioDriver.getDriver(a.length > 0 ? a[0] : "US-16x08 ASIO");
      System.out.println("opened: " + d.getName());
      System.out.println("sampleRate=" + d.getSampleRate()
          + " can44100=" + d.canSampleRate(44100)
          + " bufferPref=" + d.getBufferPreferredSize());
      System.out.println("channels in/out = " + d.getNumChannelsInput()
          + "/" + d.getNumChannelsOutput());
      int n = d.getNumChannelsOutput();
      for (int i = 0; i < n; i++) {
        AsioChannel c = d.getChannelOutput(i);
        System.out.println("  out " + i + " -> " + c);
      }
      d.shutdownAndUnloadDriver();
      System.out.println("PROBE OK");
    } catch (Throwable t) {
      System.out.println("PROBE FAILED: " + t);
      t.printStackTrace();
    }
    System.exit(0);
  }
}


