# iSight

**A second pair of eyes for blind and low-vision travellers — runs entirely on a chest-mounted Android phone, fully offline, zero recurring cost.**

It watches the world, decides what matters, and tells you through spatial sound and vibration — never the screen. You can also just talk to it.

---

## Why it exists

A white cane only tells you what's touching the ground right in front of your feet. It misses: people crossing your path, steps going down, head-height obstacles, signs, and where you last put something down.

iSight is a **cane complement, not a replacement**. Core rule: **the AI is never the final word on safety.** Ask "is it safe to cross?" and it will never say yes — that question is blocked from every language model in the system.

---

## What it does

| Feature | Summary |
|---|---|
| Obstacle awareness | Names objects ahead, distance, direction, whether they're approaching — as spatial sound |
| Drop-off detection | Detects steps/stairs down before your foot reaches the edge (5 independent checks) |
| Specular Trap fix | Ignores puddles/shadows/shiny floors that look like cliffs (3 physics checks) |
| Overhead hazards | Detects branches, open cabinets, poles at head height |
| Voice commands | "What's ahead?", "find a chair", "read that sign", "where's my phone?"|

---

## How it works

```
Camera frame
   ↓
PERCEPTION — object detector + depth model
   ↓
FUSION — direction, proximity, approach rate per object + confidence tier
   ↓
HAZARDS — drop-off state machine, Specular-Trap vetoes, overhead detection, camera-health checks
   ↓
TARGET — picks the one most important thing to alert you to right now
   ↓
OUTPUT — sound (pan/rate/timbre), haptics, earcons, speech
```

Running alongside: motion sensors (activity detection), barometer (descent check), microphone (sirens/commands), thermal governor, voice assistant, and the safety gate.

### Sound/haptic channels (never mixed)

| Dimension | Channel |
|---|---|
| Direction | stereo pan |
| Distance/urgency | pulse rate (faster = closer) |
| Identity | timbre (sound icon or spoken word) |
| Proximity | graded haptics |
| Confidence | sound texture (never fakes certainty) |

### The Safety Gate

Language models tend to answer "yes, go ahead" even over a live hazard warning — so they're never allowed to answer "is it safe?" directly. Six layers enforce this:

1. A tiny (37KB) n-gram classifier catches the question in English/Hindi/Kannada
2. A fast path for obvious phrasing
3. A fixed template response built from real sensor data — never says "safe" as a yes
4. 1.5s hazard memory so a flickered warning still counts
5. Any LLM reply is scanned and a "you can go" answer is discarded
6. The LLM's own prompt also tells it to refuse

*(Real test: Gemma-1B once said "you see nothing there" while a book, person, and furniture were all detected — hence the gate.)*

Full details: [`ISIGHT_BIBLE.md`](ISIGHT_BIBLE.md)

---

## Status

| Area | State |
|---|---|
| Vision pipeline (NPU) | Working on iQOO 15 |
| Vision pipeline (CPU/MOCK) | Working, falls back safely |
| Sonification | Live, tested |
| Drop-off detection | Live, tested — not yet field-tested on real drops |
| Specular-Trap | Live, tested — needs real puddle/shadow footage |
| Activity context system | Live, tested |
| Context auto-detection | Live — thresholds need real-world testing |
| Thermal governor | Live — full soak test pending |
| Voice — intent grammar | Live, tested |
| Voice — LLM fallback | Works, weak — long-tail helper only |
| Safety gate | Live — 100% on held-out test phrases |
| Panic gesture | Live |
| Sign reading + translation | Live — needs one-time model download |
| Blind-first UI | Live — needs hands-on testing with a blind user |
| Hazard sound detection | Live — thresholds not field-validated |

---

## Future scope: 3D home/workspace model

Currently a demo only (laptop-side). Long-term direction: a **persistent, on-device 3D map** of places a user lives/works, built once via a guided walk-through.

**Unlocks:**
- Persistent object memory ("where are my keys?")
- Indoor routing around known furniture
- Zone awareness (kitchen vs hallway)
- Change detection (new obstacle vs known layout)
- Fewer false drop-off alerts

**Gap:** Reconstruction demo works; persistence, labelling, on-phone execution, and routing are not built yet.

---

## Quick start

Requires Android Studio (Koala 2024.1+) or cached Gradle 8.13.

```bash
cd android
export JAVA_HOME="/path/to/Android Studio/jbr"

gradle :app:assembleDebug :app:testDebugUnitTest   # works with no model files (MOCK mode)
gradle :app:installDebug
```

**Build flags:**

| Flag | Adds |
|---|---|
| `-PenableQnnNative=true` | Hexagon NPU path (needs Qualcomm QAIRT SDK) |
| `-PenableLlm=true` | On-device Gemma + MediaPipe (~120MB); model itself is side-loaded (~530MB) |
| `-PenableSherpa=true` | Offline keyword-spotting ASR |

Engine switch: `inference/EngineConfig.kt` → `Kind.MOCK / TFLITE / QNN`

### Laptop 3D room demo

```bash
cd laptop
pip install -r room3d/requirements.txt
python -m room3d.record --source phone:<IP> --out sweep.mp4 --seconds 120
python -m room3d.app --source file:sweep.mp4
```

Phone serves frames at `http://<phone-ip>:8085/frame.jpg` (same Wi-Fi, no internet).

---

## Repo layout

```
android/    Kotlin app (inference, sonification, context, voice, perception, sensors, ar, ui)
laptop/     room3d demo (Python/Open3D) + safety-gate training tool
ISIGHT_BIBLE.md   full technical documentation
debug_*.py  offline validation scripts
```

---

## Design rules (do not break)

1. No runtime network dependency
2. Depth is relative (0–1), never metres, in the live cue path
3. Direction = pan, distance = pulse rate, identity = timbre — no overlap
4. Haptics are a primary channel, not a last-resort alert
5. Confidence is derived from real signal, never faked
6. Every degradation path stays functional — never silent
7. The LLM never answers "is it safe" — six layers enforce this
8. Many small independent checks vote, rather than one big model

---

## Not included in repo

- Model files (`.tflite`/`.bin`) — regenerate via `convert.py`
- Qualcomm QNN runtime `.so` (proprietary)
- Gemma `.task` model (~530MB, side-loaded only)
- Other generated/large files (`.onnx`, `.ply`, `venv/`, build output)

---
