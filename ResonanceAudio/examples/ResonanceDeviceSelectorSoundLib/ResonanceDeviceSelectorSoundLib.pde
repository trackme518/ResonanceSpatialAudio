/**
 * Resonance Device Selector (Sound library)
 *
 * Shows how to choose which audio device the spatial audio is played back
 * on. Sound.deviceNames() lists every available device, and
 * Sound.outputDevice("name") selects one by its exact name (case-
 * insensitive) before any audio objects are created.
 *
 * The ResonanceAudioEngine wires its output channels straight onto the
 * output channels of the current audio device, so the device must be
 * chosen BEFORE the engine is constructed. Binaural output needs 2
 * channels; multi-channel Ambisonics (9 or 16) or loudspeaker-decoder
 * output needs an interface with enough physical outputs (e.g. an ASIO
 * driver). To let the Sound library search for a capable device
 * automatically instead of naming it, use:
 *
 *   MultiChannel.autoSelectDevice(16);   // first device with >= 16 outputs
 *
 * Controls:
 *   click - toggle playback
 *   D     - print the device list to the console
 *
 * Requires: ResonanceAudio library + Sound library (headphones recommended)
 */

import processing.sound.*;
import com.google.resonanceaudio.*;

// Exact device name as shown in the console listing (case-insensitive).
// Leave empty to stay on the system default device.
static final String OUTPUT_DEVICE = "";

Sound sound;
ResonanceAudio ra;
ResonanceAudioEngine engine;
ResonanceSoundFile source;

String deviceLabel = "system default";

void setup() {
  size(640, 480);
  frameRate(60);

  // Initialize the Processing Sound library.
  sound = new Sound(this);

  // Print every device with its number of outputs.
  listDevices();

  // ---- choose the output device by name ---------------------------------
  // Must happen BEFORE creating the engine or any sound files.
  String[] names = Sound.deviceNames();
  int def = Sound.getAudioDeviceManager().getDefaultOutputDeviceID();
  if (def >= 0 && def < names.length) deviceLabel = names[def];
  if (OUTPUT_DEVICE.length() > 0) {
    try {
      Sound.outputDevice(OUTPUT_DEVICE);   // switches the audio engine
      deviceLabel = OUTPUT_DEVICE;
      println("Output device set to: " + OUTPUT_DEVICE);
    } catch (RuntimeException e) {
      println(e.getMessage() + "  (staying on the default device)");
    }
  }

  // ---- Resonance Audio with binaural output ------------------------------
  ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
  options.numFramesPerBuffer = 512;
  options.sampleRate = Sound.sampleRate();
  options.numOutputChannels = ResonanceAudio.OUTPUT_BINAURAL;
  ra = new ResonanceAudio(options);

  // Binds its 2 output channels to the CURRENT device's outputs.
  engine = new ResonanceAudioEngine(this, ra, 4);

  source = new ResonanceSoundFile(this, "loop_mono.wav", engine,
    RenderingMode.BINAURAL_HIGH_QUALITY);
  source.amp(0.8);
  source.loop();
}

void draw() {
  background(245);

  float t = millis() / 1000.0;

  // Listener head yaw follows the mouse, source orbits around it.
  float yaw = map(mouseX, 0, width, -PI, PI);
  ra.update(0, 0, 0,
    -sin(yaw), 0, -cos(yaw),
    0, 1, 0);
  source.getSoundSource().setPosition(3 * sin(t), 0, 3 * cos(t));

  pushMatrix();
  translate(width / 2, height / 2);
  float s = 55;
  stroke(200);
  noFill();
  for (int r = 1; r <= 4; r++) ellipse(0, 0, r * 2 * s, r * 2 * s);
  pushMatrix();
  rotate(-yaw);
  fill(60);
  noStroke();
  triangle(0, -14, -9, 8, 9, 8);
  popMatrix();
  fill(#2B6CB0);
  ellipse(3 * sin(t) * s, 3 * cos(t) * s, 22, 22);
  popMatrix();

  fill(40);
  textSize(13);
  text("output device: " + deviceLabel, 12, 24);
  text("press D = list devices   |   click = play/pause", 12, height - 16);
}

// Print every audio device with its output channel count.
void listDevices() {
  String[] names = Sound.deviceNames();
  int def = Sound.getAudioDeviceManager().getDefaultOutputDeviceID();
  println("\nAvailable audio devices:");
  for (int i = 0; i < names.length; i++) {
    int ch = MultiChannel.availableChannels(i);
    String mark = (i == def) ? "   <- system default" : "";
    println("  [" + i + "] " + names[i] + "  (" + ch + " outputs)" + mark);
  }
}

void mousePressed() {
  if (source.isPlaying()) {
    source.stop();
  } else {
    source.loop();
  }
}

void keyPressed() {
  if (key == 'd' || key == 'D') listDevices();
}
