// Transport validation only. These are not exercise targets or a medical assessment.
export const MAX_SAMPLE_AGE_MS = 5000;
export function inspectDisplayFrame(frame, nowMs) {
  if (!frame || frame.kind !== 'hr_display' || frame.version !== 1 || frame.source !== 'sense2_exercise' ||
      frame.displayOnly !== true || frame.quality !== 'acquisition_unknown' || frame.acquisitionAgeMs !== null)
    return {ok:false,reason:'invalid_display_frame'};
  if (typeof frame.sessionId !== 'string' || !/^[A-Za-z0-9_:-]{8,80}$/.test(frame.sessionId) ||
      !Number.isInteger(frame.sequence) || frame.sequence < 1) return {ok:false,reason:'invalid_sequence'};
  if (!Number.isFinite(frame.polledAtMs) || frame.polledAtMs < 0 || !Number.isFinite(frame.pollAgeMs) || frame.pollAgeMs < 0)
    return {ok:false,reason:'invalid_poll_time'};
  const transit = nowMs - frame.polledAtMs;
  if (transit < -2000) return {ok:false,reason:'clock_skew'};
  const pollAgeMs = Math.max(0, transit, frame.pollAgeMs);
  if (pollAgeMs > 5000) return {ok:false,reason:'stale_poll'};
  const active = frame.status === 'display_only';
  if (active ? frame.onWrist !== true || !Number.isFinite(frame.bpm) || frame.bpm < 30 || frame.bpm > 240 :
      ['inactive','paused','off_wrist','unavailable'].indexOf(frame.status) < 0 || frame.bpm !== null)
    return {ok:false,reason:'invalid_display_reading'};
  return {ok:true,sample:{bpm:active ? Math.round(frame.bpm) : null,pollAgeMs,source:'sense2_exercise',sessionId:frame.sessionId,
    sequence:frame.sequence,status:frame.status,displayOnly:true,acquisitionAgeMs:null,quality:'acquisition_unknown'}};
}
export function inspectFrame(frame, nowMs) {
  if (!frame || frame.kind !== 'hr' || frame.version !== 1 || frame.source !== 'sense2') {
    return { ok: false, reason: 'invalid_frame' };
  }
  if (typeof frame.sessionId !== 'string' || frame.sessionId.length < 1 || frame.sessionId.length > 80 ||
      !Number.isInteger(frame.sequence) || frame.sequence < 1) {
    return { ok: false, reason: 'invalid_sequence' };
  }
  if (frame.status !== 'ok') return { ok: false, reason: frame.status || 'invalid_status' };
  if (frame.onWrist !== true) return { ok: false, reason: frame.onWrist === false ? 'off_wrist' : 'unknown_wear_state' };
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
