/**
 * Sound ASIO Speakers (Multi-channel Ambisonics + optional Subwoofer)
 *
 * World-space Ambisonics decoded to a loudspeaker array (with or without a
 * discrete bass-managed subwoofer) and played through an ASIO interface using
 * the Sound library's native ASIO driver support (JAsioHost) at 48 kHz.
 *
 * Included decoder presets in data/:
 *   - decoder_museum_of_prague_12_1.json : 3rd-order AllRAD, 12 speakers
 *       (ch 1-12) + discrete subwoofer (ch 41) -> needs >= 41 device outputs
 *       (e.g. Dante Virtual Soundcard in 64x64 ASIO mode).
 *   - decoder_4square_sub.json           : 1st-order square, 4 speakers
 *       (ch 1-4) + discrete subwoofer (ch 5)   -> needs >= 5 device outputs.
 *   - decoder_4square.json               : 1st-order square, 4 speakers
 *       (ch 1-4), NO subwoofer                 -> needs >= 4 device outputs.
 *
 * Speaker layouts WITH or WITHOUT a subwoofer:
 *   1) In the decoder JSON ("Decoder" object):
 *      - Without subwoofer: omit "SubwooferChannel" (or set it to -1). All
 *        loudspeakers receive full-band decoded audio.
 *      - With discrete subwoofer: set "SubwooferChannel" to a 1-based physical
 *        output channel that does NOT collide with any channel in "Routing"
 *        (e.g. "Routing": [1, 2, 3, 4], "SubwooferChannel": 5). Optional
 *        fields "SubwooferCrossoverHz" (default 80.0) and "SubwooferGainDb"
 *        (default 0.0) set the default 4th-order Linkwitz-Riley (LR4)
 *        crossover and gain trim.
 *   2) Programmatically in Processing code:
 *      - Add or override a subwoofer on any decoder JSON without editing the
 *        file:
 *          decoder = new SpeakerDecoder(dataPath("decoder_4square.json"), 5);
 *          // or: decoder = decoder.withSubwooferChannel(5);
 *          // pass -1 to disable a JSON's subwoofer channel.
 *      - Adjust subwoofer parameters at runtime:
 *          ra.setSubwooferCrossoverFrequency(80);   // Hz (LR4)
 *          ra.setSubwooferGainDb(0);                // dB trim
 *          ra.setSubwooferPolarityInverted(false);  // 180-deg phase flip
 *
 * ASIO & 48 kHz setup:
 *   MultiChannel.useJASIO() must be the very first Sound library call,
 *   followed by Sound.sampleRate(48000) BEFORE constructing new Sound(this).
 *   This is required for interfaces locked to 48 kHz such as Dante Virtual
 *   Soundcard. This is a Windows-only feature; on other systems the sketch
 *   falls back to the system default audio device.
 *
 * Controls:
 *   mouse Y  - move the height of the orbiting source
 *   click    - toggle playback
 *   R        - restart playback
 *   D        - print the ASIO driver list again
 *
 * Requires: ResonanceAudio library + Sound library with ASIO support
 * (2.5.0-jasio or newer) + an ASIO driver with enough output channels for the
 * selected decoder.
 */

import processing.sound.*;
import com.google.resonanceaudio.*;
import com.synthbot.jasiohost.AsioDriver;
import java.util.List;

// Exact ASIO driver name to open (press 'D' while running to print all
// registered ASIO drivers on this machine).
// Active device:
static final String ASIO_DRIVER_NAME = "Dante Virtual Soundcard (x64)";
// Other ASIO devices tested for reference (uncomment one to switch):
//static final String ASIO_DRIVER_NAME = "Focusrite USB ASIO";
//static final String ASIO_DRIVER_NAME = "Focusrite Thunderbolt ASIO";
//static final String ASIO_DRIVER_NAME = "US-16x08 ASIO";
//static final String ASIO_DRIVER_NAME = "VB-Matrix VASIO-64A";
//static final String ASIO_DRIVER_NAME = "ASIO4ALL v2";

// Decoder JSON to load from data/ (pick a layout with or without subwoofer):
static final String DECODER_FILE = "decoder_museum_of_prague_12_1.json"; // 12.1 (ch 1-12 + sub ch 41)
//static final String DECODER_FILE = "decoder_4square_sub.json";         // 4.1  (ch 1-4  + sub ch 5)
//static final String DECODER_FILE = "decoder_4square.json";             // 4.0  (ch 1-4, no subwoofer)

Sound sound;
SpeakerDecoder decoder;
ResonanceAudio ra;
ResonanceAudioEngine engine;
ResonanceSoundFile sourceA;  // orbiting

boolean usingASIO = false;
String deviceLabel = "system default";
int numSpeakers = 12;
int numOutputChannels = 41;

void setup() {
  size(640, 480);
  frameRate(60);

  // Has to be the very first Sound library call, before creating the Sound
  // object or any other audio objects.
  usingASIO = MultiChannel.useJASIO();

  // Switch the JAsio synthesis engine to 48 kHz (required for Dante Virtual
  // Soundcard and standard 48 kHz ASIO interfaces) BEFORE new Sound(this).
  Sound.sampleRate(48000);

  sound = new Sound(this);

  if (usingASIO) {
    String driver = selectAsioDriver();
    if (driver != null) {
      try {
        Sound.outputDevice(driver);
        deviceLabel = driver;
        println("Output device set to ASIO driver: " + driver);
      } catch (RuntimeException e) {
        println("Could not open ASIO driver '" + driver + "': " + e.getMessage());
      }
    }
  } else {
    println("ASIO is not available, falling back to the default audio drivers.");
  }

  // If no ASIO device could be opened, use the system default output.
  if (deviceLabel.equals("system default")) {
    try {
      Sound.defaultOutputDevice();
      String[] names = Sound.deviceNames();
      int def = Sound.getAudioDeviceManager().getDefaultOutputDeviceID();
      if (def >= 0 && def < names.length) {
        deviceLabel = names[def];
      }
    } catch (RuntimeException e) {
      die("Could not select an audio output device: " + e.getMessage());
      return;
    }
  }

  // --- load the precomputed decoding matrix -------------------------------
  try {
    decoder = new SpeakerDecoder(dataPath(DECODER_FILE));
    // Optional: override the subwoofer channel programmatically in code
    // (pass a free 1-based channel number to enable, or -1 to disable):
    // decoder = decoder.withSubwooferChannel(5);
  } catch (Exception e) {
    die("Could not load decoder JSON: " + e);
    return;
  }
  numSpeakers = decoder.getNumChannels();
  numOutputChannels = decoder.getNumOutputChannels();
  println("Decoder '" + decoder.getName() + "' -> " + numSpeakers + " loudspeakers"
    + (decoder.hasSubwoofer()
        ? " + subwoofer on channel " + decoder.getSubwooferChannel()
          + " (" + decoder.getSubwooferCrossoverFrequency() + " Hz LR4)"
        : " (no subwoofer)")
    + " [" + numOutputChannels + " output channels]");

  int outputs = MultiChannel.availableChannels();
  println("Using '" + deviceLabel + "' with " + outputs + " output channels");
  if (outputs < numOutputChannels) {
    die("The decoder needs " + numOutputChannels + " output channels, but '"
      + deviceLabel + "' only provides " + outputs + ".");
    return;
  }

  // --- B-format decoded to loudspeakers (+ discrete subwoofer if configured)
  ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
  options.numFramesPerBuffer = 512;
  options.sampleRate = Sound.sampleRate();
  options.numOutputChannels = ResonanceAudio.OUTPUT_DECODER;
  ra = new ResonanceAudio(options, decoder);

  // Optional runtime subwoofer tuning (only active when decoder.hasSubwoofer()):
  // ra.setSubwooferCrossoverFrequency(80);
  // ra.setSubwooferGainDb(0);
  // ra.setSubwooferPolarityInverted(false);

  // Connects all decoded outputs (1..numOutputChannels) to the selected device.
  engine = new ResonanceAudioEngine(this, ra, 4);

  sourceA = new ResonanceSoundFile(this, "loop_mono.wav", engine);
  sourceA.amp(0.8);
  sourceA.getSoundSource().setDistanceRolloff(DistanceRolloffModel.LOGARITHMIC, 0.5, 25);
  sourceA.loop();

  ra.setMasterVolume(0.9);

  // Print the actual driver, sample rate and latency in use.
  Sound.status();
}

void draw() {
  background(245);

  float t = millis() / 1000.0;

  // Listener fixed at the center, looking down -Z (no head rotation).
  ra.update(0, 0, 0, 0, 0, -1, 0, 1, 0);

  // The single source orbits around the listener, height follows mouseY.
  float ay = map(mouseY, 0, height, 2, -2);
  sourceA.getSoundSource().setPosition(3 * sin(t), ay, 3 * cos(t));

  drawTopDown();

  fill(40);
  textSize(13);
  String layoutLabel = decoder.hasSubwoofer()
    ? (numSpeakers + ".1 decode (sub ch " + decoder.getSubwooferChannel() + ")")
    : (numSpeakers + "-speaker decode");
  text(layoutLabel + " on ASIO: " + deviceLabel
    + "   |   click = play/pause   |   R = restart   |   D = list drivers",
    12, height - 16);
}

// top-down visualization incl. loudspeaker positions and optional subwoofer
void drawTopDown() {
  pushMatrix();
  translate(width / 2, height / 2);
  float s = 55;
  stroke(200);
  noFill();
  for (int r = 1; r <= 4; r++) ellipse(0, 0, r * 2 * s, r * 2 * s);

  // listener (fixed, looking "up" = -Z)
  fill(60);
  noStroke();
  triangle(0, -14, -9, 8, 9, 8);

  if (decoder.hasSubwoofer()) {
    fill(140, 60, 60);
    textAlign(CENTER, CENTER);
    textSize(10);
    text("SUB ch " + decoder.getSubwooferChannel(), 0, 22);
  }

  // loudspeakers (azimuth from front, positive toward LEFT)
  float[] az = decoder.getSpeakerAzimuths();
  int[] routing = decoder.getEffectiveRouting();
  float rs = 4 * s;
  for (int i = 0; i < az.length; i++) {
    float a = radians(az[i]);
    float sx = -sin(a) * rs;
    float sy = -cos(a) * rs;
    fill(90);
    rect(sx - 7, sy - 5, 14, 10, 2);
    fill(160);
    textAlign(CENTER, CENTER);
    textSize(9);
    int ch = i < routing.length ? routing[i] : (i + 1);
    text(str(ch), sx, sy + 16);
  }

  // orbiting source
  float t = millis() / 1000.0;
  noStroke();
  fill(#2B6CB0);
  float ax = 3 * sin(t) * s, ay = 3 * cos(t) * s;
  ellipse(ax, ay, 22, 22);

  popMatrix();
}

void mousePressed() {
  if (sourceA.isPlaying()) {
    sourceA.stop();
  } else {
    sourceA.loop();
  }
}

void keyPressed() {
  if (key == 'r' || key == 'R') {
    sourceA.stop();
    sourceA.loop();
  }
  if (key == 'd' || key == 'D') {
    listASIODevices();
  }
}

// Prints all ASIO drivers and returns the configured ASIO_DRIVER_NAME, or
// the best fallback. Returns null if ASIO is not available or no driver exists.
String selectAsioDriver() {
  List<String> drivers = AsioDriver.getDriverNames();
  println("\nAvailable ASIO drivers:");
  for (String name : drivers) {
    println("  " + name);
  }
  if (drivers.isEmpty()) {
    println("No ASIO drivers found.");
    return null;
  }

  int index = indexOfDriver(drivers, ASIO_DRIVER_NAME);
  if (index < 0) {
    index = indexOfDriverContaining(drivers, "Dante");
  }
  if (index < 0) {
    index = indexOfDriverContaining(drivers, "Focusrite");
  }
  if (index < 0) {
    index = 0;
    println("No matching ASIO driver found, falling back to '" + drivers.get(index) + "'");
  }
  return drivers.get(index);
}

void listASIODevices() {
  if (!usingASIO) {
    println("ASIO is not enabled, using the default audio drivers.");
    return;
  }
  println("\nAvailable ASIO drivers:");
  for (String name : AsioDriver.getDriverNames()) {
    println("  " + name);
  }
}

int indexOfDriver(List<String> drivers, String name) {
  for (int i = 0; i < drivers.size(); i++) {
    if (drivers.get(i).equalsIgnoreCase(name)) {
      return i;
    }
  }
  return -1;
}

int indexOfDriverContaining(List<String> drivers, String namePart) {
  for (int i = 0; i < drivers.size(); i++) {
    if (drivers.get(i).toLowerCase().contains(namePart.toLowerCase())) {
      return i;
    }
  }
  return -1;
}
