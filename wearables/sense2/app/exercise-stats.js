// Comparison counters only. ExerciseStats has no HR acquisition timestamp.
// Polls and value changes must never be treated as fresh sensor samples.
export function createStatsDiagnostic(startedAt) {
  let polls = 0, validPolls = 0, changes = 0, timeAdvances = 0, errors = 0;
  let lastBpm = null, lastActiveTime = null, lastChangeAt = startedAt, maxSameMs = 0;
  let lastPollAt = 0, lastPollValid = false;
  function finite(v) { return typeof v === 'number' && v === v && v !== Infinity && v !== -Infinity; }
  function gap(time) { maxSameMs = Math.max(maxSameMs, Math.max(0, time - lastChangeAt)); }
  function observe(stats, time) {
    polls++; gap(time); lastPollAt = time; lastPollValid = false;
    try {
      const activeTime = stats && stats.activeTime;
      if (finite(activeTime) && activeTime >= 0) {
        if (lastActiveTime !== null && activeTime > lastActiveTime) timeAdvances++;
        lastActiveTime = activeTime;
      }
      const hr = stats && stats.heartRate;
      const bpm = hr && hr.current;
      if (!finite(bpm) || bpm < 30 || bpm > 240) { errors++; return; }
      validPolls++;
      lastPollValid = true;
      if (lastBpm === null || bpm !== lastBpm) {
        if (lastBpm !== null) changes++;
        lastBpm = bpm; lastChangeAt = time;
      }
    } catch (_) { errors++; }
  }
  function failed(time) { polls++; errors++; gap(time); lastPollAt = time; lastPollValid = false; }
  function snapshot(time) {
    // Returning counters only avoids persisting raw biometric values.
    return {polls, validPolls, changes, timeAdvances, errors,
      maxSameSec:Math.ceil(Math.max(maxSameMs, Math.max(0, time - lastChangeAt)) / 1000)};
  }
  function displayObservation() { return {bpm:lastPollValid ? lastBpm : null, polledAtMs:lastPollAt}; }
  return {observe, failed, snapshot, displayObservation};
}
