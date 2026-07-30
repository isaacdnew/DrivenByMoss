// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.akai.apc.looper;

import java.util.Arrays;

import de.mossgrabers.controller.akai.apc.APCConfiguration;
import de.mossgrabers.controller.akai.apc.RecordedClipLaunchFixer;
import de.mossgrabers.framework.daw.IHost;
import de.mossgrabers.framework.daw.IModel;
import de.mossgrabers.framework.daw.data.ICursorTrack;
import de.mossgrabers.framework.daw.data.ISlot;
import de.mossgrabers.framework.daw.data.ITrack;
import de.mossgrabers.framework.daw.data.bank.ISlotBank;
import de.mossgrabers.framework.daw.data.bank.ITrackBank;
import de.mossgrabers.framework.daw.resource.ChannelType;


/**
 * Detects and drives audio-overdub looper groups. See docs/looper-design.md for the authoritative
 * spec.
 *
 * <p>
 * Banks (created once, at init): the top-level track bank (8x5) plus one 5x5 child bank per column
 * that follows the top-level scene position. A valid looper group has a fixed child layout:
 * child[0]=monitor, child[1]=template (empty, audio; duplicated to make layers), child[2]=next/
 * recording layer, child[3]=recording/most-recent, child[4]=most-recent/2nd-recent. Launch/stop/
 * display go through the group track. New layers are made by duplicating the template; the fresh copy
 * is not addressable synchronously, so the rename+arm of the new child[2] and the disarm of child[4]
 * happen on a later rescan. All such work runs only once the child layout has stopped changing (the
 * "settling edge", see {@link #rescan()}), which is what keeps a just-issued duplicate from being
 * re-issued while its copy is still appearing.
 * </p>
 *
 * @author Jürgen Moßgraber
 */
public class LooperManager
{
    private static final int CHILD_BANK_WIDTH = 5;
    private static final int MONITOR = 0;
    private static final int TEMPLATE = 1;
    private static final int CHILD2 = 2;
    private static final int CHILD3 = 3;
    private static final int CHILD4 = 4;

    /** TEMP DEBUG: set false (or remove all LOOPDBG code) once the layer-creation bug is diagnosed. */
    private static final boolean LOOPDBG = true;
    private int dbgRescanCount = 0;

    private final IHost host;
    private final APCConfiguration configuration;
    private final ITrackBank trackBank;
    private final ITrackBank [] childBanks;
    private final LooperValidity [] looperValiditiesByColumn;
    private final boolean [] armStatesByColumn;
    /**
     * Per-column: was the column settled (child layout unchanged) on the previous rescan? Used to run
     * {@link #maintainStagingLayer} only on the settling <em>edge</em> (first settled pass after a
     * change), so a just-issued template duplicate is not re-issued during its async latency. This
     * replaces the old duplication-pending flag (self-healing: any real layout change re-arms the edge).
     */
    private final boolean [] wasSettled;
    /**
     * Per-column snapshot of the child layout as of the last rescan. A column is only acted upon when
     * its signature is unchanged since the previous pass (i.e. Bitwig has stopped rippling the bank);
     * see {@link #rescan()}.
     */
    private final String [] prevGroupSignature;
    /** True while a single self-scheduled re-check (settle confirmation) is outstanding; see rescan(). */
    private boolean recheckScheduled;
    /**
     * A private cursor track used to preserve the user's selection across a script layer edit. duplicate ()
     * and remove () both move the DAW selection to another track (which drags the follow-cursor bank along).
     * Just before the edit we pin this cursor onto the current selection; once the edit lands we re-select
     * it (which scrolls the bank back to it) and unpin. Because it is a cursor, not a bank slot, the handle
     * survives the track scrolling out of every bank window. Kept separate from the shared cursor track so
     * pinning does not disturb the device/parameter views bound to that one.
     */
    private final ICursorTrack selectionCursor;
    /** The shared cursor track, which follows the live DAW selection; compared against {@link #selectionCursor}. */
    private final ICursorTrack cursorTrack;
    /**
     * True from the moment a layer edit pins the saved selection until that edit is seen to steal the
     * selection away (at which point we restore it and clear this). The saved track is still selected right
     * after the pin, so we must wait for the (async) steal before restoring - restoring immediately would
     * clear before the edit even lands, letting it take the selection for good.
     */
    private boolean waitingForSelectionSteal;
    /**
     * The layer edit (duplicate/remove) deferred until the pin is confirmed engaged; null when none is
     * pending. setPinned () takes a few host cycles to take effect, and running the edit before then lets
     * its selection change beat the pin, so the cursor never actually holds the saved track.
     */
    private Runnable pendingEdit;
    private RecordedClipLaunchFixer launchFixer;


    /**
     * Constructor. Must be called during driver initialization (it creates track banks).
     *
     * @param model The model
     * @param configuration The configuration
     * @param numScenes The number of scenes for the child banks
     */
    public LooperManager (final IModel model, final APCConfiguration configuration, final int numScenes)
    {
        this.host = model.getHost ();
        this.configuration = configuration;
        this.trackBank = model.getTrackBank ();
        this.cursorTrack = model.getCursorTrack ();
        this.selectionCursor = model.createCursorTrack ("FANCYLOOP_SELECTION", "FancyLoop Selection");
        this.selectionCursor.enableObservers (true);

        final int numColumns = this.trackBank.getPageSize ();
        this.childBanks = new ITrackBank [numColumns];
        this.looperValiditiesByColumn = new LooperValidity [numColumns];
        this.armStatesByColumn = new boolean [numColumns];
        this.wasSettled = new boolean [numColumns];
        this.prevGroupSignature = new String [numColumns];
        Arrays.fill (this.looperValiditiesByColumn, LooperValidity.NONE);
        Arrays.fill (this.prevGroupSignature, "");
        for (int column = 0; column < numColumns; column++)
        {
            final ITrackBank childBank = model.createChildTrackBank (this.trackBank.getItem (column), CHILD_BANK_WIDTH, numScenes);
            childBank.enableObservers (true);
            this.childBanks[column] = childBank;
        }
    }


    /**
     * Set the launch fixer used to re-launch a finished layer recording into a loop.
     *
     * @param launchFixer The launch fixer
     */
    public void setLaunchFixer (final RecordedClipLaunchFixer launchFixer)
    {
        this.launchFixer = launchFixer;
    }


    /**
     * Register the re-check triggers and the scene-following, then do an initial scan.
     */
    public void start ()
    {
        // Re-check triggers (and only these): horizontal move, top-track rename/type, child
        // rename/type, name settings.
        this.trackBank.addPageObserver (this::rescan);
        this.trackBank.addNameObserver ( (index, name) -> this.rescan ());
        for (int column = 0; column < this.looperValiditiesByColumn.length; column++)
        {
            this.trackBank.getItem (column).addTrackTypeObserver (type -> this.rescan ());
            final ITrackBank childBank = this.childBanks[column];
            childBank.addNameObserver ( (index, name) -> this.rescan ());
            for (int i = 0; i < childBank.getPageSize (); i++)
            {
                final ITrack childTrack = childBank.getItem (i);
                childTrack.addTrackTypeObserver (type -> this.rescan ());
                // A slot starting or stopping recording changes no track name or type, so nothing else
                // here would fire a rescan. The next staging layer for a just-recorded loop is created on
                // a rescan (once child[2] shows content), so trigger one whenever a child slot's recording
                // state flips.
                final ISlotBank slots = childTrack.getSlotBank ();
                for (int s = 0; s < slots.getPageSize (); s++)
                    slots.getItem (s).addIsRecordingObserver (isRecording -> this.rescan ());
            }
        }
        this.configuration.addLooperSettingsObserver (this::rescan);

        // Child banks follow the top-level scene position.
        this.trackBank.getSceneBank ().addPageObserver (this::syncChildScenes);
        this.syncChildScenes ();

        this.rescan ();
    }


    private void syncChildScenes ()
    {
        final int position = this.trackBank.getSceneBank ().getScrollPosition ();
        for (final ITrackBank childBank : this.childBanks)
            childBank.getSceneBank ().scrollTo (position);
    }


    /**
     * Re-validate every visible column, acting only on columns whose child layout has settled (stopped
     * changing). On the settling edge of a valid column, normalize its staging layer.
     */
    public void rescan ()
    {
        // Run a deferred layer edit only once the pin has actually engaged (isPinned () lags setPinned () by
        // a few host cycles). Running it earlier let the edit's selection change beat the pin, so the cursor
        // followed the selection away instead of holding the saved track.
        if (this.pendingEdit != null && this.selectionCursor.isPinned ())
        {
            final Runnable edit = this.pendingEdit;
            this.pendingEdit = null;
            this.waitingForSelectionSteal = true;
            this.dbg ("pin confirmed (isPinned=true) - running deferred edit");
            edit.run ();
        }

        // TRUST GATE: Bitwig applies a single change to the child bank as a burst of individual updates,
        // and the in-between snapshots are inconsistent (half-moved tracks, un-renamed copies, UNKNOWN
        // cells). So we only (re)compute validity and touch a column once its child layout is UNCHANGED
        // since the previous pass - i.e. the ripple has gone quiet. While anything is still changing we
        // act on nothing and schedule a single next-cycle re-check (scheduleTask delay 0 = next host
        // update, no wall-clock guess) so we are guaranteed to look again once it settles. Columns that
        // stay changed re-arm the re-check; a settled bank schedules nothing (fully event-driven at rest).
        boolean anyUnsettled = false;
        for (int column = 0; column < this.looperValiditiesByColumn.length; column++)
        {
            final String signature = this.getGroupSignature (column, this.childBanks[column].getPageSize ());
            final boolean settled = signature.equals (this.prevGroupSignature[column]);
            this.prevGroupSignature[column] = signature;
            // The settling EDGE: this is the first settled pass since the layout last changed.
            final boolean settleEdge = settled && !this.wasSettled[column];
            this.wasSettled[column] = settled;
            if (!settled)
            {
                anyUnsettled = true;
                continue; // still rippling - leave validity + staging at their last settled values
            }
            this.looperValiditiesByColumn[column] = this.computeColumnStatus (column);
            // Normalize the staging layer only on the settling edge, so each distinct settled layout is
            // handled exactly once. A just-issued duplicate then stays settled (unchanged) through its
            // async latency without being re-issued - no duplicate storm, and no pending flag needed.
            if (settleEdge && this.looperValiditiesByColumn[column] == LooperValidity.VALID)
                this.maintainStagingLayer (column);
        }

        // Restore the pre-edit selection once the whole pass is quiet (see restoreSelection). Cursor-based,
        // so it needs neither the operated group nor its layers to be in the bank window.
        this.restoreSelection (!anyUnsettled);

        // Keep looking again while the bank is still rippling, or while we are waiting for a pending edit's
        // pin to engage (nothing else would fire a rescan to notice isPinned () has flipped).
        if (anyUnsettled || this.pendingEdit != null)
            this.scheduleRecheck ();

        // TEMP DEBUG: one compact line per rescan so that, for every UI action (deletes, scroll, etc.),
        // we can see whether a rescan actually fired and what each looper column's child[2] read at that
        // instant. This tells apart "no rescan fired" (no line at all), "rescan fired but the column read
        // invalid / child[2] absent" (stale-read skip), and "read the right value but skipped" (logic bug).
        if (LOOPDBG)
        {
            final StringBuilder sb = new StringBuilder ("rescan #").append (++this.dbgRescanCount).append (anyUnsettled ? " UNSETTLED(re-check queued)" : " settled").append (" bankScroll=").append (this.trackBank.getScrollPosition ());
            boolean anyLooper = false;
            for (int c = 0; c < this.looperValiditiesByColumn.length; c++)
            {
                if (this.looperValiditiesByColumn[c] == LooperValidity.NONE)
                    continue;
                anyLooper = true;
                final ITrack c2 = this.getChild (c, CHILD2);
                sb.append (" | col").append (c).append (":").append (this.looperValiditiesByColumn[c]).append (" groupPos=").append (this.trackBank.getItem (c).getPosition ()).append (" child[2]=").append (c2.doesExist () ? c2.getType () + " '" + c2.getName () + "'" + (hasAnyContent (c2) ? ",content" : "") : "ABSENT");
            }
            if (!anyLooper)
                sb.append (" | (no looper columns visible)");
            this.dbg (sb.toString ());
        }
    }


    /** The ordered validity checks (see design doc). Tracks are identified by type, not heuristics. */
    private LooperValidity computeColumnStatus (final int column)
    {
        if (!this.configuration.isLooperEnabled ())
            return LooperValidity.NONE;

        final ITrack group = this.trackBank.getItem (column);
        if (!group.doesExist ())
            return LooperValidity.NONE;

        // 1. name contains the looper-group substring (else not a looper at all).
        final String groupSubstring = nullToEmpty (this.configuration.getLooperGroupName ());
        if (groupSubstring.isEmpty () || !group.getName ().contains (groupSubstring))
            return LooperValidity.NONE;

        // 2. is a group track (covers both collapsed and expanded groups).
        if (!group.isGroup ())
            return LooperValidity.MISCONFIGURED;

        final ITrackBank childBank = this.childBanks[column];
        final String monitorName = nullToEmpty (this.configuration.getLooperMonitorName ());
        final String templateName = nullToEmpty (this.configuration.getLooperTemplateName ());
        final String layerPrefix = nullToEmpty (this.configuration.getLooperLayerPrefix ());
        final ITrack monitor = childBank.getItem (MONITOR);
        final ITrack template = childBank.getItem (TEMPLATE);

        // 3. child[0] is the monitor: an audio, instrument or group track whose name contains the
        // configured monitor name.
        if (!isMonitorTrack (monitor) || !monitor.getName ().contains (monitorName))
            return LooperValidity.MISCONFIGURED;
        // 4. child[1] is exactly the template: an empty audio track with the configured name.
        if (template.getType () != ChannelType.AUDIO || !template.getName ().equals (templateName))
            return LooperValidity.MISCONFIGURED;
        if (hasAnyContent (template))
            return LooperValidity.MISCONFIGURED;

        // 5. Every child between the template and the group master must be a valid audio layer. The
        // group master is always the trailing child (type MASTER) - stop there and ignore it.
        for (int i = CHILD2; i < childBank.getPageSize (); i++)
        {
            final ITrack child = childBank.getItem (i);
            if (!child.doesExist () || child.getType () == ChannelType.MASTER)
                break;
            if (child.getType () != ChannelType.AUDIO)
                return LooperValidity.MISCONFIGURED;
            final String name = child.getName ();
            if (!monitorName.isEmpty () && name.contains (monitorName))
                return LooperValidity.MISCONFIGURED;
            // Each child here is either a layer (name contains the layer prefix) or a freshly duplicated
            // template copy that is not yet renamed (name still contains the template name) - the latter
            // is transient and maintainStagingLayer renames it to a layer on the next settled pass.
            final boolean isFreshTemplateCopy = !templateName.isEmpty () && name.contains (templateName);
            if (!name.contains (layerPrefix) && !isFreshTemplateCopy)
                return LooperValidity.MISCONFIGURED;
        }
        return LooperValidity.VALID;
    }


    /**
     * Normalize a valid column's **staging layer** (child[2], the empty layer the next loop records
     * into): create one when missing, keep it named one greater than the layer to its right, and arm it
     * to match the column. Runs once per settling edge (see {@link #rescan()}) - which is what stops a
     * just-issued template duplicate from being re-issued while its copy is still appearing.
     */
    private void maintainStagingLayer (final int column)
    {
        // Keep child[4] disarmed: only the staging layer (child[2]) and the recording / most-recent
        // layer (child[3]) stay armed. Every layer passes through child[4] (child[3] -> child[4] ->
        // out of the 5-wide window) on its way down, so disarming this one index disarms every layer
        // before the next duplicate pushes it out of the window.
        this.disarmLayer (this.getChild (column, CHILD4));

        final ITrack child2 = this.getChild (column, CHILD2);
        final String templateName = nullToEmpty (this.configuration.getLooperTemplateName ());

        // Ensure a staging layer exists - but only from a CONFIRMED state, never an ambiguous one:
        //  - child[2] is the group MASTER => the group has no layers => create the first;
        //  - child[2] is an audio track WITH content => the staging was consumed by a recording (or the
        //    empty staging was deleted and a recorded layer slid up) => create a fresh one.
        // An UNKNOWN/absent child[2] is left alone. Because this runs only on the settling edge, the
        // duplicate is issued once and not repeated while its copy is still appearing.
        if (child2.getType () == ChannelType.MASTER || (child2.getType () == ChannelType.AUDIO && hasAnyContent (child2)))
        {
            this.dbg ("maintainStagingLayer col=" + column + ": CREATE staging layer -> duplicating template (child[2]=" + child2.getType () + " '" + child2.getName () + "' content=" + hasAnyContent (child2) + ")");
            // duplicate () selects the fresh copy and scrolls the group off-screen; pin the current
            // selection first, deferring the duplicate until the pin engages, so it survives to be restored.
            this.saveSelection ( () -> this.getChild (column, TEMPLATE).duplicate ());
            return;
        }

        // child[2] is the empty staging layer (an established one, or a just-duplicated copy still named
        // like the template). Keep it named one greater than the layer to its right (child[3]) - or
        // "<prefix> 1" when there is none - and carry the column's armed state onto it.
        if (child2.getType () == ChannelType.AUDIO && !hasAnyContent (child2))
        {
            if (!templateName.isEmpty () && child2.getName ().contains (templateName))
            {
                // A freshly duplicated copy is unarmed; carry the column's armed intent onto it.
                if (this.armStatesByColumn[column])
                    this.armLayer (child2);
                else
                    this.disarmLayer (child2);
            }
            else
            {
                // An established staging layer's record-arm is the source of truth for the column (it is
                // saved in the project, so this restores the armed state across reloads and reflects a
                // manual arm change on the track).
                this.armStatesByColumn[column] = child2.isRecArm ();
            }

            final ITrack right = this.getChild (column, CHILD3);
            final int rightNumber = right.getType () == ChannelType.AUDIO ? parseTrailingNumber (right.getName ()) : 0;
            final String stagedName = nullToEmpty (this.configuration.getLooperLayerPrefix ()) + " " + (rightNumber + 1);
            if (!child2.getName ().equals (stagedName))
                child2.setName (stagedName);
        }
    }


    /**
     * Get the per-column child track banks (monitor/template/layers/master). Used to extend the
     * just-recorded-clip auto-loop behavior to layers of collapsed looper groups (which are not
     * visible in the main session bank).
     *
     * @return The child track banks, one per surface column
     */
    public ITrackBank [] getChildBanks ()
    {
        return this.childBanks;
    }


    /**
     * Get the cached looper status of the given APC column (0-based).
     *
     * @param column The column index on the surface
     * @return The status
     */
    public LooperValidity getColumnLooperValidity (final int column)
    {
        if (column < 0 || column >= this.looperValiditiesByColumn.length)
            return LooperValidity.NONE;
        return this.looperValiditiesByColumn[column];
    }


    /**
     * Whether the column is a looper group at all (valid or misconfigured) - i.e. its name matches.
     *
     * @param column The surface column index
     * @return True if the column is a looper group
     */
    public boolean isLooperColumn (final int column)
    {
        return this.getColumnLooperValidity (column) != LooperValidity.NONE;
    }


    /**
     * Whether the given looper column is armed for recording (per-column state).
     *
     * @param column The surface column index
     * @return True if armed
     */
    public boolean isColumnArmed (final int column)
    {
        return column >= 0 && column < this.armStatesByColumn.length && this.armStatesByColumn[column];
    }


    /**
     * Toggle the armed state of a looper column (driven by the column's record-arm button). Arming
     * arms child[2]+child[3]; disarming disarms child[2..4] immediately (even if it stops a
     * recording, like a normal track).
     *
     * @param column The surface column index
     */
    public void toggleColumnArm (final int column)
    {
        if (column < 0 || column >= this.armStatesByColumn.length)
            return;
        this.armStatesByColumn[column] = !this.armStatesByColumn[column];
        this.dbgDumpColumn (column, "toggleColumnArm -> armed=" + this.armStatesByColumn[column]);
        if (this.armStatesByColumn[column])
        {
            this.armLayer (this.getChild (column, CHILD2));
            this.armLayer (this.getChild (column, CHILD3));
        }
        else
        {
            this.disarmLayer (this.getChild (column, CHILD2));
            this.disarmLayer (this.getChild (column, CHILD3));
            this.disarmLayer (this.getChild (column, CHILD4));
        }
    }


    /**
     * Handle a press on a looper pad. An in-progress recording is finished (relaunched into a loop)
     * regardless of the column's possibly-transient validity, so a short take can always be stopped mid-
     * churn. The remaining actions require a VALID column: armed -&gt; record a new layer into child[2];
     * has content -&gt; launch the loop via the group; empty + not armed -&gt; stop the column.
     *
     * @param column The surface column index
     * @param scene The scene index
     * @return True if the press was handled
     */
    public boolean handlePad (final int column, final int scene)
    {
        // FINISH takes priority over everything, and runs BEFORE the validity gate: the pad must stop an
        // in-progress take even while the staging-layer duplication is still settling (the column may read
        // transiently MISCONFIGURED and the recording layer may have slid to another child index).
        final ITrack recordingLayer = this.getRecordingLayerAtScene (column, scene);
        if (recordingLayer != null)
        {
            this.dbg ("handlePad(scene=" + scene + ") col=" + column + " -> branch FINISH (relaunch recording layer '" + recordingLayer.getName () + "')");
            final ISlot recordingSlot = recordingLayer.getSlotBank ().getItem (scene);
            // Launching the still-recording slot ends the recording (Bitwig then plays it once). Schedule
            // the auto-looper to re-launch this exact clip into a loop the instant recording stops.
            if (this.launchFixer != null)
                this.launchFixer.scheduleRelaunch (recordingLayer.getPosition (), recordingSlot.getPosition ());
            recordingSlot.launch (true, false);
            return true;
        }

        if (this.getColumnLooperValidity (column) != LooperValidity.VALID)
            return false;

        this.dbgDumpColumn (column, "handlePad(scene=" + scene + ")");

        final ITrack group = this.trackBank.getItem (column);
        final ISlot groupSlot = group.getSlotBank ().getItem (scene);

        if (this.armStatesByColumn[column])
        {
            final ITrack target = this.getChild (column, CHILD2);
            if (target.getType () != ChannelType.AUDIO)
            {
                this.dbg ("  -> branch ARMED but NO STAGING LAYER (child[2]=" + (target.doesExist () ? target.getType () : "ABSENT") + "); nothing recorded");
                return true; // no layer staged yet; a rescan will create one
            }
            final boolean groupPlaying = groupSlot.isPlaying () || groupSlot.isPlayingQueued ();
            this.dbg ("  -> branch RECORD (deferred-duplicate): target=child[2] name='" + target.getName () + "' pos=" + target.getPosition () + " | groupSlot playing=" + groupSlot.isPlaying () + " playingQueued=" + groupSlot.isPlayingQueued () + " hasContent=" + groupSlot.hasContent ());
            // Keep an EXISTING loop audible (without restarting it) while overdubbing - but only when there
            // is content to play. Launching the group scene for the very first layer would fire the (empty)
            // staging slot at the same instant we record into it.
            if (!groupPlaying && groupSlot.hasContent ())
            {
                this.dbg ("     launch group scene (existing loop was stopped)");
                groupSlot.launch (true, false);
            }
            // Record into the EXISTING, already-armed staging layer. We deliberately do NOT duplicate the
            // template here: issuing record + a track-reordering duplicate together made record () bind to
            // bank position 2 AFTER the duplicate had slid a fresh, not-yet-armed copy into it, so the take
            // landed on the copy and started a beat late (the new track needed a beat to arm/route).
            // Deferring lets maintainStagingLayer mint the next staging layer on a later rescan - which,
            // thanks to the settle gate, cannot fire until this recording has produced content, i.e. after
            // record () has already committed to this (stable, armed) track.
            final ISlot recordSlot = target.getSlotBank ().getItem (scene);
            this.dbg ("     startRecording on track pos=" + target.getPosition () + " name='" + target.getName () + "' slotPos=" + recordSlot.getPosition () + " (recording=" + recordSlot.isRecording () + " recQueued=" + recordSlot.isRecordingQueued () + ")");
            recordSlot.startRecording ();
            this.dbgDumpColumn (column, "     post-record (no duplicate; next staging created on a later settled rescan)");
            return true;
        }

        if (groupSlot.hasContent ())
        {
            this.dbg ("  -> branch LAUNCH loop (group slot has content)");
            groupSlot.launch (true, false);
            return true;
        }

        this.dbg ("  -> branch STOP column (empty, not armed)");
        group.stop (false);
        return true;
    }


    /**
     * Stop the playing loop of a looper column (driven by the column's stop button).
     *
     * @param column The surface column index
     */
    public void stopColumn (final int column)
    {
        if (column < 0 || column >= this.looperValiditiesByColumn.length)
            return;
        // Stopping is an explicit "stay stopped" intent - cancel any pending re-launches for this
        // column's layers so a just-finished recording is not restarted.
        if (this.launchFixer != null)
        {
            final ITrackBank childBank = this.childBanks[column];
            for (int i = 0; i < childBank.getPageSize (); i++)
                this.launchFixer.cancelScheduledForTrack (childBank.getItem (i).getPosition ());
        }
        this.trackBank.getItem (column).stop (false);
    }


    /**
     * Remove the newest recorded layer that has content at the given scene (the per-scene LIFO undo -
     * which may not be the newest layer overall). No-op if no recorded layer has content there. The
     * empty staging layer is never removed.
     *
     * @param column The surface column index
     * @param scene The scene index
     */
    public void removeLastLayerAtScene (final int column, final int scene)
    {
        if (this.getColumnLooperValidity (column) != LooperValidity.VALID)
            return;
        final ITrackBank childBank = this.childBanks[column];
        // Layers are newest-first from child[2]; the first one with content at this scene is the
        // newest for the scene. Stop at the group master (the trailing child).
        for (int i = CHILD2; i < childBank.getPageSize (); i++)
        {
            final ITrack child = childBank.getItem (i);
            if (child.getType () == ChannelType.MASTER)
                break;
            if (child.getType () != ChannelType.AUDIO)
                continue;
            if (child.getSlotBank ().getItem (scene).hasContent ())
            {
                if (this.launchFixer != null)
                    this.launchFixer.cancelScheduledForTrack (child.getPosition ());
                // remove () selects an adjacent track and scrolls the group off-screen; pin the current
                // selection first, deferring the remove until the pin engages, so it survives to be restored.
                this.saveSelection ( () -> child.remove ());
                return;
            }
        }
    }


    /**
     * Whether any loop of a looper column is currently playing (or queued to play).
     *
     * @param column The surface column index
     * @return True if a loop is playing/queued
     */
    public boolean isColumnPlaying (final int column)
    {
        if (column < 0 || column >= this.looperValiditiesByColumn.length)
            return false;
        final ISlotBank slotBank = this.trackBank.getItem (column).getSlotBank ();
        for (int i = 0; i < slotBank.getPageSize (); i++)
        {
            final ISlot slot = slotBank.getItem (i);
            if (slot.isPlaying () || slot.isPlayingQueued ())
                return true;
        }
        return false;
    }


    /** The layer track among child[2]/child[3] whose slot at the given scene is recording, or null. */
    private ITrack getRecordingLayerAtScene (final int column, final int scene)
    {
        // Scan every layer child (not just child[2]/child[3]): while the staging duplicate is settling
        // the recording layer slides between indices, so a narrow scan misses it and the finish press is
        // lost. child[0]/child[1] are the monitor/template (never recorded into) and MASTER is skipped by
        // the AUDIO check, so scanning the whole window is safe.
        final ITrackBank childBank = this.childBanks[column];
        for (int i = CHILD2; i < childBank.getPageSize (); i++)
        {
            final ITrack child = childBank.getItem (i);
            if (child.getType () == ChannelType.AUDIO)
            {
                final ISlot slot = child.getSlotBank ().getItem (scene);
                if (slot.isRecording () || slot.isRecordingQueued ())
                    return child;
            }
        }
        return null;
    }


    private ITrack getChild (final int column, final int index)
    {
        return this.childBanks[column].getItem (index);
    }


    /**
     * Pin the selection cursor onto the currently selected track, then defer the given layer edit until the
     * pin is confirmed engaged (see {@link #pendingEdit}), so the saved track survives to be restored. If a
     * save is already in flight the edit is run immediately instead (its selection is not preserved, but it
     * must not be dropped).
     *
     * @param edit The duplicate/remove to run once the pin has engaged
     */
    private void saveSelection (final Runnable edit)
    {
        if (this.pendingEdit != null || this.waitingForSelectionSteal)
        {
            edit.run ();
            return;
        }
        this.selectionCursor.setPinned (true);
        this.pendingEdit = edit;
        this.scheduleRecheck ();
        this.dbg ("saveSelection: pinning '" + this.selectionCursor.getName () + "' pos=" + this.selectionCursor.getPosition () + " (live '" + this.cursorTrack.getName () + "' pos=" + this.cursorTrack.getPosition () + "); edit deferred until pin engages");
    }


    /** Schedule a single next-cycle rescan (delay 0), unless one is already outstanding. */
    private void scheduleRecheck ()
    {
        if (this.recheckScheduled)
            return;
        this.recheckScheduled = true;
        this.host.scheduleTask ( () -> {
            this.recheckScheduled = false;
            this.rescan ();
        }, 0);
    }


    /**
     * Once the edit has moved the DAW selection off the saved track, re-select that track - which scrolls
     * the bank back to it - and unpin. Called only on a fully settled pass. Detection compares the live
     * selection (shared cursor) against the pinned cursor, so it needs nothing to be in the bank window.
     *
     * @param settled Whether the current rescan pass is fully settled
     */
    private void restoreSelection (final boolean settled)
    {
        if (!this.waitingForSelectionSteal || !settled)
            return;
        this.dbg ("restore-selection check: live '" + this.cursorTrack.getName () + "' pos=" + this.cursorTrack.getPosition () + " | pinned '" + this.selectionCursor.getName () + "' isPinned=" + this.selectionCursor.isPinned () + " pos=" + this.selectionCursor.getPosition ());
        // The saved track is still selected right after the pin; wait until the edit steals it away. Acting
        // now would restore before the (async) edit lands, and it would then take the selection for good.
        if (this.cursorTrack.getPosition () == this.selectionCursor.getPosition ())
            return;

        this.dbg ("restore-selection: edit stole the selection - re-selecting the saved track");
        // Select via the pinned cursor (works even off-window); the bank auto-follows the cursor, so the
        // view returns without a bare scroll. Select BEFORE unpinning, or the cursor would first jump to
        // follow the current (edit-stolen) selection. No further steal follows, so this one select sticks.
        this.selectionCursor.select ();
        this.selectionCursor.setPinned (false);
        this.waitingForSelectionSteal = false;
    }


    /**
     * A snapshot string of a looper group's in-view layout: the first {@code count} of its child tracks,
     * each rendered by {@link #getTrackSignature}. Two rescans with the same signature mean Bitwig has
     * stopped changing that group's bank (the settle signal the {@link #rescan()} trust gate acts on) -
     * a layer appearing, disappearing, being renamed, or gaining/losing content all change it. Pass the
     * full page size for the whole layout, or one less to drop the trailing slot.
     */
    private String getGroupSignature (final int column, final int count)
    {
        final ITrackBank childBank = this.childBanks[column];
        final StringBuilder sb = new StringBuilder ();
        for (int i = 0; i < count; i++)
            sb.append (getTrackSignature (childBank.getItem (i))).append (';');
        return sb.toString ();
    }


    /** A signature for a single track: type:name:has-content - the per-track unit of {@link #getGroupSignature}. */
    private static String getTrackSignature (final ITrack track)
    {
        return (track.doesExist () ? track.getType ().toString () : "-") + ":" + track.getName () + ":" + (hasAnyContent (track) ? "C" : "-");
    }


    // ==== TEMP DEBUG (remove once diagnosed) ====

    private void dbg (final String message)
    {
        if (LOOPDBG)
            this.host.println ("[LOOP] " + message);
    }


    /** Dumps the full child layout the code sees for a column: type / name / hasContent per child. */
    private void dbgDumpColumn (final int column, final String when)
    {
        if (!LOOPDBG)
            return;
        final ITrackBank childBank = this.childBanks[column];
        final StringBuilder sb = new StringBuilder ();
        sb.append (when).append (" col=").append (column).append (" bankScroll=").append (this.trackBank.getScrollPosition ()).append (" groupPos=").append (this.trackBank.getItem (column).getPosition ()).append (" validity=").append (this.looperValiditiesByColumn[column]).append (" armed=").append (this.armStatesByColumn[column]).append (" | ");
        for (int i = 0; i < childBank.getPageSize (); i++)
        {
            final ITrack c = childBank.getItem (i);
            sb.append ("child[").append (i).append ("]=").append (c.doesExist () ? c.getType () : "ABSENT").append ("('").append (c.getName ()).append ("'").append (hasAnyContent (c) ? ",content" : "").append (") ");
        }
        this.host.println ("[LOOP] " + sb);
    }


    private void armLayer (final ITrack track)
    {
        if (track.getType () == ChannelType.AUDIO)
            track.setRecArm (true);
    }


    private void disarmLayer (final ITrack track)
    {
        if (track.getType () == ChannelType.AUDIO && track.isRecArm ())
            track.setRecArm (false);
    }


    /** The monitor may be an audio, instrument or group track (anything that can carry the live source). */
    private static boolean isMonitorTrack (final ITrack track)
    {
        final ChannelType type = track.getType ();
        return type == ChannelType.AUDIO || type == ChannelType.INSTRUMENT || type == ChannelType.GROUP;
    }


    private static boolean hasAnyContent (final ITrack track)
    {
        final ISlotBank slotBank = track.getSlotBank ();
        for (int i = 0; i < slotBank.getPageSize (); i++)
        {
            if (slotBank.getItem (i).hasContent ())
                return true;
        }
        return false;
    }


    private static int parseTrailingNumber (final String name)
    {
        int start = name.length ();
        while (start > 0 && Character.isDigit (name.charAt (start - 1)))
            start--;
        if (start == name.length ())
            return 0;
        try
        {
            return Integer.parseInt (name.substring (start));
        }
        catch (final NumberFormatException ex)
        {
            return 0;
        }
    }


    private static String nullToEmpty (final String text)
    {
        return text == null ? "" : text;
    }
}
