# Field findings

Everything learned on the car so far that is not obvious from the code.
Sample logs are in `docs/logs/` (this car: VIN ..DC401434, 24 kWh AZE0,
SOH ~66 %, Hx ~50 %, odometer ~180 000 km).

## Step test calibration (this car)

| run | file | pack T | SOC | cruise | R at +1 s | +2 s | +3 s | first sample |
|---|---|---|---|---|---|---|---|---|
| 10-11 09:36 | `Log_DC401434_261011_093626.csv` | 19 C | 70 % | 56 A / 371.4 V | stale (27) | 128 | 136 | mixed, 76 stale cells |
| 10-11 18:10 | `Log_DC401434_261011_181042.csv` | 21 C | 44 % | 45 A / 364.9 V | stale (14) | 122 | 144* | mixed, cells 1-20 fresh only |
| 10-12 12:54 | `Log_DC401434_261012_125454.csv` | 21 C | 79 % | 53 A / 376.5 V | - | 119 | 131 | clean (step spread over 2 samples) |
| 10-12 16:42 | `Log_DC401434_261012_164232.csv` | 25 C | 44 % | 52 A / 359.6 V | 104 | 110 | 113 | clean |
| 10-12 16:43 | `Log_DC401434_261012_164341.csv` | 26 C | 42 % | 46 A / 358.9 V | 99 | 105 | 108 | clean |

R = pack mOhm from the cruise sample to the high sample N seconds after
the step (full throttle 242-253 A). `*` current already falling.

- R depends on pack temperature more than anything else: 19 C -> ~130,
  21 C -> ~122, 25-26 C -> ~105 mOhm at +2 s. Compare runs only at the
  same temperature and the same hold time.
- R grows with hold time (128 -> 152 mOhm from 2 s to 7 s on 10-11
  09:36): ohmic + charge transfer by 1-2 s, diffusion adds ~15 % by 7 s.
  A 2-3 s hold is enough; the cell ranking is stable from 2 s on
  (correlation 2 s vs 7 s 0.76-0.84).
- Whether the first high sample is mixed is timing luck: 2 of 5 runs had
  the step land right after a read, so the next read was already settled.
- Weakest cells in every run: #17, #18, then #25, #61, #64, #73.
  Spread of per-cell R within +-10 % of the mean. Pack is even.
- Full throttle = 242-253 A at 337-354 V = 85-91 kW. The motor is power
  limited (~80 kW), so the amps at full throttle depend on pack voltage /
  SOC: verdicts use peak kW (>= 70), not amps. Step threshold 150 A.
- Per-cell R 1.0-1.7 mOhm depending on temperature, spread at peak 64-85
  mV vs ~26 mV at cruise. Min cell at full throttle 3.46-3.63 V.
- Since v0.7 the app's peak is the 3rd high sample (fixed ~3 s hold);
  before that it was the lowest pack V = last sample before release, so
  R depended on how long the pedal was held.

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
