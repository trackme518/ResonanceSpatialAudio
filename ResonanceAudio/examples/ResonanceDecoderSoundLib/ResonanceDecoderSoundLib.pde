/**
 * Resonance Speaker Decoder (Sound library)
 *
 * Full ambisonic-to-loudspeaker pipeline with Google Resonance Audio: sound
 * files are spatialized to world-space 3rd order B-format (ACN/SN3D) inside
 * the renderer and decoded to a loudspeaker array by a PRECOMPUTED matrix
 * loaded from an IEM AllRADecoder/MatrixDecoder JSON file
 * (data/decoder_default.json, here an AllRAD order-3 decoder for an
 * 11-speaker dome, maxRE weights baked in).
 *
 * The library output mode is ResonanceAudio.OUTPUT_DECODER: the number of
 * output channels equals the number of loudspeakers (matrix rows), and the
 * Processing Sound library routes one device channel per loudspeaker, exactly
 * as in the MultiChannelOutput example.
 *
 * The listener is fixed at the center (B-format output is world-space, which
 * is what a fixed speaker array needs). Speaker positions from the JSON are
 * drawn in the visualization.
 *
 * Controls:
 *   click    - toggle playback
 *   R        - restart playback
 *
 * Requires: ResonanceAudio library + Sound library + an output device with at
 * least as many channels as the decoder has speakers. Set OUTPUT_DEVICE
 * below to use a specific device by name instead of auto-selecting the
 * first capable one (see ResonanceDeviceSelectorSoundLib).
 */

import processing.sound.*;
import com.google.resonanceaudio.*;

// Exact output device name (case-insensitive) as printed by Sound.list().
// Leave empty to auto-select the first device with enough channels.
static final String OUTPUT_DEVICE = "Reproduktory (US-16x08) - MME";
//static final String OUTPUT_DEVICE = "US-16x08 ASIO - ASIO";
//static final String OUTPUT_DEVICE = "Reproduktory (US-16x08 Audio) - Windows WDM-KS";


Sound sound;
SpeakerDecoder decoder;
ResonanceAudio ra;
ResonanceAudioEngine engine;
ResonanceSoundFile sourceA;  // orbiting

String[] deviceNames;
String outName = "";
int numSpeakers;

void setup() {
  size(640, 480);
  frameRate(60);
  
  MultiChannel.usePortAudio();
  // --- load the precomputed decoding matrix -------------------------------
  try {
    decoder = new SpeakerDecoder(dataPath("decoder_8ring.json"));
    //decoder = new SpeakerDecoder(dataPath("decoder_default.json"));
  }
  catch (Exception e) {
    die("Could not load decoder JSON: " + e);
    return;
  }
  numSpeakers = decoder.getNumChannels();
  println("Decoder '" + decoder.getName() + "' -> " + numSpeakers
    + " loudspeakers");

  sound = new Sound(this);

  // --- find an output device with enough channels --------------------------
  // (like the Sound library's MultiChannelOutput example; PortAudio often
  // reports the correct channel counts for multi-channel USB interfaces)
  if (!MultiChannel.usePortAudio()) {
    println("PortAudio not available, using default JavaSound device list.");
  }
  deviceNames = Sound.list();
  int device = -1;
  if (OUTPUT_DEVICE.length() > 0) {
    // pick the device by name instead of searching for a capable one
    for (int i = 0; i < deviceNames.length; i++) {
      if (deviceNames[i].equalsIgnoreCase(OUTPUT_DEVICE)) {
        device = i;
        break;
      }
    }
    if (device < 0) {
      die("No output device named '" + OUTPUT_DEVICE + "'. The device list is above.");
      return;
    }
    if (MultiChannel.availableChannels(device) < numSpeakers) {
      die("Device '" + OUTPUT_DEVICE + "' has only "
        + MultiChannel.availableChannels(device) + " outputs, but "
        + numSpeakers + " are needed.");
      return;
    }
    println("Using named output device: " + deviceNames[device]);
  } else {
    for (int i = 0; i < deviceNames.length; i++) {
      if (MultiChannel.availableChannels(i) >= numSpeakers) {
        println("Found a " + numSpeakers + "-channel device: " + deviceNames[i]);
        device = i;
        break;
      }
    }
  }
  if (device < 0) {
    die("No output device with >= " + numSpeakers + " channels found. "
      + "Install a virtual audio matrix (e.g. VB-Audio Matrix) or use a "
      + "multi-channel USB interface and run the sketch again.");
  }
  Sound.outputDevice(device);
  outName = deviceNames[device];
  println("Using "+outName);

  // --- renderer in decoder output mode -------------------------------------
  // Internally: 16-ch world-space B-format -> multiply with the decoder
  // matrix -> numSpeakers channels. Binaural output stays available via
  // ra.fillBinauralBuffer().
  ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
  options.numFramesPerBuffer = 512;
  options.sampleRate = Sound.sampleRate();
  options.numOutputChannels = ResonanceAudio.OUTPUT_DECODER;
  // The ambisonic order is taken from the decoder JSON automatically
  // (Decoder.NumberOfInputChannels: 4 = 1st, 9 = 2nd, 16 = 3rd order),
  // e.g. load decoder/data/iemcube-style 1st/2nd order exports as well.
  ra = new ResonanceAudio(options, decoder);

  engine = new ResonanceAudioEngine(this, ra, 4);

  sourceA = new ResonanceSoundFile(this, "loop_mono.wav", engine);
  sourceA.amp(0.8);
  sourceA.getSoundSource().setDistanceRolloff(DistanceRolloffModel.LOGARITHMIC, 0.5, 25);
  sourceA.loop();

  ra.setMasterVolume(0.9);
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
  text(numSpeakers + "-speaker AllRAD decode (world-space B-format -> "
    + "speakers) on: " + outName + "   |   click = play/pause   |   R = restart",
    12, height - 16);
}

// top-down visualization incl. loudspeaker positions from the JSON
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
  // loudspeakers (azimuth from front, positive toward LEFT in the IEM /
  // renderer azimuth convention; projected to 2D), device channel = row+1
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
}
