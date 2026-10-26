package com.google.resonanceaudio;

/**
 * Distance rolloff models used for distance attenuation. Mirrors the
 * DistanceRolloffModel enum of the Resonance Audio C++ API.
 */
public enum DistanceRolloffModel {
  /** Logarithmic distance rolloff model. */
  LOGARITHMIC,
  /** Linear distance rolloff model. */
  LINEAR,
  /** Distance attenuation is set explicitly by the user. */
  NONE
}
