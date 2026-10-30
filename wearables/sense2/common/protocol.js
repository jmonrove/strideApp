// Transport validation only. These are not exercise targets or a medical assessment.
export const MAX_SAMPLE_AGE_MS = 5000;
export function inspectFrame(frame, nowMs) {
  if (!frame || frame.kind !== 'hr' || frame.version !== 1 || frame.source !== 'sense2') {
    return { ok: false, reason: 'invalid_frame' };
  }
  if (typeof frame.sessionId !== 'string' || frame.sessionId.length < 1 || frame.sessionId.length > 80 ||
      !Number.isInteger(frame.sequence) || frame.sequence < 1) {
    return { ok: false, reason: 'invalid_sequence' };
  }
  if (frame.status !== 'ok' || frame.onWrist === false) return { ok: false, reason: frame.status || 'off_wrist' };
  if (!Number.isFinite(frame.bpm) || frame.bpm <= 0 || frame.bpm > 300 ||
      !Number.isFinite(frame.observedAtMs) || !Number.isFinite(frame.ageMs) || frame.ageMs < 0) {
    return { ok: false, reason: 'invalid_reading' };
  }
  const transitMs = nowMs - frame.observedAtMs;
  if (transitMs < -2000) return { ok: false, reason: 'clock_skew' };
  const ageMs = Math.max(frame.ageMs, transitMs, 0);
  if (ageMs > MAX_SAMPLE_AGE_MS) return { ok: false, reason: 'stale' };
  return { ok: true, sample: {
    bpm: frame.bpm, ageMs, source: 'sense2', sequence: frame.sequence, sessionId: frame.sessionId,
  }};
}
export function parseSetting(raw) {
  if (!raw) return '';
  try {
    const value = JSON.parse(raw);
    return typeof value === 'string' ? value : (value && typeof value.name === 'string' ? value.name : '');
  } catch (_) { return ''; }
}
export function validEndpoint(value) {
  // No fixed destination or public relay. User configures their own lab endpoint.
  return typeof value === 'string' && /^https?:\/\/[^\s/]+\/api\/hr$/.test(value);
}
