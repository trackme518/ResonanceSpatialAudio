/**
 * Resonance ASIO (Focusrite Scarlett)
 *
 * Binaural spatial audio played through a Focusrite Scarlett USB interface
 * using the Sound library's native ASIO driver support (JAsioHost), bypassing
 * the Windows audio stack for the lowest possible latency.
 *
 * ASIO has to be enabled with MultiChannel.useJASIO() before any other Sound
 * library call. The sketch then prints every registered ASIO driver and looks
 * for "Focusrite USB ASIO", which is the driver installed by Focusrite for the
 * Scarlett USB interfaces. If that driver is not found it falls back to any
 * other Focusrite ASIO driver, then to the first available ASIO driver, and
 * finally to the system default audio device.
 *
 * The 2-channel binaural HRTF output is sent to outputs 1 and 2 of the
 * interface, so connect headphones or monitors there. This is a Windows-only
 * feature; on other systems the sketch still runs on the default audio driver.
 *
 * Controls:
 *   mouse X  - rotate the listener's head (yaw, head-tracked binaural)
 *   mouse Y  - move the height of the orbiting source
 *   click    - toggle playback
 *   A        - toggle reverb room model
 *   R        - restart playback
 *   D        - print the ASIO driver list again
 *
 * Requires: ResonanceAudio library + Sound library with ASIO support
 * (2.5.0-jasio or newer) + Focusrite USB ASIO driver + headphones.
 */

import processing.sound.*;
import com.google.resonanceaudio.*;
import com.synthbot.jasiohost.AsioDriver;
import java.util.List;

// Exact name of the ASIO driver installed by Focusrite for Scarlett USB
// interfaces. Other Focusrite interfaces use "Focusrite Thunderbolt ASIO".
static final String ASIO_DRIVER_NAME = "Focusrite USB ASIO";

Sound sound;
ResonanceAudio ra;
ResonanceAudioEngine engine;
ResonanceSoundFile sourceA;  // orbiting

boolean usingASIO = false;
boolean roomOn = false;
String deviceLabel = "system default";

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

  int outputs = MultiChannel.availableChannels();
  println("Using '" + deviceLabel + "' with " + outputs + " output channels");
  if (outputs < ResonanceAudio.OUTPUT_BINAURAL) {
    die("Binaural output needs 2 channels, but '" + deviceLabel + "' only provides "
      + outputs + ".");
    return;
  }

  // --- binaural renderer ---------------------------------------------------
  // For 16-channel third order Ambisonics B-format instead, set
  // options.numOutputChannels = ResonanceAudio.OUTPUT_AMBISONIC_3RD_ORDER;
  // (the Focusrite USB ASIO driver exposes enough outputs for this), but note
  // that the result then has to be decoded by an external ambisonic decoder.
  ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
  options.numFramesPerBuffer = 512;
  options.sampleRate = Sound.sampleRate();
  options.numOutputChannels = ResonanceAudio.OUTPUT_BINAURAL;
  ra = new ResonanceAudio(options);

  // Connects the rendered output to the first two output channels of the
  // currently selected device.
  engine = new ResonanceAudioEngine(this, ra, 4);

  sourceA = new ResonanceSoundFile(this, "loop_mono.wav", engine,
    RenderingMode.BINAURAL_HIGH_QUALITY);
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

  // Listener: fixed at the origin, head yaw follows the mouse (head-tracked
  // binaural rendering -- try it with headphones!).
  float yaw = map(mouseX, 0, width, -PI, PI);
  ra.update(0, 0, 0,                    // listener position
    -sin(yaw), 0, -cos(yaw),           // forward
    0, 1, 0);                          // up

  // The single source orbits around the listener, height follows mouseY.
  float ay = map(mouseY, 0, height, 2, -2);
  sourceA.getSoundSource().setPosition(3 * sin(t), ay, 3 * cos(t));

  drawTopDown(yaw);

  fill(40);
  textSize(13);
  text("ASIO: " + deviceLabel + "   |   mouse X = turn head   |   click = play/pause   |   A = room "
    + (roomOn ? "ON" : "off") + "   |   R = restart   |   D = list drivers",
    12, height - 16);
}

// simple top-down visualization
void drawTopDown(float yaw) {
  pushMatrix();
  translate(width / 2, height / 2);
  float s = 55;
  stroke(200);
  noFill();
  for (int r = 1; r <= 4; r++) ellipse(0, 0, r * 2 * s, r * 2 * s);
  // listener
  pushMatrix();
  rotate(-yaw);
  fill(60);
  noStroke();
  triangle(0, -14, -9, 8, 9, 8);
  popMatrix();
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
  if (key == 'a' || key == 'A') {
    roomOn = !roomOn;
    if (roomOn) {
      ra.setRoomModel(Surface.BRICK_PAINTED,
        new float[] { 0, 0, 0 }, new float[] { 0, 0, 0, 1 },
        new float[] { 12, 4, 12 }, 0.9, 0.9, 1.0, 0.0);
    } else {
      ra.setRoomEffectsEnabled(false);
    }
  }
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
