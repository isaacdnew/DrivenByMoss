// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.akai.apc.looper;

import java.util.Arrays;

import de.mossgrabers.controller.akai.apc.APCConfiguration;
import de.mossgrabers.controller.akai.apc.RecordedClipLaunchFixer;
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
 * display go through the group track. New layers are made by duplicating the template; since the
 * fresh copy is not addressable synchronously, the rename+arm of the new child[2] and the disarm of
 * child[4] happen on the next bank update (finalize), with a duplication-pending flag keeping the
 * group valid in between.
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

    private final APCConfiguration      configuration;
    private final ITrackBank            trackBank;
    private final ITrackBank []         childBankByColumn;
    private final LooperColumnStatus [] statusByColumn;
    private final boolean []            armedColumns;
    private final boolean []            duplicationPending;
    private final int []                pendingLayerNumber;
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
        this.configuration = configuration;
        this.trackBank = model.getTrackBank ();

        final int columns = this.trackBank.getPageSize ();
        this.childBankByColumn = new ITrackBank [columns];
        this.statusByColumn = new LooperColumnStatus [columns];
        this.armedColumns = new boolean [columns];
        this.duplicationPending = new boolean [columns];
        this.pendingLayerNumber = new int [columns];
        Arrays.fill (this.statusByColumn, LooperColumnStatus.NONE);
        for (int column = 0; column < columns; column++)
        {
            final ITrackBank childBank = model.createChildTrackBank (this.trackBank.getItem (column), CHILD_BANK_WIDTH, numScenes);
            childBank.enableObservers (true);
            this.childBankByColumn[column] = childBank;
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
        for (int column = 0; column < this.statusByColumn.length; column++)
        {
            this.trackBank.getItem (column).addTrackTypeObserver (type -> this.rescan ());
            final ITrackBank childBank = this.childBankByColumn[column];
            childBank.addNameObserver ( (index, name) -> this.rescan ());
            for (int i = 0; i < childBank.getPageSize (); i++)
                childBank.getItem (i).addTrackTypeObserver (type -> this.rescan ());
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
        for (final ITrackBank childBank : this.childBankByColumn)
            childBank.getSceneBank ().scrollTo (position);
    }


    /**
     * Re-validate every visible column; on valid ones, finalize a pending duplicate or ensure the
     * first layer exists.
     */
    public void rescan ()
    {
        for (int column = 0; column < this.statusByColumn.length; column++)
        {
            this.statusByColumn[column] = this.computeColumnStatus (column);
            if (this.statusByColumn[column] == LooperColumnStatus.VALID)
                this.finalizeOrEnsure (column);
        }
    }


    /** The ordered validity checks (see design doc). Tracks are identified by type, not heuristics. */
    private LooperColumnStatus computeColumnStatus (final int column)
    {
        if (!this.configuration.isLooperEnabled ())
            return LooperColumnStatus.NONE;

        final ITrack group = this.trackBank.getItem (column);
        if (!group.doesExist ())
            return LooperColumnStatus.NONE;

        // 1. name contains the looper-group substring (else not a looper at all).
        final String groupSubstring = nullToEmpty (this.configuration.getLooperGroupName ());
        if (groupSubstring.isEmpty () || !group.getName ().contains (groupSubstring))
            return LooperColumnStatus.NONE;

        // 2. is a group track (covers both collapsed and expanded groups).
        if (!group.isGroup ())
            return LooperColumnStatus.MISCONFIGURED;

        final ITrackBank childBank = this.childBankByColumn[column];
        final String monitorName = nullToEmpty (this.configuration.getLooperMonitorName ());
        final String templateName = nullToEmpty (this.configuration.getLooperTemplateName ());
        final String layerPrefix = nullToEmpty (this.configuration.getLooperLayerPrefix ());
        final ITrack monitor = childBank.getItem (MONITOR);
        final ITrack template = childBank.getItem (TEMPLATE);

        // 3. child[0] is exactly the monitor: an audio track with the configured name.
        if (monitor.getType () != ChannelType.AUDIO || !monitor.getName ().equals (monitorName))
            return LooperColumnStatus.MISCONFIGURED;
        // 4. child[1] is exactly the template: an empty audio track with the configured name.
        if (template.getType () != ChannelType.AUDIO || !template.getName ().equals (templateName))
            return LooperColumnStatus.MISCONFIGURED;
        if (hasAnyContent (template))
            return LooperColumnStatus.MISCONFIGURED;

        // 5. Every child between the template and the group master must be a valid audio layer. The
        // group master is always the trailing child (type MASTER) - stop there and ignore it.
        final boolean pending = this.duplicationPending[column];
        for (int i = CHILD2; i < childBank.getPageSize (); i++)
        {
            final ITrack child = childBank.getItem (i);
            if (!child.doesExist () || child.getType () == ChannelType.MASTER)
                break;
            if (child.getType () != ChannelType.AUDIO)
                return LooperColumnStatus.MISCONFIGURED;
            final String name = child.getName ();
            // A freshly duplicated template, not yet renamed, is allowed while a duplication is pending.
            if (pending && name.equals (templateName))
                continue;
            if (!monitorName.isEmpty () && name.contains (monitorName))
                return LooperColumnStatus.MISCONFIGURED;
            if (!templateName.isEmpty () && name.contains (templateName))
                return LooperColumnStatus.MISCONFIGURED;
            if (!name.contains (layerPrefix))
                return LooperColumnStatus.MISCONFIGURED;
        }
        return LooperColumnStatus.VALID;
    }


    /** Finalize a pending template duplicate, or ensure the first layer exists. */
    private void finalizeOrEnsure (final int column)
    {
        if (this.duplicationPending[column])
        {
            // The fresh template copy appears at child[2] as an audio track named like the template -
            // rename + arm it, disarm child[4], and clear the flag. The audio-type guard guarantees
            // we never touch the group master (which is type MASTER, not AUDIO).
            final ITrack newLayer = this.child (column, CHILD2);
            final String templateName = nullToEmpty (this.configuration.getLooperTemplateName ());
            if (newLayer.getType () == ChannelType.AUDIO && newLayer.getName ().equals (templateName))
            {
                // Use the number captured before the duplicate (when the bank was stable) - reading
                // it now would race the not-yet-settled track insertion.
                newLayer.setName (nullToEmpty (this.configuration.getLooperLayerPrefix ()) + " " + this.pendingLayerNumber[column]);
                if (this.armedColumns[column])
                    newLayer.setRecArm (true);
                this.disarmLayer (this.child (column, CHILD4));
                this.duplicationPending[column] = false;
            }
            return;
        }
        // Ensure an empty staging layer at child[2]: duplicate the template when child[2] is not an
        // empty audio track - i.e. it is the group master / absent (no layers yet), OR it already
        // holds content (e.g. the empty staging layer was deleted, sliding a recorded layer up into
        // child[2]). Without this we would record over that existing layer.
        final ITrack child2 = this.child (column, CHILD2);
        if (child2.getType () != ChannelType.AUDIO || hasAnyContent (child2))
        {
            this.pendingLayerNumber[column] = this.nextLayerNumber (column);
            this.child (column, TEMPLATE).duplicate ();
            this.duplicationPending[column] = true;
            return;
        }

        // child[2] is the empty staging layer. If it is now the only layer (child[3] is the group
        // master or absent - every content layer below it was deleted), reset its number to 1 so the
        // next loop starts fresh.
        if (this.child (column, CHILD3).getType () != ChannelType.AUDIO)
        {
            final String firstLayerName = nullToEmpty (this.configuration.getLooperLayerPrefix ()) + " 1";
            if (!child2.getName ().equals (firstLayerName))
                child2.setName (firstLayerName);
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
        return this.childBankByColumn;
    }


    /**
     * Get the cached looper status of the given APC column (0-based).
     *
     * @param column The column index on the surface
     * @return The status
     */
    public LooperColumnStatus getColumnStatus (final int column)
    {
        if (column < 0 || column >= this.statusByColumn.length)
            return LooperColumnStatus.NONE;
        return this.statusByColumn[column];
    }


    /**
     * Whether the column is a looper group at all (valid or misconfigured) - i.e. its name matches.
     *
     * @param column The surface column index
     * @return True if the column is a looper group
     */
    public boolean isLooperColumn (final int column)
    {
        return this.getColumnStatus (column) != LooperColumnStatus.NONE;
    }


    /**
     * Whether the given looper column is armed for recording (per-column state).
     *
     * @param column The surface column index
     * @return True if armed
     */
    public boolean isColumnArmed (final int column)
    {
        return column >= 0 && column < this.armedColumns.length && this.armedColumns[column];
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
        if (column < 0 || column >= this.armedColumns.length)
            return;
        this.armedColumns[column] = !this.armedColumns[column];
        if (this.armedColumns[column])
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
        if (this.getColumnStatus (column) != LooperColumnStatus.VALID)
            return false;

        final ITrack group = this.trackBank.getItem (column);
        final ISlot groupSlot = group.getSlotBank ().getItem (scene);

        final ITrack recordingLayer = this.recordingLayerAtScene (column, scene);
        if (recordingLayer != null)
        {
            final ISlot recordingSlot = recordingLayer.getSlotBank ().getItem (scene);
            // Finish: launching the still-recording slot ends the recording (Bitwig then plays it
            // once). Schedule the auto-looper to re-launch this exact clip into a loop the instant
            // recording stops.
            if (this.launchFixer != null)
                this.launchFixer.scheduleRelaunch (recordingLayer.getPosition (), recordingSlot.getPosition ());
            recordingSlot.launch (true, false);
            return true;
        }

        if (this.armedColumns[column])
        {
            final ITrack target = this.child (column, CHILD2);
            if (target.getType () != ChannelType.AUDIO)
                return true; // no layer staged yet; a rescan will create one
            // Keep the existing loop playing (without restarting it) so the overdub is heard.
            if (!groupSlot.isPlaying () && !groupSlot.isPlayingQueued ())
                groupSlot.launch (true, false);
            target.getSlotBank ().getItem (scene).startRecording ();
            // Capture the next layer number now, while the bank is stable (child[2] is the layer we
            // just started recording). Then duplicate the template: this pushes the recording layer
            // to child[3]; the new child[2] is renamed (to that number) + armed on finalize.
            this.pendingLayerNumber[column] = this.nextLayerNumber (column);
            this.child (column, TEMPLATE).duplicate ();
            this.duplicationPending[column] = true;
            return true;
        }

        if (groupSlot.hasContent ())
        {
            groupSlot.launch (true, false);
            return true;
        }

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
        if (column < 0 || column >= this.statusByColumn.length)
            return;
        // Stopping is an explicit "stay stopped" intent - cancel any pending re-launches for this
        // column's layers so a just-finished recording is not restarted.
        if (this.launchFixer != null)
        {
            final ITrackBank childBank = this.childBankByColumn[column];
            for (int i = 0; i < childBank.getPageSize (); i++)
                this.launchFixer.cancelScheduledForTrack (childBank.getItem (i).getPosition ());
        }
        this.trackBank.getItem (column).stop (false);
    }


    /**
     * Whether any loop of a looper column is currently playing (or queued to play).
     *
     * @param column The surface column index
     * @return True if a loop is playing/queued
     */
    public boolean isColumnPlaying (final int column)
    {
        if (column < 0 || column >= this.statusByColumn.length)
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


    /**
     * The next layer number: one more than the highest layer number currently among the children.
     * Must be called while the bank is stable (i.e. before a duplicate inserts a track), since it
     * scans the whole child window rather than trusting a single index. The group master (type
     * MASTER) is the trailing child - stop there.
     */
    private int nextLayerNumber (final int column)
    {
        final ITrackBank childBank = this.childBankByColumn[column];
        int max = 0;
        for (int i = CHILD2; i < childBank.getPageSize (); i++)
        {
            final ITrack child = childBank.getItem (i);
            if (child.getType () == ChannelType.MASTER)
                break;
            if (child.getType () == ChannelType.AUDIO)
                max = Math.max (max, parseTrailingNumber (child.getName ()));
        }
        return max + 1;
    }


    private ITrack child (final int column, final int index)
    {
        return this.childBankByColumn[column].getItem (index);
    }


    private void armLayer (final ITrack track)
    {
        if (track.getType () == ChannelType.AUDIO)
            track.setRecArm (true);
    }


    private void disarmLayer (final ITrack track)
    {
        if (track.getType () == ChannelType.AUDIO)
            track.setRecArm (false);
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
