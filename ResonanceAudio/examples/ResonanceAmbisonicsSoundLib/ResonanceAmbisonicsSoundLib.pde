/**
 * Resonance Ambisonics B-Format (Sound library)
 *
 * Third order Ambisonics output with Google Resonance Audio, integrated with
 * the Processing Sound library. A sound file is spatialized and rendered to
 * 16-channel Ambisonics B-format (ACN ordering, SN3D normalization = AmbiX).
 * No speaker decoder is applied -- the raw B-format soundfield is sent to the
 * 16 output channels of the audio device, ready for an external ambisonic
 * decoder.
 *
 * As in the Sound library's MultiChannelOutput example, we look for an audio
 * device with >= 16 output channels and select it with Sound.outputDevice().
 * On Windows, usePortAudio() often improves multi-channel USB interface
 * support.
 *
 * The listener is fixed at the center. The B-format output is world-space
 * ("pre head-rotation"): channels stay anchored to the room, so a fixed
 * loudspeaker array renders stable directions.
 *
 * Companion of the ResonanceAmbisonicsStandalone example: identical scene
 * and visualization, the only difference is that playback goes through the
 * Processing Sound library here.
 *
 * Controls:
 *   click    - toggle playback
 *   R        - restart playback
 *
 * Requires: ResonanceAudio library + Sound library + a 16-channel output
 * device (e.g. a USB interface, or a virtual matrix like VB-Audio Matrix /
 * Voicemeeter Banana). Route the device into e.g. Reaper/Bitwig/Ircam Spat5
 * to decode. See ResonanceDeviceSelectorSoundLib for how to select the
 * output device by name.
 */

import processing.sound.*;
import com.google.resonanceaudio.*;

Sound sound;
ResonanceAudio ra;
ResonanceAudioEngine engine;
ResonanceSoundFile sourceA;  // orbiting

String[] deviceNames;
String outName = "";

void setup() {
  size(640, 480);
  frameRate(60);

  sound = new Sound(this);

  // --- find an output device with at least 16 channels -------------------
  // (on Windows, PortAudio often reports the correct channel counts for
  // multi-channel USB interfaces -- same recommendation as the Sound
  // library's MultiChannelOutput example)
  if (!MultiChannel.usePortAudio()) {
    println("PortAudio not available, using default JavaSound device list.");
  }
  deviceNames = Sound.list();
  int device = -1;
  for (int i = 0; i < deviceNames.length; i++) {
    if (MultiChannel.availableChannels(i) >= 16) {
      println("Found a 16-channel device: " + deviceNames[i]);
      device = i;
      break;
    }
  }
  if (device < 0) {
    die("No output device with >= 16 channels found. Install a virtual "
      + "16-channel device (e.g. VB-Audio Matrix) or use a multi-channel "
      + "USB interface and run the sketch again.");
  }
  Sound.outputDevice(device);
  outName = deviceNames[device];

  // --- third order ambisonic B-format output ------------------------------
  ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
  options.numFramesPerBuffer = 512;
  options.sampleRate = Sound.sampleRate();
  options.numOutputChannels = ResonanceAudio.OUTPUT_AMBISONIC_3RD_ORDER;
  // ...to render a lower ambisonic order instead, use one of:
  //   options.numOutputChannels = ResonanceAudio.OUTPUT_AMBISONIC_1ST_ORDER;  // 4ch
  //   options.numOutputChannels = ResonanceAudio.OUTPUT_AMBISONIC_2ND_ORDER;  // 9ch
  // (device selection above must then find a device with >= 4/9/16 channels)
  ra = new ResonanceAudio(options);

  engine = new ResonanceAudioEngine(this, ra, 4);

  sourceA = new ResonanceSoundFile(this, "loop_mono.wav", engine);
  sourceA.amp(0.8);
  sourceA.getSoundSource().setDistanceRolloff(DistanceRolloffModel.LOGARITHMIC, 0.5, 25);
  sourceA.loop();

  ra.setMasterVolume(0.9);
  // World-space (pre-rotation) B-format is ON by default:
  // ra.setAmbisonicListenerRotationCompensated(true);
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
  text("16-ch 3rd order Ambisonics B-format (ACN/SN3D, undecoded) on: "
    + outName + "   |   click = play/pause   |   R = restart",
    12, height - 16);
}

// simple top-down visualization
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
  // orbiting source
  float t = millis() / 1000.0;
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
