// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.akai.apc.looper;

import java.util.Arrays;

import de.mossgrabers.controller.akai.apc.APCConfiguration;
import de.mossgrabers.controller.akai.apc.RecordedClipLaunchFixer;
import de.mossgrabers.framework.daw.IHost;
import de.mossgrabers.framework.daw.IModel;
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
    private static final int            CHILD_BANK_WIDTH = 5;
    private static final int            MONITOR          = 0;
    private static final int            TEMPLATE         = 1;
    private static final int            CHILD2           = 2;
    private static final int            CHILD3           = 3;
    private static final int            CHILD4           = 4;

    /** TEMP DEBUG: set false (or remove all LOOPDBG code) once the layer-creation bug is diagnosed. */
    private static final boolean        LOOPDBG                 = true;
    private int                         dbgRescanCount          = 0;

    private final IHost                 host;
    private final APCConfiguration      configuration;
    private final ITrackBank            trackBank;
    private final ITrackBank []         childBanks;
    private final LooperValidity []     looperValiditiesByColumn;
    private final boolean []            armStatesByColumn;
    /**
     * Per-column: was the column settled (child layout unchanged) on the previous rescan? Used to run
     * {@link #maintainStagingLayer} only on the settling <em>edge</em> (first settled pass after a
     * change), so a just-issued template duplicate is not re-issued during its async latency. This
     * replaces the old duplication-pending flag (self-healing: any real layout change re-arms the edge).
     */
    private final boolean []            wasSettled;
    /**
     * Per-column snapshot of the child layout as of the last rescan. A column is only acted upon when
     * its signature is unchanged since the previous pass (i.e. Bitwig has stopped rippling the bank);
     * see {@link #rescan()}.
     */
    private final String []             prevChildSignature;
    /** True while a single self-scheduled re-check (settle confirmation) is outstanding; see rescan(). */
    private boolean                     recheckScheduled;
    /**
     * When &gt;= 0, the main-bank scroll position to restore to. Armed just before a script-issued
     * template duplicate (whose selection of the fresh copy makes the follow-cursor bank scroll the
     * looper group out of view); the page observer scrolls back to it, and it is cleared once the
     * duplication churn has settled. A user scroll (no duplicate in flight) leaves it &lt; 0 and untouched.
     */
    private int                         scrollRestorePos = -1;
    /**
     * The surface column whose template duplicate we are currently restoring the scroll for, or -1. We
     * keep watching (and restoring) until this column's copy has landed - the duplicate's scroll is
     * heavily delayed and there are settled moments before it, so "stop when everything settles" gives up
     * far too early. A column index only means "the group we duplicated" while the bank is at
     * {@link #scrollRestorePos} (child banks follow the main bank, so a scrolled bank remaps every
     * column), so the disarm in {@link #maintainStagingLayer} is gated on that.
     */
    private int                         scrollRestoreColumn = -1;
    /**
     * The main-bank column that was selected just before the duplicate, to re-select once its copy has
     * landed - the duplicate selects the fresh copy, hijacking the user's selection. -1 if nothing in the
     * bank window was selected (then we leave the copy selected).
     */
    private int                         selectionRestoreColumn = -1;
    private RecordedClipLaunchFixer     launchFixer;


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

        final int numColumns = this.trackBank.getPageSize ();
        this.childBanks = new ITrackBank [numColumns];
        this.looperValiditiesByColumn = new LooperValidity [numColumns];
        this.armStatesByColumn = new boolean [numColumns];
        this.wasSettled = new boolean [numColumns];
        this.prevChildSignature = new String [numColumns];
        Arrays.fill (this.looperValiditiesByColumn, LooperValidity.NONE);
        Arrays.fill (this.prevChildSignature, "");
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

        // TEMP DEBUG: log whenever a column that was valid stops being valid (e.g. the group scrolled
        // out of the bank window after a duplicate selected the copy). Helps confirm the scroll-away.
        this.trackBank.addPageObserver ( () -> {
            final int pos = this.trackBank.getScrollPosition ();
            this.dbg ("trackBank page scrolled to position " + pos + (this.scrollRestorePos >= 0 ? " (watching; restore target " + this.scrollRestorePos + ")" : ""));
            // If a script-issued duplicate scrolled the group out of view, scroll back. The cursor stays
            // on the fresh copy and followCursorTrack only reacts to cursor MOVEMENT (not bank position),
            // so it does not fight this restore. A user scroll (scrollRestorePos < 0) is left untouched.
            if (this.scrollRestorePos >= 0 && pos != this.scrollRestorePos)
            {
                this.dbg ("  -> restoring script-caused scroll to position " + this.scrollRestorePos);
                this.trackBank.scrollTo (this.scrollRestorePos);
            }
        });

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
            final String signature = this.childSignature (column);
            final boolean settled = signature.equals (this.prevChildSignature[column]);
            this.prevChildSignature[column] = signature;
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

        if (anyUnsettled && !this.recheckScheduled)
        {
            this.recheckScheduled = true;
            this.host.scheduleTask ( () -> {
                this.recheckScheduled = false;
                this.rescan ();
            }, 0);
        }

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
                final ITrack c2 = this.child (c, CHILD2);
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
        this.disarmLayer (this.child (column, CHILD4));

        final ITrack child2 = this.child (column, CHILD2);
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
            // Remember where the bank is so the page observer can undo the scroll the duplicate's copy
            // selection will cause. Arm on the first duplicate of a batch (concurrent records across
            // columns all restore to the same pre-duplicate position); track the column so we know when
            // to stop (once its copy has landed at the restore position).
            if (this.scrollRestorePos < 0)
            {
                this.scrollRestorePos = this.trackBank.getScrollPosition ();
                this.selectionRestoreColumn = this.selectedColumn ();
            }
            this.scrollRestoreColumn = column;
            this.child (column, TEMPLATE).duplicate ();
            return;
        }

        // child[2] is the empty staging layer (an established one, or a just-duplicated copy still named
        // like the template). Keep it named one greater than the layer to its right (child[3]) - or
        // "<prefix> 1" when there is none - and carry the column's armed state onto it.
        if (child2.getType () == ChannelType.AUDIO && !hasAnyContent (child2))
        {
            // The staging layer (a landed copy or an established one) is visible and empty. If we were
            // restoring the scroll for THIS column and the bank is back at the restore position - only
            // then does this column really map to the group we duplicated (child banks follow the main
            // bank) - the copy has landed: stop watching. While the bank is still scrolled away this is
            // false, so the page observer keeps restoring and we never disarm on a jumped-to column.
            if (this.scrollRestoreColumn == column && this.trackBank.getScrollPosition () == this.scrollRestorePos)
            {
                // The copy has landed and the bank is home, so the previously-selected track is back at
                // its column: restore the selection the duplicate stole (the copy-selection has already
                // settled by now, so this overrides it), then stop watching.
                if (this.selectionRestoreColumn >= 0)
                    this.trackBank.getItem (this.selectionRestoreColumn).select ();
                this.scrollRestorePos = -1;
                this.scrollRestoreColumn = -1;
                this.selectionRestoreColumn = -1;
            }

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

            final ITrack right = this.child (column, CHILD3);
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
            this.armLayer (this.child (column, CHILD2));
            this.armLayer (this.child (column, CHILD3));
        }
        else
        {
            this.disarmLayer (this.child (column, CHILD2));
            this.disarmLayer (this.child (column, CHILD3));
            this.disarmLayer (this.child (column, CHILD4));
        }
    }


    /**
     * Handle a press on a looper pad (only for VALID columns). recording -&gt; finish at the
     * boundary and loop; armed -&gt; record a new layer into child[2] then duplicate the template;
     * has content -&gt; launch the loop via the group; empty + not armed -&gt; stop the column.
     *
     * @param column The surface column index
     * @param scene The scene index
     * @return True if the press was handled
     */
    public boolean handlePad (final int column, final int scene)
    {
        if (this.getColumnLooperValidity (column) != LooperValidity.VALID)
            return false;

        this.dbgDumpColumn (column, "handlePad(scene=" + scene + ")");

        final ITrack group = this.trackBank.getItem (column);
        final ISlot groupSlot = group.getSlotBank ().getItem (scene);

        final ITrack recordingLayer = this.recordingLayerAtScene (column, scene);
        if (recordingLayer != null)
        {
            this.dbg ("  -> branch FINISH (relaunch recording layer '" + recordingLayer.getName () + "')");
            final ISlot recordingSlot = recordingLayer.getSlotBank ().getItem (scene);
            // Finish: launching the still-recording slot ends the recording (Bitwig then plays it
            // once). Schedule the auto-looper to re-launch this exact clip into a loop the instant
            // recording stops.
            if (this.launchFixer != null)
                this.launchFixer.scheduleRelaunch (recordingLayer.getPosition (), recordingSlot.getPosition ());
            recordingSlot.launch (true, false);
            return true;
        }

        if (this.armStatesByColumn[column])
        {
            final ITrack target = this.child (column, CHILD2);
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
                child.remove ();
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
    private ITrack recordingLayerAtScene (final int column, final int scene)
    {
        for (int i = CHILD2; i <= CHILD3; i++)
        {
            final ITrack child = this.child (column, i);
            if (child.getType () == ChannelType.AUDIO)
            {
                final ISlot slot = child.getSlotBank ().getItem (scene);
                if (slot.isRecording () || slot.isRecordingQueued ())
                    return child;
            }
        }
        return null;
    }


    private ITrack child (final int column, final int index)
    {
        return this.childBanks[column].getItem (index);
    }


    /** The surface column whose main-bank track is currently selected, or -1 if none in the window is. */
    private int selectedColumn ()
    {
        for (int c = 0; c < this.trackBank.getPageSize (); c++)
        {
            if (this.trackBank.getItem (c).isSelected ())
                return c;
        }
        return -1;
    }


    /**
     * A snapshot string of a column's child layout (per child: type, name, has-content). Two rescans
     * with the same signature mean Bitwig has stopped changing the bank for that column - the settle
     * signal the {@link #rescan()} trust gate acts on. Includes every child slot in the window so that a
     * layer appearing, disappearing, being renamed, or gaining/losing content all change the signature.
     */
    private String childSignature (final int column)
    {
        final ITrackBank childBank = this.childBanks[column];
        final StringBuilder sb = new StringBuilder ();
        for (int i = 0; i < childBank.getPageSize (); i++)
        {
            final ITrack c = childBank.getItem (i);
            sb.append (c.doesExist () ? c.getType () : "-").append (':').append (c.getName ()).append (':').append (hasAnyContent (c) ? 'C' : '-').append (';');
        }
        return sb.toString ();
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
