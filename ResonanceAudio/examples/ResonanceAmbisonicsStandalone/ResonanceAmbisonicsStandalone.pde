/**
 * Resonance Ambisonics B-Format (standalone)
 *
 * Third order Ambisonics output WITHOUT the Processing Sound library. The
 * renderer writes 16-channel B-format (ACN/SN3D, undecoded). We search all
 * javax.sound mixers for a >= 16-channel output line and play to it live; if
 * none exists we render the whole file offline to
 * ambisonics_offline.wav in the sketch folder instead (import that into any
 * ambisonic decoder/DCC).
 *
 * The listener is fixed at the center. The B-format output is world-space
 * ("pre head-rotation"): channels stay anchored to the room, so a fixed
 * loudspeaker array renders stable directions.
 *
 * Companion of the ResonanceAmbisonicsSoundLib example: identical scene and
 * visualization, the only difference is that no Sound library is used here.
 *
 * Controls:
 *   click    - toggle playback
 *   R        - restart playback
 *
 * For live 16-channel playback on Windows install a virtual matrix such as
 * VB-Audio Matrix or Voicemeeter Banana, or use a multi-channel USB
 * interface.
 */

import com.google.resonanceaudio.*;
import javax.sound.sampled.*;

ResonanceAudio ra;
SoundSource src;
FilePlayer player;

int outChans = 16;
SourceDataLine line;
Thread audioThread;
volatile boolean running = true;
String mode = "";

void setup() {
  size(640, 480);
  frameRate(60);

  WavUtil.WavData wav;
  try {
    wav = WavUtil.load(dataPath("loop_mono.wav"));
    player = new FilePlayer(wav);
    ResonanceAudio.InitOptions options = new ResonanceAudio.InitOptions();
    options.numFramesPerBuffer = 512;
    options.sampleRate = wav.sampleRate;
    options.numOutputChannels = ResonanceAudio.OUTPUT_AMBISONIC_3RD_ORDER;
    // ...to render a lower ambisonic order instead, use one of:
    //   options.numOutputChannels = ResonanceAudio.OUTPUT_AMBISONIC_1ST_ORDER;  // 4ch
    //   options.numOutputChannels = ResonanceAudio.OUTPUT_AMBISONIC_2ND_ORDER;  // 9ch
    // (also set outChans = 4 or 9 below so output buffers match)
    ra = new ResonanceAudio(options);
    src = ra.addSoundObjectSource(RenderingMode.BINAURAL_HIGH_QUALITY);
    src.setDistanceRolloff(DistanceRolloffModel.LOGARITHMIC, 0.5, 25);
    player.setVolume(0.8);
    player.setLooping(true);
    player.play();
  }
  catch (Exception e) {
    die("Audio file problem: " + e);
    return;
  }

  // find a mixer with >= 16 output channels (same idea as the Sound
  // library's MultiChannelOutput example, but on javax.sound directly)
  AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
    player.getSampleRate(), 16, outChans, outChans * 2,
    player.getSampleRate(), false);
  DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
  for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
    Mixer mixer = AudioSystem.getMixer(mi);
    if (mixer.isLineSupported(info)) {
      try {
        line = (SourceDataLine) mixer.getLine(info);
        line.open(fmt, player.getSampleRate() * 2);
        line.start();
        mode = "LIVE 16-channel output: " + mi.getName();
        break;
      }
      catch (Exception e) {
        line = null;
      }
    }
  }
  if (line == null) {
    // offline fallback: render the whole file to a 16-channel WAV
    mode = "no 16-ch device found -> rendering offline WAV (see console)";
    thread("renderOffline");
  } else {
    final int frames = ra.getFramesPerBuffer();
    final float[] out = new float[frames * outChans];
    audioThread = new Thread() {
      public void run() {
        while (running) {
          player.sendTo(src, frames);
          if (ra.fillAmbisonicBuffer(out)) {
            line.write(floatsToBytes(out), 0, out.length * 2);
          }
        }
      }
    };
    audioThread.setDaemon(true);
    audioThread.start();
  }
}

byte[] floatsToBytes(float[] in) {
  byte[] b = new byte[in.length * 2];
  for (int i = 0; i < in.length; i++) {
    short s = (short)(constrain(in[i], -1, 1) * 32767);
    b[2 * i] = (byte)(s & 0xFF);
    b[2 * i + 1] = (byte)(s >> 8);
  }
  return b;
}

void renderOffline() {
  int sr = player.getSampleRate();
  int total = (int)(player.duration() * sr) / 512 * 512;
  float[] out = new float[512 * outChans];
  short[] pcm = new short[total * outChans];
  int written = 0;
  float a = 0;
  player.replay();
  while (written < total) {
    src.setPosition(3 * sin(a), 0, 3 * cos(a));
    a += 0.02;
    ra.update(0, 0, 0, 0, 0, -1, 0, 1, 0);
    if (ra.fillAmbisonicBuffer(out)) {
      for (int i = 0; i < out.length && written < total; i++, written++) {
        pcm[written] = (short)(constrain(out[i], -1, 1) * 32767);
      }
    }
  }
  float[] f = new float[pcm.length];
  for (int i = 0; i < pcm.length; i++) f[i] = pcm[i] / 32767.0f;
  String path = sketchPath("ambisonics_offline.wav");
  try {
    WavUtil.write(path, f, outChans, sr);
    println("WROTE 16-channel B-format WAV: " + path);
  }
  catch (Exception e) {
    println("Could not write WAV: " + e);
  }
  running = false;
}

void draw() {
  background(245);

  float t = millis() / 1000.0;

  if (line != null) {
    // Listener fixed at the center, looking down -Z (no head rotation).
    ra.update(0, 0, 0, 0, 0, -1, 0, 1, 0);

    // The single source orbits around the listener, height follows mouseY.
    float ay = map(mouseY, 0, height, 2, -2);
    src.setPosition(3 * sin(t), ay, 3 * cos(t));
  }

  drawTopDown();

  fill(40);
  textSize(13);
  text("16-ch 3rd order Ambisonics B-format (ACN/SN3D, undecoded) | "
    + mode + "   |   click = play/pause   |   R = restart",
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
  if (line == null) {
    return;  // offline mode
  }
  if (player.isPlaying()) {
    player.pause();
  } else {
    player.play();
  }
}

void keyPressed() {
  if (key == 'r' || key == 'R') {
    player.replay();
  }
}

void dispose() {
  running = false;
  if (line != null) line.close();
  if (ra != null) ra.dispose();
}
