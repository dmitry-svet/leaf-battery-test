# Field findings

Everything learned on the car so far that is not obvious from the code.
Sample logs are in `docs/logs/` (this car: VIN ..DC401434, 24 kWh AZE0,
SOH ~66 %, Hx ~50 %, odometer ~180 000 km).

## Step test calibration (this car)

| run | file | cruise | peak (app pick) | R pack | R / cell | peak kW | worst cell |
|---|---|---|---|---|---|---|---|
| 2026-10-11 09:36 | `Log_DC401434_261011_093626.csv` | ~50 A / 378 V | 245 A / 345 V | ~136 mOhm | ~1.4 | 85 | - |
| 2026-10-12 12:54 | `Log_DC401434_261012_125454.csv` | 33-36 A / 378.9 V | 233 A / 350.4 V | 130-134 mOhm | 1.36-1.39 | 85 | #17 -312 mV (3.624 V at peak) |

- Full throttle = 242-253 A at 340-354 V = 85-91 kW. The motor is power
  limited (~80 kW), so the amps at full throttle depend on pack voltage /
  SOC: verdicts use peak kW (>= 70), not amps. Step threshold 150 A.
- Per-cell R 1.23-1.51 mOhm, spread at peak 64-85 mV vs ~26 mV at cruise.
  Weakest group #17/#18, then #13, #25, #61, #64, #73, #82 (305-312 mV
  drop). Best #47 (-263 mV). Nothing outside 10 % of the mean: pack is
  even for its SOH.
- Min cell at full throttle 3.55-3.63 V.

## LBC cell-table refresh (probe 2026-10-12 07:59, `Probe_DC401434_261012_075935.csv`)

- `2102` read back to back takes ~0.39 s (cv 7 %). Comparing successive
  reads: cells change in 4-cell chunks, scanned 1 -> 96 continuously,
  ~150 cells/s, one full pass = ~0.65 s. There is no "all fresh" moment and
  no latch flag reachable from the OBD port.
- `2101` pack V lags the same way; the shunt current (`2101` p8-11) is
  instant.
- Consequence: the first sample after a current step mixes fresh and stale
  cells (shows as cells with ~0 mOhm). A snapshot is consistent only if the
  current was steady for >= 0.7 s before the read. The app's rule
  (`HOLD`=2, peak = high sample with the lowest pack V, never the first
  high sample unless it is the only one) satisfies this; an external
  analyzer should drop the first sample after any step > ~50 A.
- 2026-10-12 run had no mixed snapshot because the step spread over two
  samples (101 A, then 243 A).

## 0x1DB `LB_Voltage_Latch_Flag` (bit 24)

Lives on EV-CAN only (OBD pins 12/13); the ELM327 sits on car-CAN (pins
6/14) and never sees 0x1DB. Reading it would need a cable or jumper
6<->12, 14<->13 and `ATMA` with `ATCRA1DB`. Not pursued.

## LeafSpy (same dongle, same car)

- Log location on the phone (Xiaomi 2201117SY, LeafSpy Pro):
  `/sdcard/Android/data/com.Turbo3.Leaf_Spy_Pro/files/LOG_FILES/` (also
  `CHARGES/`, `TripChrgLog_*.csv`). Old path `/sdcard/LEAF_BT_CAN/LOG_FILES`
  is empty.
- Negative cell values in a LeafSpy log mark "shunt on"; take the absolute
  value.
- Row cadence is its polling cycle length (7.5 s on this car, 4.5 s on
  another car, `LeafSpy_FC313300_261009_othercar.csv`), not a BMS sync:
  it reads the cell table with no wait after `2101` and also shows mixed
  snapshots at steps (5 of 55 steps in the other car's log, 24-32 lagging
  cells).
- Sample interval regularity (cv = std/mean): LeafSpy 7.6 s / 4.5 s
  (~35 %), LeafDash 5 s (91 %, BT gaps), this app's test log 1.0 s
  (22 %), probe 0.39 s (7 %).

## Phone access

- Wireless adb: pair with the code from Developer options, then connect to
  the port shown there (changes every time):
  `adb pair <ip>:<pair-port> <code>`, `adb connect <ip>:<port>`. The mDNS
  entry shows up as a second device: pass `-s <ip>:<port>`. Phone IP was
  192.168.31.23.
- Logs the user sends via Telegram Desktop land in
  `~/Downloads/Telegram Desktop/`.

## Not used yet

- `2102` response is 196 bytes: 96 cells (2 bytes each) + pack V / 100 at
  [192..193] + bus V / 100 at [194..195] (per OVMS). Could give dV from the
  same read as the cells.
- LeafDash (`../leaf_dash`) still has the old `PowerTest` (250 A step,
  270 A floored): port the 150 A / 70 kW / deepest-sag changes if the step
  test there matters.
