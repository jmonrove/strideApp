// Inspection only reads native API metadata; activity writes need confirmation.
// Only timing/counters are retained here; BPM values never enter this controller.
export function createExerciseTest(options) {
  let phase = 'idle';
  let message = '60s test: creates Fitbit activity';
  let api = null;
  let owned = false;
  let armedUntil = 0;
  let startedAt = 0;
  let lastClock = 0;
  let callbacks = 0;
  let samples = 0;
  let lastSampleAt = 0;
  let maxGapMs = 0;
  let failure = null;
  function clock() {
    const value = options.now();
    if (typeof value !== 'number' || value !== value || value < 0 || value === Infinity) throw Error('Invalid clock');
    return value;
  }
  function changed() { if (options.changed) options.changed(); }
  function allowed(input) { return input && input.onWrist === true && input.paused === false; }
  function stateLabel(value) {
    if (value === null) return 'null';
    if (typeof value === 'string') return value.slice(0, 24);
    if (typeof value === 'number' || typeof value === 'boolean') return String(value);
    return typeof value;
  }
  function errorText(error) {
    // Native diagnostics only. Do not inspect stats or include sensor values.
    try {
      if (error && typeof error.message === 'string') return error.message.slice(0, 96);
      if (typeof error === 'string') return error.slice(0, 96);
    } catch (_) {}
    return 'No native error message';
  }
  function errorName(error) {
    try { if (error && typeof error.name === 'string') return error.name.slice(0, 24); } catch (_) {}
    return typeof error;
  }
  function nativeState() {
    try { return stateLabel(api.state); } catch (_) { return 'state getter threw'; }
  }
  function inspect() {
    try {
      if (!options.permission()) return {state:'permission missing', valid:false};
      const module = options.load();
      const candidate = module && (module.default || module.exercise || module);
      if (!candidate || typeof candidate.start !== 'function' || typeof candidate.stop !== 'function')
        return {state:'API unavailable', valid:false};
      const state = candidate.state;
      return {state:stateLabel(state), valid:state === 'stopped' || state === 'started' || state === 'paused'};
    } catch (_) { return {state:'API read failed', valid:false}; }
  }
  function snapshot() {
    const elapsed = phase === 'active' ? Math.max(0, lastClock - startedAt) : 0;
    return {phase, message, owned, samples, failure, maxGapSec: Math.ceil(maxGapMs / 1000),
      remainingSec: phase === 'active' ? Math.max(0, 60 - Math.floor(elapsed / 1000)) : 0};
  }
  function stop(reason) {
    if (!owned) {
      if (phase === 'armed') { phase = 'idle'; message = 'Test cancelled; nothing recorded'; changed(); }
      return snapshot();
    }
    try {
      const state = api.state;
      if (state === 'started' || state === 'paused') api.stop();
      if (api.state !== 'stopped') throw Error('Stop not confirmed');
      if (startedAt) maxGapMs = Math.max(maxGapMs, Math.max(0, clock() - (lastSampleAt || startedAt)));
      owned = false;
      phase = 'done';
      message = (reason || 'Test ended') + ': ' + samples + ' readings, max gap ' + Math.ceil(maxGapMs / 1000) + 's';
    } catch (_) {
      phase = 'error';
      message = 'Stop unconfirmed: end exercise in Fitbit';
    }
    changed();
    return snapshot();
  }
  function arm(input) {
    if (owned) return snapshot();
    failure = null;
    if (!allowed(input)) { phase = 'blocked'; message = 'Wear watch; resume HR before test'; changed(); return snapshot(); }
    phase = 'armed';
    armedUntil = clock() + 10000;
    message = 'Confirm: records 60s Treadmill activity';
    changed();
    return snapshot();
  }
  function confirm(input) {
    if (owned) return snapshot();
    if (phase !== 'armed' || clock() >= armedUntil || !allowed(input)) {
      phase = 'idle'; message = 'Start cancelled; nothing recorded'; changed(); return snapshot();
    }
    try {
      if (!options.permission()) throw Error('Permission missing');
    } catch (_) { phase = 'blocked'; message = 'Fitbit exercise permission required'; changed(); return snapshot(); }
    try {
      const module = options.load();
      api = module && (module.default || module.exercise || module);
      if (!api || typeof api.start !== 'function' || typeof api.stop !== 'function') throw Error('Unsupported exercise API');
      const existing = api.state;
      if (existing !== 'stopped') {
        if (existing === 'started' || existing === 'paused') {
          phase = 'blocked'; message = 'Fitbit exercise ' + existing + '; end it first';
        } else {
          phase = 'unsupported'; message = 'Exercise state: ' + stateLabel(existing) + '; blocked';
        }
        changed(); return snapshot();
      }
    } catch (_) {
      api = null;
      phase = 'unsupported'; message = 'Exercise API unavailable; HR still works'; changed(); return snapshot();
    }
    // The state was verified stopped. Mark ownership before start so a partial
    // native failure cannot be mistaken for permission to create another session.
    startedAt = clock(); lastClock = startedAt; callbacks = 0;
    samples = 0; lastSampleAt = 0; maxGapMs = 0;
    owned = true;
    let startReturned = false;
    try {
      api.start('treadmill', {gps:false, autopause:false});
      startReturned = true;
      const after = api.state;
      if (after !== 'started') {
        failure = {stage:'state after return', name:'Unconfirmed', detail:'start returned; state=' + stateLabel(after), state:stateLabel(after)};
        throw Error('Start not confirmed');
      }
      phase = 'active'; message = 'Recording test activity';
    } catch (error) {
      if (!failure) failure = {stage:startReturned ? 'read state after start' : 'native start', name:errorName(error), detail:errorText(error), state:nativeState()};
      stop('Start failed');
      if (!owned) { phase = 'error'; message = 'Start failed: ' + failure.name + ' / ' + failure.state; }
    }
    changed();
    return snapshot();
  }
  function reading() {
    if (phase !== 'active') return;
    const time = clock();
    if (time < lastClock || time < startedAt || time - startedAt >= 60000) { stop('Test complete'); return; }
    samples++;
    maxGapMs = Math.max(maxGapMs, time - (lastSampleAt || startedAt));
    lastSampleAt = time;
  }
  function tick(input) {
    const time = clock();
    // Read-only follow-up distinguishes a stable rejection from a late change.
    if (phase === 'error' && failure && !owned) failure.state = nativeState();
    if (phase === 'armed' && (time >= armedUntil || !allowed(input))) {
      phase = 'idle'; message = 'Start cancelled; nothing recorded'; changed();
    }
    if (phase !== 'active') return snapshot();
    if (!allowed(input)) return stop(input && input.paused ? 'HR paused' : 'Wear lost');
    if (time < lastClock) return stop('Clock changed');
    lastClock = time; callbacks++;
    try {
      if (api.state === 'stopped') {
        owned = false; phase = 'done'; message = 'Exercise ended outside this probe'; changed(); return snapshot();
      }
      if (api.state !== 'started') return stop('Exercise paused');
    } catch (_) { return stop('Exercise state unavailable'); }
    if (time - startedAt >= 60000 || callbacks >= 60) return stop('Test complete');
    return snapshot();
  }
  return {snapshot, inspect, arm, confirm, reading, tick, stop};
}
