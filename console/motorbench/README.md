# STRIDE Motor Bench SIM

Standalone Android 5.1/API 22-compatible **simulator**, version `0.1.0-simulator`,
package `dev.stride.motorbench`. It is separate from the Workout Lab preview and
the original STRIDE motor application. It has not been installed on the treadmill.

This is the next development step toward manual motor integration. It does not
enable physical speed or incline control. Every visible control addresses an
in-memory `SimulatedBoard`. There are no network, Bluetooth, boot or USB attach
handlers. The application requests no Android permissions.

## Try the simulator

1. Connect simulator, then toggle **Simulator key IN**.
2. Wait for three stationary replies spanning at least 400 ms; press **ARM**.
3. Within ten seconds, press **Start 0.5 MPH** separately.
4. Observe mode request, independent mode confirmation, speed target ACK and
   simulated measured acceleration as separate stages.
5. Change speed by 0.1 MPH or grade by 0.5% per press. The controller limits manual
   changes to one per 500 ms and waits for the previous measured value to catch up.
6. Press **STOP SIMULATED MOTION**. Target zero is acknowledged first; measured
   deceleration and stationary feedback must follow before the controller returns
   to READY. Another start requires arming again.

The moving-speed cap is 1 MPH (encoded conservatively as at most 1.60 km/h), with
a 0–2% incline envelope. Reported board limits further restrict those values.
Stop requests hold the last fresh measured incline rather than automatically
lowering the deck. This behavior is a simulator policy, not a validated physical
incline-stop mechanism.

Try Drop replies, Corrupt replies, Freeze actuals, key removal, and leaving the
activity. Faults latch, never automatically restart motion, and make at most one
best-effort stop attempt per run. An ACK never proves that motion has stopped.
Returning to the activity does not automatically arm or resume it.

## Isolation and protocol

`MotorWire` implements the inspected FitPro1 framing, bit masks, checksum,
signed grade encoding and vendor truncation. Metadata commands 129, 130, 132 and
136 and command 2 values are allowed. There are no builders for connect, unlock,
reset, calibration or firmware commands. Writes are limited to speed, grade and
mode; mode and motion use separate transactions.

`BenchController` checks model 14116 / part 388129, reported supported fields and
limits, fresh independent status replies, stationary arming, mode confirmation,
motion confirmation, unexpected targets and the bench envelope. The 750 ms age
uses receipt time: it is not proof of sensor acquisition freshness. These checks
have only been exercised with a simulator.

`LockedUsbLink` is prepared transport code and is **never constructed by the UI**.
If explicitly constructed by future code, it accepts only the known controller
VID/PID and interface/endpoints, requires an existing per-device USB grant, and
does not force a claim or detach a kernel driver. Before any transfer, immutable
`HardwareGate.LIVE_MOTOR_WRITES = false` rejects every control write, including
zero-speed Stop. There is no preference, intent extra or runtime switch to remove
that lock. The controller also refuses to arm a physical link.

This implementation cannot substitute for the physical safety key. Actual mode
behavior, motor responses, startup requirements, controller ownership and feedback
validity remain unverified on the treadmill. A stalled controller process cannot
provide an independent hardware watchdog. Live integration needs further design
and staged validation, not merely changing the build constant.

## Validation

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

33 JVM tests pass: ten protocol/gate tests and 23 controller tests. They cover
golden packets, corrupt/short/wrong replies, signed grade, absent values, forbidden
commands, physical write rejection, identity/limits, stationary timing, arming
expiry/cancellation, mode confirmation, missing actual motion, rate/cap checks,
stop confirmation, key removal, unexpected disarmed motion, overspeed/grade,
clock discontinuities, lifecycle exit and fault recovery without auto-resume.

Build and lint pass (zero lint errors; eight diagnostic UI/orientation/icon
warnings). The APK's v1/v2 signatures verify with minimum SDK 22. No API 22 runtime
or physical motor validation has been completed for this module.

An API 36 emulator at the console's 800×480 / 160 dpi resolution was also used
to inspect the native screen and exercise connect → key insertion → arm → start
→ measured simulated motion → Stop → stationary READY. This verifies the UI
sequence on that emulator, not Android 5 hardware behavior.

## Physical work still outstanding

With an operator present, belt clear and safety key initially removed:

- Repeat read-only identification/status after a cold boot, establish exclusive
  USB ownership, and verify that refusal/security errors cause no automatic unlock.
- Validate actual speed/incline fields against independently observed motion and
  establish their update cadence, including disconnect/stall behavior.
- Establish the board's mode/key/initialization semantics before permitting any
  positive motor command in a separate explicitly reviewed build.
- Verify independent emergency stopping, loss of app/transport behavior, bounded
  manual actuation and incline hold semantics before integrating workout proposals.

Heart-rate adaptation remains disconnected from this module. The Sense 2 exercise
readout has unknown acquisition age, and the phone companion is not installed.
