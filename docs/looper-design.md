# APC40 MkII Clip-Launcher Audio Overdub — Design

> Status: in development on branch `fancy-apc40mkii-looper`. This document is the **authoritative
> spec**; it supersedes earlier iterations (flat banks, end-following banks, emptiness-based spawn,
> auto-cleanup, monitor-at-end, etc. — all removed).

## Goal

Bitwig has no native audio-clip overdub. We emulate it with a Bitwig **group track** per loop
"voice": a **monitor** track (live input + the source layers record from), an empty **template**
track that we duplicate, and **layer** tracks (the recorded loops) that all sum at the group output.
Audio routing cannot be read or set from the controller API, so the signal topology is built once in
a user template; the script only arms/records/duplicates/renames/launches/stops/colors.

## Track banks (created once, at startup)

The script creates **9** track banks during driver init (Bitwig only allows bank creation during
init):

- **1 top-level bank** — 8 tracks × 5 scenes (the APC grid).
- **8 child-track banks** — one per top-level column, **5 tracks wide × 5 scenes tall**, each scoped
  to that column track's direct children (`IModel.createChildTrackBank`). They **follow the top-level
  bank's scene position**, so a child slot at scene *s* corresponds to the grid pad at scene *s*. The
  looper layout keeps everything we touch within the first few children, so the child banks stay
  scrolled to the **start** of the group — no horizontal scrolling.

> **The group master is a child.** A group track's child bank includes the group's own **master/sum
> bus** as a **trailing** item (`getType() == ChannelType.MASTER`, named `"<Group> Master"`). So a
> group with just a monitor + template presents **three** child items: monitor, template, master.
> Note `canHoldAudioData()` returns **true** for that master — which is why we identify tracks by
> **type**, not by `canHoldAudioData()`.

## When to re-check the 8 columns for being loopers (and only then)

- the top-level bank moves **horizontally** (different tracks in view) — top-level page observer;
- a track in the top-level bank is **renamed or changes type** — top-level name + type observers;
- a child watched by a child bank is **renamed or changes type** — child name + type observers
  (this also fires when a layer is added/deleted, since the items shift);
- a **track-name setting** changes — settings observer.

## Validity — a top-level track is a VALID looper iff ALL pass, checked **in order**

Tracks are identified by `ChannelType`, never by name heuristics or `canHoldAudioData()`.

1. the looper feature is **enabled** (else NONE);
2. the top-level track **exists** (else NONE);
3. its name **contains** the looper-group-name setting (else NONE);
4. it **is a group track** (`isGroup()`; equivalently `getType() == GROUP`) — else MISCONFIGURED;
5. child[0] is **type AUDIO** and its name is **exactly** the monitor-name setting;
6. child[1] is **type AUDIO** and its name is **exactly** the template-name setting;
7. child[1] (the template) has **no clips in the child-bank window** (we only check visible scenes);
8. for every child from child[2] onward, **stopping at the first `MASTER` child or gap** (the master
   is the trailing sum bus and is ignored):
   - it must be **type AUDIO** (an unexpected non-audio track here ⇒ MISCONFIGURED);
   - its name must **not contain** the monitor name;
   - its name must **contain the layer-prefix** setting and **not contain the template name** —
     **unless** the template was just duplicated and not yet renamed (a fleeting state tracked by the
     duplication-pending flag, during which a template-named child here is allowed).

`getType()` returns `UNKNOWN` (not an error) for a non-existent child, so these type checks double as
existence checks. It never returns `GROUP_OPEN` (that is a display-only synthesis), so `type == GROUP`
is exactly `isGroup()`.

### Status semantics

- Name does **not** contain the group substring → **NONE** → normal/stock track behavior.
- Passes (3) but fails any of (4)–(8) → **MISCONFIGURED** → the column's clip pads **flash quickly in
  the invalid color (magenta/orchid)** and **every button on that column has no functionality** —
  not even the default behavior a non-looper track would have.
- All pass → **VALID**.

## Valid-looper layout (fixed positions — no adaptive search)

Newest layers sit **right after the template** (a duplicate is inserted immediately after the
template); older layers shift down toward the master.

| Child | Role |
|---|---|
| child[0] | **Monitor** track. |
| child[1] | **Template** track (empty, audio). Duplicated to create layers; never recorded into. |
| child[2] (3rd) | the **empty staging layer** we record the next loop into (armed when the column is armed); briefly, the layer being recorded, before the template is duplicated again. |
| child[3] (4th) | the **most recently recorded** layer (briefly, the layer being recorded). |
| child[4+] | older recorded layers (already disarmed). |
| trailing | the group **MASTER** sum bus — ignored. |

## Layer lifecycle (valid loopers)

- **Ensure a staging layer:** if child[2] is **not an empty audio track** — i.e. it is the master /
  absent (no layers yet), **or it already holds content** (e.g. the empty staging layer was deleted,
  sliding a recorded layer up into child[2]) — duplicate the template and rename the fresh copy as
  the next layer. This prevents recording over an existing layer.
- **Renumber to 1:** if child[2] is the empty staging layer **and** child[3] is the master / absent
  (every content layer below it was deleted), reset child[2]'s name to `Layer 1` so the next loop
  starts fresh.
- **Record into child[2]** (the press that starts a new layer/overdub), while armed:
  1. if the group scene isn't already playing, **launch it** (so the existing loop is heard during
     the overdub, without restarting it);
  2. start recording into child[2];
  3. **capture the next layer number** (max trailing number among the visible layers + 1) — this is
     read **now**, while the bank is stable, because the duplicate in the next step mid-shifts the
     bank and reading it then is unreliable;
  4. **duplicate the template** (pushes the recording layer to child[3] and shifts the rest down);
  5. on the next child-bank update (event-driven finalize, gated by the duplication-pending flag):
     rename the fresh child[2] copy to the captured number, **arm it** if the column is armed, and
     **disarm child[4]**.
- **Finish a recording:** **schedule** the recording layer for an auto re-launch (see below), then
  launch its slot to stop the recording.

## Launch / stop / display (via the GROUP track, not the children)

- **Launch** a scene = launch the **group** track's clip slot for that scene (Bitwig launches the
  whole sub-scene — all the layers).
- **Stop** = stop the **group** track, and **cancel** any scheduled re-launches for the column's
  layers (so a stop is never undone by a pending re-launch).
- **Display** = color each pad from the **group** track's own slot (Bitwig aggregates the children's
  state) using the stock `getPadColor`, with the per-column armed flag for the rec-armed stripe.

## Recorded-clip launch fix (one-shot → loop)

**Problem:** when a launcher recording stops, Bitwig can treat the clip as a **one-shot** — it plays
a single pass of its content and stops (the slot shows a queued stop), even with the clip's loop
attribute on. This reproduces on a plain audio track with no script involved. It tends to hit **very
short** recordings; longer ones loop fine. The exact deciding condition is **unknown** (time/beat
threshold? crossing a loop/quantization point? some other state?). Launching the **finished** clip,
by contrast, loops it.

**Fix:** `RecordedClipLaunchFixer` re-launches a clip the instant its recording stops. It is
**gesture-driven**: a clip is re-launched only if the press that ended its recording explicitly
**scheduled** it — so stopping via the transport (spacebar), a stop-clip / stop-column button, etc.
does **not** re-launch it.

- **Identity:** the scheduled clip is keyed by `(track absolute position, scene index)`, so a
  horizontal or vertical scroll that re-targets a slot handle onto a different clip can't match.
- **Scheduling:** the looper's **finish** schedules the recording **layer's** child slot (never the
  group slot — launching a group slot would launch the whole sub-scene); general (non-looper) session
  clips are scheduled in `SessionView.onGridNote` when a pressed pad's slot is recording.
- **Re-launch:** on `isRecording → false`, if the clip was scheduled (consume it) **and** the
  transport is playing **and** the track is **not a group** **and** the slot **has content**, call
  `launchWithOptions("none", "continue_or_synced")` — this cancels the queued stop mid-first-pass so
  the clip simply keeps looping, independent of the configured launch quantization.
- **Coverage:** the fixer `watch`es the whole top-level (session) bank **and** the looper child banks
  (a collapsed group's layers are not in the session bank).

## Arm behavior (valid loopers)

- The column record-arm button toggles a **per-column** armed flag.
- **Arm** → arm child[2] and child[3].
- **Disarm** → disarm child[2], child[3] and child[4] immediately (even if it stops a recording, like
  a normal track).

## Settings (controller preferences, category "Looper")

- **Enable looper** (On/Off, default On)
- **Looper group name** — substring match (default `LOOPER`)
- **Monitor track name** (default `Monitor`)
- **Layer template track name** (default `Template`)
- **Layer track name prefix** (default `Layer`)

## Confirmed Bitwig / framework facts & constraints

- `duplicate()` preserves input+output routing and monitor mode, does **not** copy arm state,
  **selects** the copy (accepted — it lands on the new layer), and inserts the copy **immediately
  after** the source.
- A group's child bank includes the group's **master/sum bus** as a trailing child (`type == MASTER`,
  `canHoldAudioData() == true`). Identify tracks by **type**, and stop the layer scan at the master.
- `getType()` returns `UNKNOWN` (no exception) for a non-existent track and never returns
  `GROUP_OPEN`; thus `type == GROUP` ≡ `isGroup()`.
- Launching a **still-recording** slot can make Bitwig play the resulting clip **once and stop** it
  (queued stop), most often for short takes — the exact boundary is unconfirmed. Re-launching the
  **finished** clip loops it.
- Launching a **group** track's clip slot launches the whole sub-scene; the group slot **aggregates**
  child state and is colored correctly by stock `getPadColor`.
- Track banks can only be **created during init**.
- **No arbitrary timers / polling** anywhere — a feature that would require one is omitted instead.
  (The launch fix is event-driven off `isRecording` observers, not polling.)
- A freshly `duplicate()`d track is **not addressable at its index immediately** (async); the rename/
  arm of the new layer runs when the child bank reflects it (event-driven), with the
  duplication-pending flag covering validity in the meantime.
- `ITrack.hasParent()` is true for group tracks themselves — use `isGroup()` to find groups.
- A bank not shown on the surface needs `enableObservers(true)` to deliver data.
- Framework additions for this feature: `ITrack.addTrackTypeObserver`, `IModel.createChildTrackBank`,
  `ISlot.addIsRecordingObserver`, `ISlot.launchWithOptions`.
