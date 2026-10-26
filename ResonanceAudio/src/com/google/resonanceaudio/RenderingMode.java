package com.google.resonanceaudio;

/**
 * Rendering modes define CPU load / rendering quality balance. They mirror the
 * RenderingMode enum of the Resonance Audio C++ API
 * (resonance_audio/api/resonance_audio_api.h).
 */
public enum RenderingMode {
  /** Stereo panning, i.e. this disables HRTF-based rendering. */
  STEREO_PANNING,
  /** HRTF-based rendering using First Order Ambisonics (8 virtual speakers). */
  BINAURAL_LOW_QUALITY,
  /** HRTF-based rendering using Second Order Ambisonics (12 virtual speakers). */
  BINAURAL_MEDIUM_QUALITY,
  /** HRTF-based rendering using Third Order Ambisonics (26 virtual speakers). */
  BINAURAL_HIGH_QUALITY,
  /** Room effects only rendering (no direct spatialized output). */
  ROOM_EFFECTS_ONLY
}
