/**
 * Resonance Binaural (Sound library)
 *
 * Spatial audio with Google Resonance Audio through headphones, fully
 * integrated with the Processing Sound library: ResonanceSoundFile decodes &
 * plays the audio file (like SoundFile), but the audio is routed through the
 * ResonanceAudio renderer instead of directly to the speakers, and rendered
 * binaurally (2-channel HRTF output, decoded).
 *
 * Companion of the ResonanceBinauralStandalone example: identical scene and
 * visualization, the only difference is that playback goes through the
 * Processing Sound library here.
 *
 * Controls:
 *   mouse X  - rotate the listener's head (yaw, head-tracked binaural)
 *   mouse Y  - move the height of the orbiting source
 *   click    - toggle playback
 *   A        - toggle reverb room model
 *   R        - restart playback
 *
 * To pick a specific output device by name, see the
 * ResonanceDeviceSelectorSoundLib example.
 *
 * Requires: ResonanceAudio library + Sound library + headphones!
 */

import processing.sound.*;
import com.google.resonanceaudio.*;

Sound sound;
ResonanceAudio ra;
ResonanceAudioEngine engine;

ResonanceSoundFile sourceA;  // orbiting
boolean roomOn = false;

void setup() {
  size(640, 480);
  frameRate(60);

  // Initialize the Processing Sound library (audio engine + output device).
  sound = new Sound(this);

  // Set up the Resonance Audio renderer: 512-frame blocks at the sound
  // library's sample rate, 2-channel binaural output (headphones).
  ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
  options.numFramesPerBuffer = 512;
  options.sampleRate = Sound.sampleRate();
  options.numOutputChannels = ResonanceAudio.OUTPUT_BINAURAL;
  ra = new ResonanceAudio(options);

  // The engine unit plugs Resonance into the Sound library's synthesis
  // engine and connects the rendered output to the speakers. 4 = number of
  // simultaneous spatial sources available.
  engine = new ResonanceAudioEngine(this, ra, 4);

  // Sound file: decoded & played by the Sound library, but routed through
  // Resonance instead of directly to the output.
  sourceA = new ResonanceSoundFile(this, "loop_mono.wav", engine,
    RenderingMode.BINAURAL_HIGH_QUALITY);
  sourceA.amp(0.8);
  sourceA.getSoundSource().setDistanceRolloff(DistanceRolloffModel.LOGARITHMIC, 0.5, 25);
  sourceA.loop();

  ra.setMasterVolume(0.9);
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
  text("mouse X = turn head   |   click = play/pause   |   A = room model "
    + (roomOn ? "ON" : "off") + "   |   R = restart", 12, height - 16);
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
}
