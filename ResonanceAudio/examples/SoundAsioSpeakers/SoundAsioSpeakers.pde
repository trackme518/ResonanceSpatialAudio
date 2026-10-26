/**
 * Sound ASIO Speakers (Focusrite Scarlett)
 *
 * First-order Ambisonics decoded to four loudspeakers in a square layout and
 * played through a Focusrite Scarlett USB interface using the Sound library's
 * native ASIO driver support (JAsioHost).
 *
 * The spatializer renders world-space first-order Ambisonics B-format
 * (ACN/SN3D), and the precomputed matrix in data/decoder_4square.json decodes
 * it to four loudspeakers. Connect the interface outputs as follows:
 *
 *   output 1 -> front left    (azimuth +45)
 *   output 2 -> front right   (azimuth -45)
 *   output 3 -> rear right    (azimuth -135)
 *   output 4 -> rear left     (azimuth +135)
 *
 * ASIO has to be enabled with MultiChannel.useJASIO() before any other Sound
 * library call. The sketch prints every registered ASIO driver and looks for
 * "Focusrite USB ASIO", falling back to another Focusrite driver, then the
 * first ASIO driver, and finally the system default audio device. This is a
 * Windows-only feature; on other systems the sketch still runs on the default
 * driver.
 *
 * Controls:
 *   mouse Y  - move the height of the orbiting source
 *   click    - toggle playback
 *   R        - restart playback
 *   D        - print the ASIO driver list again
 *
 * Requires: ResonanceAudio library + Sound library with ASIO support
 * (2.5.0-jasio or newer) + Focusrite USB ASIO driver + 4 speakers.
 */

import processing.sound.*;
import com.google.resonanceaudio.*;
import com.synthbot.jasiohost.AsioDriver;
import java.util.List;

// Exact name of the ASIO driver installed by Focusrite for Scarlett USB
// interfaces. Other Focusrite interfaces use "Focusrite Thunderbolt ASIO".
static final String ASIO_DRIVER_NAME = "Focusrite USB ASIO";

// First-order Ambisonics decoder for four speakers in a square.
static final String DECODER_FILE = "decoder_4square.json";

Sound sound;
SpeakerDecoder decoder;
ResonanceAudio ra;
ResonanceAudioEngine engine;
ResonanceSoundFile sourceA;  // orbiting

boolean usingASIO = false;
String deviceLabel = "system default";
int numSpeakers = 4;

void setup() {
  size(640, 480);
  frameRate(60);

  // Has to be the very first Sound library call, before creating the Sound
  // object or any other audio objects.
  usingASIO = MultiChannel.useJASIO();
  sound = new Sound(this);

  if (usingASIO) {
    String driver = selectFocusriteDriver();
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
  } catch (Exception e) {
    die("Could not load decoder JSON: " + e);
    return;
  }
  numSpeakers = decoder.getNumChannels();
  println("Decoder '" + decoder.getName() + "' -> " + numSpeakers + " loudspeakers");

  int outputs = MultiChannel.availableChannels();
  println("Using '" + deviceLabel + "' with " + outputs + " output channels");
  if (outputs < numSpeakers) {
    die("The square decoder needs " + numSpeakers + " output channels, but '"
      + deviceLabel + "' only provides " + outputs + ".");
    return;
  }

  // --- first-order B-format decoded to the four speakers ------------------
  ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
  options.numFramesPerBuffer = 512;
  options.sampleRate = Sound.sampleRate();
  options.numOutputChannels = ResonanceAudio.OUTPUT_DECODER;
  ra = new ResonanceAudio(options, decoder);

  // Connects the decoded output to outputs 1-4 of the selected device.
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
  text("4-speaker square decode on ASIO: " + deviceLabel
    + "   |   click = play/pause   |   R = restart   |   D = list drivers",
    12, height - 16);
}

// top-down visualization incl. the four loudspeaker positions
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

  // loudspeakers (azimuth from front, positive toward LEFT; device channel =
  // row + 1)
  float[] az = decoder.getSpeakerAzimuths();
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
    text(str(i + 1), sx, sy + 22);
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

// Prints all ASIO drivers and returns the Focusrite USB ASIO driver name, or
// the best fallback. Returns null if ASIO is not available or no driver exists.
String selectFocusriteDriver() {
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
    index = indexOfDriverContaining(drivers, "Focusrite");
  }
  if (index < 0) {
    index = 0;
    println("No Focusrite ASIO driver found, falling back to '" + drivers.get(index) + "'");
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
