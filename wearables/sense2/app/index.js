import * as document from 'document';
import { HeartRateSensor } from 'heart-rate';
import { BodyPresenceSensor } from 'body-presence';
import { me } from 'appbit';
import { display } from 'display';
import * as messaging from 'messaging';
import { createExerciseTest } from './exercise-test';

console.log('HR_PROBE runtime features: Number.isFinite=' + typeof Number.isFinite);
// Use primitive checks: the watch runtime may lack modern Number helpers.
function finiteNumber(value) {
  return typeof value === 'number' && value === value && value !== Infinity && value !== -Infinity;
}

// Request no inactivity termination. AMOLED display timeout remains system-managed;
// this does not wake the screen, request AOD, or promise background execution.
me.appTimeoutEnabled = false;
const startedAt = Date.now();
const sessionId = 'sense2-' + startedAt.toString(36) + '-' + Math.random().toString(36).slice(2, 8);
let sequence = 0;
let eventCount = 0;
let uniqueCount = 0;
let validCount = 0;
let ticks = 0;
let wakeCount = display.on ? 1 : 0;
let wearChanges = 0;
let sensorStarts = 0;
let rawBpm = null;
let rawTimestamp = null;
let lastSensorTimestamp = null;
let sensorTimestamp = null;
let lastObservedAt = 0;
let latestBpm = null;
let lastValidBpm = null;
let lastValidAt = 0;
let onWrist = null;
let paused = false;
let status = 'waiting';
let hrm = null;
let hrmRequested = false;
let body = null;
let phoneUnavailable = false;
let buttonTaps = 0;
const ui = {};
['bpm', 'state', 'link', 'count', 'clock', 'raw', 'last', 'runtime', 'wear', 'exercise', 'exercise-toggle', 'exercise-label', 'toggle', 'toggle-label']
  .forEach(id => ui[id] = document.getElementById(id));
const exerciseTest = createExerciseTest({
  now: () => Date.now(),
  permission: () => me.permissions.granted('access_exercise'),
  // Deliberately lazy: a missing native module must not break passive HR.
  load: () => require('exercise'),
});
// Read state only. This cannot create, resume, or stop an exercise.
const exerciseInfo = exerciseTest.inspect();
console.log('HR_PROBE runtime features: exercise state=' + exerciseInfo.state + '; valid=' + exerciseInfo.valid);
function testInput() { return {onWrist, paused}; }
function describe(value) {
  if (value === null) return 'null';
  if (value === undefined) return 'undefined';
  if (typeof value === 'number') return finiteNumber(value) ? String(value) : 'NaN';
  return typeof value;
}
function phoneLabel() {
  if (phoneUnavailable) return 'Phone API unavailable';
  try {
    return messaging.peerSocket.readyState === messaging.peerSocket.OPEN ? 'Phone connected' : 'Phone disconnected';
  } catch (_) {
    phoneUnavailable = true;
    return 'Phone API unavailable';
  }
}
function render() {
  const now = new Date();
  ui.clock.text = 'DIAG 0.1.9  ' + ('0' + now.getHours()).slice(-2) + ':' + ('0' + now.getMinutes()).slice(-2);
  ui.bpm.text = status === 'ok' ? String(latestBpm) : '--';
  ui.state.text = status.replace(/_/g, ' ');
  ui.link.text = phoneLabel() + (buttonTaps ? ' / taps ' + buttonTaps : '');
  ui.count.text = eventCount + ' events / ' + uniqueCount + ' unique / ' + validCount + ' valid';
  ui.raw.text = 'raw HR ' + describe(rawBpm) + ' / TS ' + (finiteNumber(rawTimestamp) ? 'number' : describe(rawTimestamp));
  ui.last.text = lastValidAt ? 'last ' + lastValidBpm + ' BPM / ' + Math.max(0, Math.floor((Date.now() - lastValidAt) / 1000)) + 's ago' : 'last valid: none';
  ui.wear.text = 'wear ' + (onWrist === null ? '?' : onWrist ? 'yes' : 'no') + ' / flips ' + wearChanges + ' / starts ' + sensorStarts;
  ui.runtime.text = (display.on ? 'on' : 'off') + ' / wakes ' + wakeCount + ' / ticks ' + ticks + ' / ' + Math.max(0, Math.floor((Date.now() - startedAt) / 1000)) + 's';
  ui['toggle-label'].text = paused ? 'Resume HR' : 'Pause HR';
  const test = exerciseTest.snapshot();
  if (test.failure) {
    ui.raw.text = test.failure.stage + ': ' + test.failure.name;
    ui.last.text = test.failure.detail.slice(0, 48);
    ui.wear.text = test.failure.detail.length > 48 ? test.failure.detail.slice(48, 96) : 'Fitbit state now: ' + test.failure.state;
  }
  if (test.owned && !test.failure) ui.link.text = 'Fitbit ' + test.nativeState + ' / events ' + test.stateEvents + ' / taps ' + buttonTaps;
  ui.exercise.text = test.phase === 'active' ? 'TEST ' + test.remainingSec + 's / ' + test.samples + ' reads / gap ' + test.maxGapSec + 's' : test.phase === 'starting' ? test.message + ' / ' + test.waitSec + 's' : test.phase === 'idle' && !exerciseInfo.valid ? 'Exercise state: ' + exerciseInfo.state : test.message;
  ui['exercise-label'].text = test.phase === 'starting' ? 'Cancel start' : test.phase === 'stopping' ? 'Stopping...' : test.owned ? 'Stop test' : test.phase === 'armed' ? 'Confirm recording' : 'Start 60s test';
}
function frame() {
  return { kind: 'hr', version: 1, source: 'sense2', sessionId, sequence,
    bpm: latestBpm, sensorTimestampMs: sensorTimestamp, observedAtMs: lastObservedAt,
    sentAtMs: Date.now(), ageMs: lastObservedAt ? Math.max(0, Date.now() - lastObservedAt) : 0,
    status, onWrist };
}
function publish() {
  render();
  if (phoneUnavailable) return;
  // Every native messaging operation is optional. A missing companion must not
  // prevent local sensor/display callbacks or the diagnostic timer from running.
  try {
    const peer = messaging.peerSocket;
    if (peer.readyState !== peer.OPEN || peer.bufferedAmount > 1024) return;
    peer.send(frame());
  } catch (_) { phoneUnavailable = true; render(); }
}
function invalidate(nextStatus) {
  status = nextStatus;
  latestBpm = null;
  lastObservedAt = 0;
}
function startSensor() {
  if (paused) return;
  if (onWrist !== true) { invalidate(onWrist === false ? 'off_wrist' : 'waiting_for_wear'); publish(); return; }
  if (hrm && !hrmRequested) {
    status = 'waiting';
    hrmRequested = true;
    sensorStarts++;
    hrm.start();
  }
}
if (!me.permissions.granted('access_heart_rate') || !me.permissions.granted('access_activity')) {
  status = 'permission_denied';
} else if (!HeartRateSensor) {
  status = 'unsupported';
} else if (!BodyPresenceSensor) {
  status = 'wear_detection_unavailable';
} else {
  hrm = new HeartRateSensor({ frequency: 1 });
  hrm.addEventListener('reading', () => {
    // Count callbacks separately from valid, unique readings. Raw values stay on
    // this diagnostic screen; they are never substituted for eligible frames.
    eventCount++;
    rawBpm = hrm.heartRate;
    rawTimestamp = hrm.timestamp;
    const unique = finiteNumber(rawTimestamp) && rawTimestamp >= 0 && (lastSensorTimestamp === null || rawTimestamp > lastSensorTimestamp);
    if (unique) { uniqueCount++; lastSensorTimestamp = rawTimestamp; }
    if (paused || onWrist !== true) { render(); return; }
    if (!finiteNumber(rawTimestamp) || rawTimestamp < 0) {
      invalidate('invalid_timestamp'); publish(); return;
    }
    if (!unique) { render(); return; }
    if (!finiteNumber(rawBpm) || rawBpm < 30 || rawBpm > 240) {
      invalidate('invalid_reading'); publish(); return;
    }
    sensorTimestamp = rawTimestamp;
    latestBpm = rawBpm;
    lastObservedAt = Date.now();
    lastValidBpm = rawBpm;
    lastValidAt = lastObservedAt;
    sequence++;
    validCount++;
    exerciseTest.reading();
    status = 'ok';
    publish();
  });
  hrm.addEventListener('error', event => {
    hrmRequested = false;
    invalidate('sensor_error');
    console.log('HR_PROBE sensor error: ' + event.error);
    publish();
  });
  body = new BodyPresenceSensor();
  body.addEventListener('reading', () => {
    const previous = onWrist;
    onWrist = typeof body.present === 'boolean' ? body.present : null;
    if (onWrist !== previous) wearChanges++;
    if (paused) { publish(); return; }
    if (onWrist !== true) {
      // Immediately reject streaming data. Keep an already-running acquisition
      // steady during this diagnostic so presence flapping cannot starve it.
      // Even raw events while off wrist never become eligible/forwardable HR.
      exerciseTest.stop('Wear lost');
      invalidate(onWrist === false ? 'off_wrist' : 'waiting_for_wear');
      publish();
    } else if (previous !== true) {
      invalidate('waiting');
      startSensor();
      publish();
    }
  });
  body.addEventListener('error', () => {
    onWrist = null;
    hrm.stop();
    hrmRequested = false;
    exerciseTest.stop('Wear detection failed');
    invalidate('wear_detection_error');
    publish();
  });
  body.start();
  startSensor();
}
function toggle() {
  buttonTaps++;
  if (!hrm) { render(); return; }
  paused = !paused;
  me.appTimeoutEnabled = paused;
  if (paused) { exerciseTest.stop('HR paused'); hrm.stop(); hrmRequested = false; invalidate('paused'); }
  else startSensor();
  publish();
}
function testButton() {
  buttonTaps++;
  const test = exerciseTest.snapshot();
  if (test.owned) exerciseTest.stop('User stopped');
  else if (test.phase === 'armed') exerciseTest.confirm(testInput());
  else exerciseTest.arm(testInput());
  publish();
}
ui['exercise-toggle'].addEventListener('click', testButton);
ui['exercise-label'].addEventListener('click', testButton);
// Best effort, synchronous cleanup only. A killed runtime cannot guarantee a stop.
me.onunload = () => exerciseTest.stop('Probe closed');
ui.toggle.addEventListener('click', toggle);
ui['toggle-label'].addEventListener('click', toggle);
display.addEventListener('change', () => {
  if (display.on) wakeCount++;
  // Display changes only expose state; they do not restart sensing or freshen it.
  publish();
});
try {
  if (!phoneUnavailable) {
    messaging.peerSocket.addEventListener('open', publish);
    messaging.peerSocket.addEventListener('close', render);
    messaging.peerSocket.addEventListener('message', event => {
      if (event.data && event.data.kind === 'ack') ui.link.text = 'Phone received #' + event.data.sequence;
    });
  }
} catch (_) { phoneUnavailable = true; }
setInterval(() => {
  ticks++;
  exerciseTest.tick(testInput());
  if (!paused && status === 'ok' && Date.now() - lastObservedAt > 5000) invalidate('stale');
  publish();
}, 1000);
render();
