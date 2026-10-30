import * as document from 'document';
import { HeartRateSensor } from 'heart-rate';
import { BodyPresenceSensor } from 'body-presence';
import { me } from 'appbit';
import { display } from 'display';
import * as messaging from 'messaging';

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
const ui = {};
['bpm', 'state', 'link', 'count', 'clock', 'raw', 'last', 'runtime', 'wear', 'toggle', 'toggle-label']
  .forEach(id => ui[id] = document.getElementById(id));
function describe(value) {
  if (value === null) return 'null';
  if (value === undefined) return 'undefined';
  if (typeof value === 'number') return Number.isFinite(value) ? String(value) : 'NaN';
  return typeof value;
}
function render() {
  const now = new Date();
  ui.clock.text = 'DIAG 0.1.2  ' + ('0' + now.getHours()).slice(-2) + ':' + ('0' + now.getMinutes()).slice(-2);
  ui.bpm.text = status === 'ok' ? String(latestBpm) : '--';
  ui.state.text = status.replace(/_/g, ' ');
  ui.link.text = messaging.peerSocket.readyState === messaging.peerSocket.OPEN ? 'Phone connected' : 'Phone disconnected';
  ui.count.text = eventCount + ' events / ' + uniqueCount + ' unique / ' + validCount + ' valid';
  ui.raw.text = 'raw HR ' + describe(rawBpm) + ' / TS ' + (Number.isFinite(rawTimestamp) ? 'number' : describe(rawTimestamp));
  ui.last.text = lastValidAt ? 'last ' + lastValidBpm + ' BPM / ' + Math.max(0, (Date.now() - lastValidAt) / 1000).toFixed(0) + 's ago' : 'last valid: none';
  ui.wear.text = 'wear ' + (onWrist === null ? '?' : onWrist ? 'yes' : 'no') + ' / flips ' + wearChanges + ' / starts ' + sensorStarts;
  ui.runtime.text = (display.on ? 'on' : 'off') + ' / wakes ' + wakeCount + ' / ticks ' + ticks + ' / ' + Math.max(0, Math.floor((Date.now() - startedAt) / 1000)) + 's';
  ui['toggle-label'].text = paused ? 'Resume stream' : 'Pause stream';
}
function frame() {
  return { kind: 'hr', version: 1, source: 'sense2', sessionId, sequence,
    bpm: latestBpm, sensorTimestampMs: sensorTimestamp, observedAtMs: lastObservedAt,
    sentAtMs: Date.now(), ageMs: lastObservedAt ? Math.max(0, Date.now() - lastObservedAt) : 0,
    status, onWrist };
}
function publish() {
  render();
  if (messaging.peerSocket.readyState !== messaging.peerSocket.OPEN || messaging.peerSocket.bufferedAmount > 1024) return;
  // Heartbeats never increment the sensor sequence or refresh observation time.
  try { messaging.peerSocket.send(frame()); }
  catch (error) { console.log('HR_PROBE watch send failed: ' + error.message); }
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
    const unique = Number.isFinite(rawTimestamp) && rawTimestamp >= 0 && (lastSensorTimestamp === null || rawTimestamp > lastSensorTimestamp);
    if (unique) { uniqueCount++; lastSensorTimestamp = rawTimestamp; }
    if (paused || onWrist !== true) { render(); return; }
    if (!Number.isFinite(rawTimestamp) || rawTimestamp < 0) {
      invalidate('invalid_timestamp'); publish(); return;
    }
    if (!unique) { render(); return; }
    if (!Number.isFinite(rawBpm) || rawBpm < 30 || rawBpm > 240) {
      invalidate('invalid_reading'); publish(); return;
    }
    sensorTimestamp = rawTimestamp;
    latestBpm = rawBpm;
    lastObservedAt = Date.now();
    lastValidBpm = rawBpm;
    lastValidAt = lastObservedAt;
    sequence++;
    validCount++;
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
    invalidate('wear_detection_error');
    publish();
  });
  body.start();
  startSensor();
}
function toggle() {
  if (!hrm) return;
  paused = !paused;
  me.appTimeoutEnabled = paused;
  if (paused) { hrm.stop(); hrmRequested = false; invalidate('paused'); }
  else startSensor();
  publish();
}
ui.toggle.addEventListener('click', toggle);
ui['toggle-label'].addEventListener('click', toggle);
display.addEventListener('change', () => {
  if (display.on) wakeCount++;
  // Display changes only expose state; they do not restart sensing or freshen it.
  publish();
});
messaging.peerSocket.addEventListener('open', publish);
messaging.peerSocket.addEventListener('close', render);
messaging.peerSocket.addEventListener('message', event => {
  if (event.data && event.data.kind === 'ack') ui.link.text = 'Phone received #' + event.data.sequence;
});
setInterval(() => {
  ticks++;
  if (!paused && status === 'ok' && Date.now() - lastObservedAt > 5000) invalidate('stale');
  publish();
}, 1000);
render();
