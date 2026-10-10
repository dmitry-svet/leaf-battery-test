# Leaf Battery Test

Android app for a **Nissan Leaf AZE0 (2013-2017, 24/30 kWh)** with a cheap
Bluetooth ELM327 dongle. One screen: connect, see the car's identity and
live pack readings, run a guided **full-throttle step test** that measures
the battery's internal resistance and shows which cell sags most, and
record a **LeafSpy-format data log** with all 96 cell voltages.

Spun out of [LeafDash](https://github.com/dmitry-svet/leaf-dash) (same
ELM327 driver and decoders, without the economy/trip features).

## Screen

- Status + Connect/Disconnect (auto-reconnects to the last dongle).
- VIN, SOH, Hx, odometer (km, from the meter ECU), pack temps, outside temp.
- Big live cards: pack A, pack V, km/h, lowest cell V (~1.5 s refresh).
- Test prompt / countdown / verdict, "Restart test".
- "Start log / Stop log" and "Send log".

## The step test

1. Cruise at ~60 km/h with a steady pedal so the pack current sits in
   **30-60 A**. The screen counts down **10..1**.
2. **FLOOR IT.** At the first sample >= 250 A the screen says **RELEASE**.
3. Verdict:
   - regen (negative current) between cruise and the step -> run void;
   - more than one intermediate sample between 60 A and 250 A -> pedal not
     pressed abruptly;
   - fewer than 4 cruise samples -> too little time before the step;
   - peak below 270 A -> not floored;
   - otherwise **OK**: `R = dV / dI` between the cruise and peak samples,
     in pack mOhm and per cell, plus the cell with the biggest
     cruise-to-peak drop and the lowest cell at the peak.

## Data log

LeafSpy 0.52 column layout (161 columns, same labels, order and value
formats, so LeafSpy log tools read it): SOC/AHr (x10000), pack V and A,
max/min/avg cell mV and diff, **CP1..CP96**, pack temps (sensors 1/2/4),
Hx, SOH, odometer, ambient, speed, phone battery, epoch, Power SW. Fields
this app does not read (GPS, gids, 12 V, tires, power by consumer, charge
counters, RPM, torque) are blank.

Files: `Android/data/com.svet.leafbatterytest/files/LOG_FILES/
Log_<VIN last 8>_YYMMDD_HHmmss.csv`, one per Start..Stop. "Send log" hands
the running or latest file to Telegram (if installed) or the share sheet.

## What is read, and how

Active ISO-TP to the battery controller (`0x79B` -> `0x7BB`): `2101` pack V
(p20-21 / 100), pack A (p8-11 signed / 1024, sign flipped so + =
discharge), Ah (p35-37 / 10000), SOC (p31-33 / 10000); `2102` 96 cells x 2
bytes mV; `2104` temps; `2161` Hx (p2-3 / 102.4) and SOH (p4-5 / 100).
VCM `0x797`: VIN (`21 81`). Meter `0x743`: odometer km (`21 01`, p9-11,
flow control block size 1). Broadcasts via hardware filter: `0x284`
speed ((B4<<8|B5) / 100), `0x510` ambient (B7 * 0.5 - 40).

Decodes come from public sources (OVMS, dalathegreat, OBDb) and were
checked against the car; Hx uses /102.4 because that matches LeafSpy.

## Build

Requires JDK 17 and the Android SDK (`local.properties` -> `sdk.dir`).

```
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Release signing reads a gitignored `keystore.properties`; without it the
release build is unsigned.
