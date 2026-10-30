// Inspection only reads metadata. Native state changes may arrive after calls return.
import { createStatsDiagnostic } from './exercise-stats.js';
export function createExerciseTest(options) {
  let phase = 'idle', message = '60s test: creates Fitbit activity';
  let api = null, owned = false, armedUntil = 0, startedAt = 0, lastClock = 0;
  let callbacks = 0, samples = 0, lastSampleAt = 0, maxGapMs = 0, failure = null;
  let startPending = false, cancelRequested = false, stopSent = false, stopSentAt = 0;
  let stopReason = '', state = 'unchecked', stateEvents = 0, startDelayMs = null;
  let statsDiagnostic = null;
  const startWaitMs = 10000, stopWaitMs = 10000;
  function clock() {
    const v = options.now();
    if (typeof v !== 'number' || v !== v || v < 0 || v === Infinity) throw Error('Invalid clock');
    return v;
  }
  function changed() { if (options.changed) options.changed(); }
  function allowed(input) { return input && input.onWrist === true && input.paused === false; }
  function stateLabel(v) {
    if (v === null) return 'null';
    if (typeof v === 'string') return v.slice(0, 24);
    if (typeof v === 'number' || typeof v === 'boolean') return String(v);
    return typeof v;
  }
  function errorText(e) {
    // Native errors only; no stats or sensor values enter diagnostics.
    try {
      if (e && typeof e.message === 'string') return e.message.slice(0, 96);
      if (typeof e === 'string') return e.slice(0, 96);
    } catch (_) {}
    return 'No native error message';
  }
  function errorName(e) {
    try { if (e && typeof e.name === 'string') return e.name.slice(0, 24); } catch (_) {}
    return typeof e;
  }
  function readState() {
    const value = api.state; state = stateLabel(value);
    if (failure) failure.state = state;
    return value;
  }
  function recordFailure(stage, error) {
    failure = {stage, name:errorName(error), detail:errorText(error), state};
  }
  function inspect() {
    try {
      if (!options.permission()) return {state:'permission missing', valid:false};
      const module = options.load(), candidate = module && (module.default || module.exercise || module);
      if (!candidate || typeof candidate.start !== 'function' || typeof candidate.stop !== 'function')
        return {state:'API unavailable', valid:false};
      const value = candidate.state;
      return {state:stateLabel(value), valid:value === 'stopped' || value === 'started' || value === 'paused'};
    } catch (_) { return {state:'API read failed', valid:false}; }
  }
  function snapshot() {
    const elapsed = owned ? Math.max(0, lastClock - startedAt) : 0;
    return {phase, message, owned, samples, failure, nativeState:state, stateEvents, startDelayMs,
      stats:statsDiagnostic ? statsDiagnostic.snapshot(lastClock) : null,
      maxGapSec:Math.ceil(maxGapMs / 1000),
      remainingSec:owned ? Math.max(0, 60 - Math.floor(elapsed / 1000)) : 0,
      waitSec:phase === 'starting' ? Math.max(0, Math.ceil((startWaitMs - elapsed) / 1000)) : 0};
  }
  function finish(time, reason) {
    maxGapMs = Math.max(maxGapMs, Math.max(0, time - (lastSampleAt || startedAt)));
    lastClock = time; owned = false; phase = 'done';
    message = reason + ': ' + samples + ' readings, max gap ' + Math.ceil(maxGapMs / 1000) + 's';
    changed(); return snapshot();
  }
  function sendStop(time) {
    // Guard before calling native code: its state events may be synchronous.
    stopSent = true; stopSentAt = time; phase = 'stopping';
    message = 'Stopping; waiting for Fitbit';
    try {
      api.stop();
      if (readState() === 'stopped') return finish(time, stopReason);
    } catch (error) {
      recordFailure('native stop', error); phase = 'error';
      message = 'Stop unconfirmed: end exercise in Fitbit';
    }
    changed(); return snapshot();
  }
  function observe(time) {
    let current;
    try { current = readState(); }
    catch (error) {
      recordFailure('read exercise state', error); phase = 'error';
      cancelRequested = true; stopReason = stopReason || 'State unavailable';
      message = 'State unknown: check Fitbit exercise'; changed(); return snapshot();
    }
    if (current === 'started' || current === 'paused') {
      if (startPending) { startPending = false; startDelayMs = Math.max(0, time - startedAt); }
      if (current === 'paused') { cancelRequested = true; stopReason = stopReason || 'Exercise paused'; }
      if (cancelRequested && !stopSent) return sendStop(time);
      if (stopSent) {
        if (time - stopSentAt >= stopWaitMs) { phase = 'error'; message = 'Stop unconfirmed: end exercise in Fitbit'; }
      } else {
        phase = 'active'; message = 'Recording test activity';
        // Comparison only: never forwarded as timestamped heart-rate input.
        try { statsDiagnostic.observe(api.stats, time); }
        catch (_) { statsDiagnostic.failed(time); }
      }
    } else if (current === 'stopped') {
      if (!startPending) return finish(time, stopReason || 'Exercise ended outside this probe');
      if (time - startedAt >= startWaitMs) {
        // No documented cancel-start API exists. Keep the request guarded so a
        // late start is stopped and no replacement request can overlap it.
        cancelRequested = true; stopReason = stopReason || 'Start timed out'; phase = 'error';
        if (!failure) failure = {stage:'start timeout', name:'Unconfirmed', detail:'No started state within 10s; late start guarded', state};
        message = 'No start after 10s; late start guarded';
      }
    } else {
      cancelRequested = true; stopReason = stopReason || 'Unknown state'; phase = 'error';
      message = 'Exercise state: ' + state + '; check Fitbit';
    }
    changed(); return snapshot();
  }
  function stop(reason) {
    if (!owned) {
      if (phase === 'armed') { phase = 'idle'; message = 'Test cancelled; nothing recorded'; changed(); }
      return snapshot();
    }
    cancelRequested = true; stopReason = stopReason || reason || 'Test ended';
    if (startPending && phase === 'starting') message = 'Start cancelled; watching for late start';
    return observe(clock());
  }
  function arm(input) {
    if (owned) return snapshot();
    failure = null;
    if (!allowed(input)) { phase = 'blocked'; message = 'Wear watch; resume HR before test'; changed(); return snapshot(); }
    phase = 'armed'; armedUntil = clock() + 10000;
    message = 'Confirm: records 60s Treadmill activity'; changed(); return snapshot();
  }
  function confirm(input) {
    if (owned) return snapshot();
    if (phase !== 'armed' || clock() >= armedUntil || !allowed(input)) {
      phase = 'idle'; message = 'Start cancelled; nothing recorded'; changed(); return snapshot();
    }
    try { if (!options.permission()) throw Error('Permission missing'); }
    catch (_) { phase = 'blocked'; message = 'Fitbit exercise permission required'; changed(); return snapshot(); }
    try {
      const module = options.load(); api = module && (module.default || module.exercise || module);
      if (!api || typeof api.start !== 'function' || typeof api.stop !== 'function') throw Error('Unsupported exercise API');
      const existing = readState();
      if (existing !== 'stopped') {
        if (existing === 'started' || existing === 'paused') { phase = 'blocked'; message = 'Fitbit exercise ' + existing + '; end it first'; }
        else { phase = 'unsupported'; message = 'Exercise state: ' + state + '; blocked'; }
        changed(); return snapshot();
      }
    } catch (_) { api = null; phase = 'unsupported'; message = 'Exercise API unavailable; HR still works'; changed(); return snapshot(); }
    startedAt = clock(); lastClock = startedAt; callbacks = 0;
    samples = 0; lastSampleAt = 0; maxGapMs = 0; stateEvents = 0; startDelayMs = null;
    statsDiagnostic = createStatsDiagnostic(startedAt);
    owned = true; startPending = true; cancelRequested = false; stopSent = false; stopReason = '';
    phase = 'starting'; message = 'Starting; waiting for Fitbit';
    // Events supplement polling; callbacks never make native writes themselves.
    try { api.onstatechange = () => { stateEvents++; }; } catch (_) {}
    try { api.start('treadmill', {gps:false, autopause:false}); }
    catch (error) {
      recordFailure('native start', error); cancelRequested = true; stopReason = 'Start failed';
      phase = 'error'; message = 'Start threw: ' + failure.name + '; checking state';
    }
    return observe(startedAt);
  }
  function reading() {
    if (phase !== 'active') return;
    const time = clock();
    if (time < lastClock || time < startedAt || time - startedAt >= 60000) { stop('Test complete'); return; }
    samples++; maxGapMs = Math.max(maxGapMs, time - (lastSampleAt || startedAt)); lastSampleAt = time;
  }
  function tick(input) {
    const time = clock();
    if (phase === 'armed' && (time >= armedUntil || !allowed(input))) { phase = 'idle'; message = 'Start cancelled; nothing recorded'; changed(); }
    if (!owned) return snapshot();
    if (!allowed(input)) { cancelRequested = true; stopReason = stopReason || (input && input.paused ? 'HR paused' : 'Wear lost'); }
    if (time < lastClock) { cancelRequested = true; stopReason = stopReason || 'Clock changed'; }
    lastClock = time; callbacks++;
    if (time - startedAt >= 60000 || callbacks >= 60) { cancelRequested = true; stopReason = stopReason || 'Test complete'; }
    return observe(time);
  }
  return {snapshot, inspect, arm, confirm, reading, tick, stop};
}
