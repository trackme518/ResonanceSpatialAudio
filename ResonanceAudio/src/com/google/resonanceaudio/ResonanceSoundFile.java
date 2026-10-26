package com.google.resonanceaudio;

import com.jsyn.engine.SynthesisEngine;
import com.jsyn.ports.QueueDataCommand;
import com.jsyn.ports.UnitOutputPort;

import processing.core.PApplet;
import processing.sound.Sound;
import processing.sound.SoundFile;

/**
 * A {@link processing.sound.SoundFile} that does NOT play to the speakers
 * directly, but routes its decoded audio through a {@link ResonanceAudio}
 * spatializer connected to the Sound library's output device(s).
 *
 * <p>All standard Sound library file methods (amp, rate, position, duration,
 * channels, ...) work as usual; play/loop/stop are overridden so the playback
 * is fed into the spatial renderer instead of being wired straight to the
 * channel outputs.</p>
 *
 * <pre>
 * ResonanceSoundFile file = new ResonanceSoundFile(this, "sound.wav", engine);
 * file.getSoundSource().setPosition(1, 0, -2);
 * file.loop();
 * </pre>
 */
public class ResonanceSoundFile extends SoundFile {

  private final ResonanceAudioEngine engine;
  private final ResonanceSoundTap tap;
  private final SoundSource source;
  private final int slot;
  private boolean addedToSynth = false;

  public ResonanceSoundFile(PApplet parent, String path,
      ResonanceAudioEngine engine) {
    this(parent, path, engine, RenderingMode.BINAURAL_HIGH_QUALITY);
  }

  /**
   * @param parent the PApplet
   * @param path   audio file (from the sketch's data folder), decoded by the
   *               Processing Sound library
   * @param engine the Resonance Audio engine driving the output device
   * @param mode   rendering mode for this source
   */
  public ResonanceSoundFile(PApplet parent, String path,
      ResonanceAudioEngine engine, RenderingMode mode) {
    super(parent, path);
    this.engine = engine;
    this.slot = engine.allocateSlot();
    if (slot < 0) {
      throw new IllegalStateException("ResonanceAudioEngine is full (max "
          + ResonanceAudioEngine.MAX_SOURCES + " source slots)");
    }
    source = engine.getResonanceAudio().addSoundObjectSource(mode);
    tap = new ResonanceSoundTap(channels() == 1);
    SynthesisEngine synth = Sound.getSynthesisEngine();
    synth.add(tap);
    UnitOutputPort circuitOutput = getUnitGenerator().getOutput();
    circuitOutput.connect(0, tap.in0, 0);
    if (channels() >= 2) {
      circuitOutput.connect(1, tap.in1, 0);
    }
    tap.configure(source, slot);
    engine.connectTap(tap, slot);
    parent.registerMethod("dispose", this);
  }

  /** The spatial source handle for positioning, directivity etc. */
  public SoundSource getSoundSource() {
    return source;
  }

  // ------------------------------------------------------------- transport
  // Overrides that keep playback inside the Resonance graph. Note: the
  // convenience overloads play(rate, pos, amp, ...) are inherited unchanged
  // and would route audio to the speakers -- use amp()/rate()/play()/loop()
  // individually.

  @Override
  public void play() {
    queuePlayback(0f, false);
  }

  /** Starts playback once from the given offset in seconds. */
  public void playFrom(float positionSeconds) {
    queuePlayback(positionSeconds, false);
  }

  @Override
  public void loop() {
    queuePlayback(0f, true);
  }

  /** Starts looping playback from the given offset in seconds. */
  public void loopFrom(float positionSeconds) {
    queuePlayback(positionSeconds, true);
  }

  private void queuePlayback(float positionSeconds, boolean loop) {
    SynthesisEngine synth = Sound.getSynthesisEngine();
    if (!addedToSynth) {
      // Add the file's JSyn circuit to the synthesis engine without
      // connecting it to any speaker output (that is what Engine.play()
      // would do and what we deliberately avoid).
      synth.add(getUnitGenerator());
      addedToSynth = true;
    }
    player.dataQueue.clear();
    int startFrame = (int) (positionSeconds * sampleRate());
    if (startFrame < 0) {
      startFrame = 0;
    }
    if (startFrame >= frames()) {
      startFrame = 0;
    }
    QueueDataCommand cmd = player.dataQueue.createQueueDataCommand(sample,
        startFrame, frames() - startFrame);
    cmd.setNumLoops(loop ? -1 : 0);
    synth.queueCommand(cmd);
    isPlaying = true;
  }

  @Override
  public void stop() {
    player.dataQueue.clear();
    isPlaying = false;
  }

  /** Approximate playback position in seconds (modulo file length). */
  @Override
  public float position() {
    return positionFrame() / (float) sampleRate();
  }

  @Override
  public int positionFrame() {
    return (int) (player.dataQueue.getFrameCount() % frames());
  }

  /** Removes this file's source from the renderer (called on dispose). */
  public void dispose() {
    stop();
    tap.deactivate();
    engine.releaseSlot(slot);
    try {
      Sound.getSynthesisEngine().remove(tap);
    } catch (Exception ignored) {
    }
    source.close();
  }
}
