# CLAUDE.md — Leaf Battery Test

Android (Compose) app for a Nissan Leaf AZE0 via ELM327 Bluetooth SPP: a
guided full-throttle step test that measures the pack's DC internal
resistance and shows which cells sag, plus a LeafSpy-format data log.
Spun out of LeafDash (`../leaf_dash`): same ELM327 driver, decoders and log
writer, no economy/trip tracking.

## Build & test

```
export JAVA_HOME=~/tools/jdk-17.0.19+10
export ANDROID_HOME=~/android-sdk
./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1g -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest :app:assembleRelease
```

The low-memory flags matter on this machine (12 GB, swap full: default
builds thrash). Ship `app/build/outputs/apk/release/app-release.apk` (phone
has the release-signed build; a debug APK conflicts on install). Verify the
version with `~/android-sdk/build-tools/*/aapt2 dump badging <apk>`.

## Conventions

- TDD for pure logic (`can/`, `obd/`, `log/`, `power/`): failing test first.
  UI/BT (Compose, `BtSppTransport`, ViewModel, `BatteryPoller`) has no test
  infra - keep logic out of it.
- Every user-visible change: bump `versionCode`/`versionName` in
  `app/build.gradle.kts`, rebuild, commit + push to origin/main directly
  (personal repo - no task branches, no BID prefix, no Co-Authored-By).
- Update README.md in the same commit as behavior changes.
- `LeafSpyLog.HEADER` mirrors a real LeafSpy 0.52 log byte for byte (161
  columns, `" Speed1"` with its leading space, `"Debug"` twice): tools that
  read LeafSpy logs depend on it. Do not tidy it.

## Architecture notes

- `BatteryPoller`: every cycle LBC `2101` (pack A/V) + `2102` (96 cells) +
  speed `0x284` (~1.5 s); until known and then every 20th cycle: VIN
  (`0x797 21 81`), `2161` (SOH/Hx), `2104` (temps), meter odometer
  (`0x743 2101`, past the declared ISO-TP length, flow control block size
  1), ambient `0x510`. A slow read failing 3 times is skipped for the session.
- `PowerTest` state machine: cruise 30-60 A -> 10 s countdown -> FLOOR
  (`detail` = live A, target, seconds left) -> `HOLD`=2 samples >= 150 A
  (the first high sample's pack V is stale, ~20 V too high) = RELEASE ->
  grade. Verdicts: regen before the step voids the run; >1 intermediate
  sample = not abrupt; <4 cruise samples = too little time; peak A*V <
  70 kW = not floored (full throttle on this car = ~245 A / 345 V = 85 kW,
  current is power-limited so an amps threshold would be SOC-dependent);
  else R = dV/dI (pack mOhm, per cell) plus the cell with the biggest
  cruise-to-peak drop. Peak = high sample with the LOWEST pack V, never
  the first high sample unless it is the only one: the LBC reports the
  shunt current instantly but pack V and the cell table lag, and the cell
  table refreshes in 12-cell groups, so the first post-step sample mixes
  fresh and stale cells (seen as cells with 0 mOhm).
- Pack current from `2101` p8-11 (signed / 1024 A, raw sign flipped so + =
  discharge). Hx = raw / 102.4 (matches LeafSpy; public decoders say /100).
- Data log: `LOG_FILES/Log_<VIN last 8>_YYMMDD_HHmmss.csv`, one per test:
  "Start test" opens it (and arms `PowerTest` from READY), the verdict
  closes it, "Send log" shares it via FileProvider (Telegram if installed).
  LeafSpy 0.52 column layout.
- ELM327 reads block forever: `BatteryPoller` has a 30 s watchdog that
  closes the transport; the activity auto-reconnects every 10 s.

## Lessons learned (from LeafDash)

- ELM327 `ATMA` on cheap clones drops frames: read broadcasts via hardware
  filter (`ATCRA<id>` + `ATMA`), one id at a time, restore `ATCRA7BB` after.
- A bare CR at the ELM prompt repeats the last command - never send one
  there (see `Elm327.readBroadcastTimed`).
- `IsoTp.reassemble` trims to the declared length by default; the meter's
  odometer lies past it (`trim = false`).
