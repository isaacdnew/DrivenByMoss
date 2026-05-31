# APC40 MkII Clip-Launcher Audio Overdub — Architecture & Implementation Plan

> Status: design agreed; implementation not yet started (branch `fancy-apc40mkii-looper`).
> Goal: add **audio** overdub looping to the APC40 MkII clip launcher. Bitwig has no native
> audio-clip overdub, so we emulate it with a group of tracks that the script orchestrates.

## 1. Guiding principles

- **No runtime routing.** The Bitwig controller API (extension-api v21) cannot read or set audio
  routing — `SourceSelector` only toggles the *already-assigned* audio/note input on/off; there is
  no `AudioInput`/`RoutingDestination` class. So the entire signal topology lives in a user-built
  **project template**. The script only *arms, records, duplicates, renames, launches, stops, and
  measures*.
- **Infinite via duplication.** Every layer is a `duplicate()` of an empty spawn track. Bitwig's
  duplicate preserves input + output routing and monitor mode (but **not** arm state — the clone is
  always unarmed, so we arm it explicitly). Unlimited layers, zero scripted routing.
- **Localized changes.** All looper logic lives in a new `LooperManager` service consulted by the
  existing APC `SessionView` and a new command on the SOLO row. The generic framework classes are
  not forked.
- **Background banks.** The looper uses its *own* flat track bank and its *own* cursor clip, so its
  bookkeeping never disturbs the user's visible track bank / device focus.

## 2. Project-template layout (user-built, validated by the script)

One **group track** = one APC **column** = one logical looper "voice". Children of the group:

| Child track | Role |
|---|---|
| **Monitor / source track** | Live monitoring ON; it is the audio source the layers record from. Re-patching the audio interface only ever touches this one track. Summed into the group output so you always hear yourself. |
| **Spawn template track** | Empty, monitor OFF, pre-wired to record from the monitor track. Never recorded into directly; it is the thing we duplicate. |
| **Layer tracks** | Duplicates of the spawn, each holding one recorded clip. Monitor OFF (they still record fine); their clips play back into the group sum. |

The monitor track is **inside** the group specifically so its existence can be validated.
Group output = monitor (live) + all playing layer clips.

## 3. Component map

New package `de.mossgrabers.controller.akai.apc.looper`:

| Class | Responsibility |
|---|---|
| `LooperManager` | Top-level service. Owns the flat bank + cursor clip + boundary scheduler. Scans/validates looper groups, holds a `LooperVoice` per column, exposes the API the view/command call. |
| `LooperVoice` | One column = one group. Refs to group, monitor track, spawn track, the 5 `LoopSlot`s, and the per-column overdub-mode flag. |
| `LoopSlot` | One scene/pad = one independent loop. Layer list, `wordLengthInBeats`, `storyLengthInBeats`, phase origin, active flag, `RecordingState`. |
| `LayerInfo` | One layer: track ref, scene index, length (in words). |
| `OverdubMode` enum | `AUTO_STOP`, `AUTO_ADD_LAYER`, `CONTINUOUS` (default). |
| `OverdubSpan` enum | `WORD`, `STORY`. |
| `RecordingState` enum | `IDLE`, `WAIT_START`, `RECORDING`, `WAIT_STOP`, `PLAYING`. |
| `OverdubCommand` | `TriggerCommand` bound to ROW2 (SOLO). Looper column → toggle overdub mode; otherwise → delegate to stock `SoloCommand`. |

Modified files:
- `APCControllerSetup.java` — build `LooperManager`, rewire ROW2 to `OverdubCommand`, hand the manager to the view.
- `view/SessionView.java` — delegate `onGridNote`, `getPadColor`, and the delete combo for looper columns.
- `APCConfiguration.java` — add the settings.

## 4. Terminology — word / story

- **word** = the loop's **first** layer's measured loop length. Immutable for the loop's life
  (LIFO deletion removes it last). It is the `WORD` option of the selectable auto span.
- **story** = the **current/compound** loop length = `max` over the current layers' lengths.
  It **grows** when a `CONTINUOUS` overdub runs longer than the current loop, and **reverts**
  (recomputed `max`) when a layer is deleted. Always a whole number of words.

Because every layer's length is an integer multiple of the story at its record time, the story stays
divisible by every layer's length ⇒ permanent phase-lock across all layers and across deletes.

## 5. Track-structure resolution & validation

- After the model exists (in `createModel`), `LooperManager` creates a **dedicated flat track bank**
  via `HostImpl.getControllerHost().createTrackBank(N, 0, numScenes, true)` (the `true` =
  flat list, which includes nested children).
- **Hierarchy reconstruction:** walk the flat bank; a track with `isGroup()` whose name contains the
  configured *group substring* starts a voice; following tracks with `hasParent()` are its children.
  `Track.createParentTrack(...)` (raw Bitwig) confirms a child's owning group robustly.
- **Child identification by name:** the child named per the *monitor name* setting → monitor track;
  the empty child named per the *spawn name* setting → spawn track; any other child with a clip → a
  layer (its scene = the slot index that has content).
- **Validation (structure + names only):** group present & is a group; exactly one monitor and one
  spawn track by name; spawn empty + `canHoldAudioData()`. On failure → mark the column
  `MISCONFIGURED`, paint a distinct pad color, `host.showNotification(...)`. **Routing cannot be
  validated** (API limit) — documented as user responsibility.
- Re-validate on a track-bank-change observer so structural edits update live.

## 6. The spawn → layer lifecycle (hides async duplication)

`duplicate()` is asynchronous — the clone appears in a bank a host round-trip later. We hide that
latency: **duplicate the empty spawn at overdub *start*, record into the *current* spawn, and let the
duplicate resolve during the (≥ one-loop-long) recording.**

Per overdub:
1. **Mint next spawn (async, non-blocking):** `spawn.duplicate()`. A one-shot bank observer captures
   the new empty child as the *new* spawn and renames it to the exact spawn name.
2. **Record into the current spawn:** arm it (arm isn't copied), then `slot.startRecording()` at the
   start boundary.
3. **On stop:** rename the just-recorded track `Layer N` (`IItem.setName`), disarm, set its loop
   length precisely (§7), register a `LayerInfo`. By now step 1's duplicate is the new pristine spawn.

This keeps the recording target always-known (no blocking wait), overlaps the round-trip with the
recording time, and leaves exactly one empty spawn at all times. The first layer of a new loop uses
the same flow with free length + manual stop.

## 7. Length measurement

- Uses a **dedicated** `CursorTrack` + `createLauncherCursorClip` (not the UI's): point it at the
  just-recorded slot, read `IClip.getLoopLength()` (beats) after a round-trip.
- Only the **first** layer truly needs measuring (to learn `word`). Overdub layers have a known
  target length, enforced with `IClip.setLoopLength()`.

## 8. Transport boundary scheduler

Authority for quantized start/stop. `scheduleTask` is millisecond-based and tempo can change, so we
**poll** rather than precompute delays:

- A lightweight periodic tick (`host.scheduleTask` re-arming every ~15–25 ms) reads
  `transport.getPosition()` (beats) and `getQuartersPerMeasure()`.
- Each active `LoopSlot` records its **phase origin** (the beat it was (re)launched at).
  Next boundary = `origin + ceil((now − origin) / story) · story`.
- The scheduler drives `WAIT_START → RECORDING` and `WAIT_STOP → PLAYING` on boundary crossings.
- **Assumption:** launcher clips advance only while the transport runs. The looper assumes transport
  is running; optionally auto-start it on first record (configurable).

## 9. Recording state machine (per LoopSlot)

`IDLE → WAIT_START → RECORDING → (WAIT_STOP) → PLAYING`

- **New loop (empty pad, overdub on):** start (optionally bar-quantized), free length, `RECORDING`
  until the pad is pressed again → stop → measure → `word = story = measured` → `PLAYING`.
- **AUTO_STOP (a):** start at next boundary; record exactly one **span** (`WORD`→1×word,
  `STORY`→1×story); auto-stop at that boundary; `setLoopLength(span)`; recompute story.
- **AUTO_ADD_LAYER (b):** like (a), but each stop immediately mints + starts the next layer at the
  boundary; repeats until overdub toggled off (or pad pressed). Each layer = one span.
- **CONTINUOUS (c, default):** start at next boundary; record freely; pad press → `WAIT_STOP`, stop
  at the **next** boundary; layer length = whole number of stories elapsed (≥1). If it exceeds the
  current story, **story grows** to that length; word unchanged; recompute story = max.

## 10. Playback, launch & phase-lock

- **Launch a loop (scene S):** at the next global boundary, launch slot S on every layer track of
  that `LoopSlot` simultaneously; record the shared phase origin. Per-clip loop lengths are
  word-multiples, so they stay phase-locked indefinitely.
- **One active pad per column:** before launching scene S, **explicitly stop** the layer tracks of
  the column's other scenes (separate tracks, so Bitwig won't auto-stop them). Track the active scene
  per voice.
- Overdub layers join the playing loop on the same boundary grid, so new material aligns from pass 1.

## 11. Delete-last-layer

The existing delete-slot combo, when the column is in overdub mode, calls
`LooperManager.deleteLastLayer(col, scene)`: `track.remove()` on the newest `LayerInfo` (LIFO), then
recompute story = max of survivors (reverts if that layer had extended it). Deleting the last
remaining layer tears down the loop (word/story cleared). Outside overdub mode, delete is stock.

## 12. UI integration

- **`onGridNote`:** if `manager.isLooperColumn(col)`, route to `manager.handlePad(col, scene, pressed)`
  and consume; else stock path. (The group track is never armed, so we fully own looper-column
  behavior.)
- **`getPadColor`:** for looper columns, `manager.getPadColor(col, scene)` aggregates the scene's
  layers — recording > queued > playing > has-content (≥1 layer) > overdub-armed > empty, plus a
  `MISCONFIGURED` color. Reuses the color fields from `AbstractSessionView.getPadColor`.
- **ROW2 / SOLO → `OverdubCommand`** (replaces `SoloCommand` at `APCControllerSetup` ROW2 wiring):
  looper column → toggle overdub mode (LED reflects it); otherwise forward to `SoloCommand`.

## 13. Settings (APCConfiguration)

Following the existing `activate…Setting` pattern:
- Text: **Looper group name** (substring), **Spawn track name**, **Monitor track name**.
- Enum: **Default overdub mode** = {Auto-stop, Auto-add-layer, Continuous} (default Continuous).
- Enum: **Auto-overdub span** = {Word, Story}.
- Toggle: **Enable looper** (off = pure stock behavior; SOLO stays SOLO).

## 14. Files to create / modify

**Create:** `looper/LooperManager.java`, `LooperVoice.java`, `LoopSlot.java`, `LayerInfo.java`,
`OverdubMode.java`, `OverdubSpan.java`, `RecordingState.java`, `command/trigger/OverdubCommand.java`.
**Modify:** `APCControllerSetup.java`, `view/SessionView.java`, `APCConfiguration.java`.

## 15. Risks & spikes (validate before building the full machine)

1. **`duplicate()` carries routing** — manually confirmed via Ctrl+D; confirm the *script* call
   behaves identically (duplicate a wired track, record into the copy, hear the input). *Highest
   stakes.* → see the temporary test harness in `createObservers()` (Looper Test settings buttons).
2. **Flat-bank child resolution + async handle to a fresh duplicate** — prove we can duplicate the
   spawn and reliably grab the new track via a bank observer, then arm + record it.
3. **Boundary scheduler accuracy** — prove the poller starts/stops audio recording tightly on a story
   boundary and that recorded layers phase-lock on playback (input-latency offset shows here; tune
   Bitwig recording-latency compensation).
4. **Track-count growth** — add a configurable soft cap + notification; "unlimited" is bounded by
   CPU / project size.

## 16. Suggested milestone order

1. Settings + group detection/validation + pad-color marking.
2. Single free-length loop record/play on a looper column (no layers yet).
3. One auto-stop overdub layer via duplication + phase-lock.
4. Boundary scheduler & mode (c) extension.
5. Modes (a)/(b) + span setting.
6. Delete-last-layer.
7. Polish (LEDs, notifications, edge cases).
