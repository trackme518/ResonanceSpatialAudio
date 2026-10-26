/**
 * Resonance Binaural (standalone)
 *
 * Google Resonance Audio binaural rendering WITHOUT the Processing Sound
 * library: the WAV file is decoded with Java Sound (javax.sound.sampled),
 * playback/transport is handled by the FilePlayer class shipped with the
 * ResonanceAudio library, and the spatialized 2-channel binaural output is
 * written to the default output device via a SourceDataLine.
 *
 * Companion of the ResonanceBinauralSoundLib example: identical scene and
 * visualization, the only difference is that no Sound library is used here.
 *
 * Controls:
 *   mouse X  - rotate the listener's head (yaw, head-tracked binaural)
 *   mouse Y  - move the height of the orbiting source
 *   click    - toggle playback
 *   A        - toggle reverb room model
 *   R        - restart playback
 *
 * Requires: ResonanceAudio library + headphones!
 */

import com.google.resonanceaudio.*;
import javax.sound.sampled.*;

ResonanceAudio ra;
SoundSource src;
FilePlayer player;

SourceDataLine line;
Thread audioThread;
volatile boolean running = true;
boolean roomOn = false;

void setup() {
  size(640, 480);
  frameRate(60);

  // 1. decode the audio file (data/loop_mono.wav)
  try {
    WavUtil.WavData wav = WavUtil.load(dataPath("loop_mono.wav"));
    player = new FilePlayer(wav);
    println("loaded " + wav.samples.length / wav.channels
      + " frames @ " + wav.sampleRate + " Hz");

    // 2. create the renderer (sample rate of the file; binaural output)
    ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
    options.numFramesPerBuffer = 512;
    options.sampleRate = wav.sampleRate;
    options.numOutputChannels = ResonanceAudio.OUTPUT_BINAURAL;
    ra = new ResonanceAudio(options);

    src = ra.addSoundObjectSource(RenderingMode.BINAURAL_HIGH_QUALITY);
    src.setDistanceRolloff(DistanceRolloffModel.LOGARITHMIC, 0.5, 25);
    player.setVolume(0.8);
    player.setLooping(true);
    player.play();
  }
  catch (Exception e) {
    die("Could not open audio: " + e);
  }

  // 3. open a 2-channel 16-bit output line at the file's sample rate
  try {
    AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
      player.getSampleRate(), 16, 2, 4, player.getSampleRate(), false);
    line = AudioSystem.getSourceDataLine(fmt);
    line.open(fmt, player.getSampleRate()); // ~1s buffer
    line.start();
  }
  catch (Exception e) {
    die("Could not open output line: " + e);
  }

  // 4. audio thread: feed source -> render -> write to sound card
  //    (scene parameters are updated from draw() below)
  final int frames = ra.getFramesPerBuffer();
  final float[] out = new float[frames * 2];
  audioThread = new Thread() {
    public void run() {
      while (running) {
        player.sendTo(src, frames);
        if (ra.fillBinauralBuffer(out)) {
          byte[] bytes = new byte[out.length * 2];
          for (int i = 0; i < out.length; i++) {
            short s = (short)(constrain(out[i], -1, 1) * 32767);
            bytes[2 * i] = (byte)(s & 0xFF);
            bytes[2 * i + 1] = (byte)((s >> 8) & 0xFF);
          }
          line.write(bytes, 0, bytes.length);
        }
      }
    }
  };
  audioThread.setDaemon(true);
  audioThread.start();
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
  src.setPosition(3 * sin(t), ay, 3 * cos(t));

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
  if (player.isPlaying()) {
    player.pause();
  } else {
    player.play();
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
    player.replay();
  }
}

void dispose() {
  running = false;
  if (line != null) line.close();
  if (ra != null) ra.dispose();
}
