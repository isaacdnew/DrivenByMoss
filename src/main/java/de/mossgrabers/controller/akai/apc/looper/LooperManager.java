// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.akai.apc.looper;

import java.util.Arrays;

import de.mossgrabers.controller.akai.apc.APCConfiguration;
import de.mossgrabers.framework.daw.IHost;
import de.mossgrabers.framework.daw.IModel;
import de.mossgrabers.framework.daw.data.ISlot;
import de.mossgrabers.framework.daw.data.ITrack;
import de.mossgrabers.framework.daw.data.bank.ISlotBank;
import de.mossgrabers.framework.daw.data.bank.ITrackBank;


/**
 * Detects and validates audio-overdub looper groups, and exposes their status per APC track column.
 *
 * <p>
 * A looper group is a group track whose name contains the configured substring. It must contain a
 * child track named like the configured monitor track and an (empty, audio-capable) child track
 * named like the configured spawn track.
 * </p>
 *
 * <p>
 * Bitwig only allows track banks to be created during driver initialization, so we pre-create one
 * child track bank per surface column (each obtained from the corresponding main-bank track via
 * {@link IModel#createChildTrackBank}). As the main bank scrolls, each child bank follows its column
 * and exposes that column track's <i>direct</i> children - robust against group nesting, with no
 * reliance on flat-list ordering or {@code hasParent()}. A column is a looper group when its visible
 * track is a group whose name matches; it is valid when its children include the monitor track and
 * an empty, audio-capable spawn track. Routing cannot be validated (the controller API cannot read
 * it) - that remains the user's responsibility.
 * </p>
 *
 * @author Jürgen Moßgraber
 */
public class LooperManager
{
    private static final int           MAX_CHILD_TRACKS   = 8;

    private final IHost                host;
    private final APCConfiguration     configuration;
    private final ITrackBank           trackBank;
    private final ITrackBank []        childBankByColumn;
    private final LooperColumnStatus [] statusByColumn;


    /**
     * Constructor. Must be called during driver initialization (it creates track banks).
     *
     * @param model The model
     * @param host The host
     * @param configuration The configuration
     * @param numScenes The number of scenes (slots per track) for the child banks
     */
    public LooperManager (final IModel model, final IHost host, final APCConfiguration configuration, final int numScenes)
    {
        this.host = host;
        this.configuration = configuration;
        this.trackBank = model.getTrackBank ();

        final int columns = this.trackBank.getPageSize ();
        this.childBankByColumn = new ITrackBank [columns];
        this.statusByColumn = new LooperColumnStatus [columns];
        Arrays.fill (this.statusByColumn, LooperColumnStatus.NONE);
        for (int column = 0; column < columns; column++)
        {
            final ITrackBank childBank = model.createChildTrackBank (this.trackBank.getItem (column), MAX_CHILD_TRACKS, numScenes);
            // Not shown on the surface, so nothing else subscribes its data - enable it explicitly.
            childBank.enableObservers (true);
            this.childBankByColumn[column] = childBank;
        }
    }


    /**
     * Start the periodic re-validation of the visible columns.
     */
    public void start ()
    {
        // Re-validate only when something that could change a column's looper status changes: a
        // track name (group/spawn/monitor rename, or add/remove shifting names), the main bank's
        // page (scroll), a child bank's contents (children added/removed/renamed), or a looper
        // setting. No periodic polling.
        this.trackBank.addNameObserver ( (index, name) -> this.rescan ());
        this.trackBank.addPageObserver (this::rescan);
        for (int column = 0; column < this.statusByColumn.length; column++)
        {
            // A column track becoming/ceasing to be a group, or changing audio capability, changes
            // its type - re-validate on that too (covers changes that do not touch any name).
            this.trackBank.getItem (column).addTrackTypeObserver (type -> this.rescan ());
            final ITrackBank childBank = this.childBankByColumn[column];
            childBank.addNameObserver ( (index, name) -> this.rescan ());
            for (int i = 0; i < childBank.getPageSize (); i++)
                childBank.getItem (i).addTrackTypeObserver (type -> this.rescan ());
        }
        this.configuration.addLooperSettingsObserver (this::rescan);

        this.rescan ();
    }


    /**
     * Re-validate every visible column from its (live) child bank.
     */
    public void rescan ()
    {
        for (int column = 0; column < this.statusByColumn.length; column++)
            this.statusByColumn[column] = this.computeColumnStatus (column);
    }


    private LooperColumnStatus computeColumnStatus (final int column)
    {
        if (!this.configuration.isLooperEnabled ())
            return LooperColumnStatus.NONE;

        final ITrack track = this.trackBank.getItem (column);
        if (!track.doesExist () || !this.isLooperGroupName (track))
            return LooperColumnStatus.NONE;

        return this.areChildrenValid (this.childBankByColumn[column]) ? LooperColumnStatus.VALID : LooperColumnStatus.MISCONFIGURED;
    }


    private boolean areChildrenValid (final ITrackBank childBank)
    {
        final String spawnName = this.configuration.getLooperSpawnName ();
        final String monitorName = this.configuration.getLooperMonitorName ();

        boolean hasMonitor = false;
        boolean hasAudioSpawn = false;
        final int count = Math.min (childBank.getPageSize (), childBank.getItemCount ());
        for (int i = 0; i < count; i++)
        {
            final ITrack child = childBank.getItem (i);
            if (!child.doesExist ())
                continue;
            final String name = child.getName ();
            // The spawn is the loop track; it holds the recorded loops, so it need not be empty -
            // only present and able to hold audio.
            if (name.equals (spawnName))
                hasAudioSpawn = child.canHoldAudioData ();
            else if (name.equals (monitorName))
                hasMonitor = true;
        }
        return hasMonitor && hasAudioSpawn;
    }


    /**
     * Get the loop (spawn) track of a column, or null if the column is not a valid looper column.
     *
     * @param column The surface column index
     * @return The spawn track or null
     */
    private ITrack getSpawnTrack (final int column)
    {
        if (column < 0 || column >= this.childBankByColumn.length)
            return null;
        final ITrackBank childBank = this.childBankByColumn[column];
        final String spawnName = this.configuration.getLooperSpawnName ();
        final int count = Math.min (childBank.getPageSize (), childBank.getItemCount ());
        for (int i = 0; i < count; i++)
        {
            final ITrack child = childBank.getItem (i);
            if (child.doesExist () && child.getName ().equals (spawnName))
                return child;
        }
        return null;
    }


    /**
     * Get the slot that represents a looper pad for display, or null if the column is not a valid
     * looper column / it does not exist. The pad is painted by reusing the normal session-view clip
     * coloring on this slot, so a looper column inherits all clip color behavior automatically.
     * <p>
     * Today this is simply the single loop track's (spawn's) slot. When overdub layers are added,
     * this will return an aggregate {@link ISlot} (e.g. an {@code EmptySlot} subclass) that combines
     * the layers' slots at this scene - and the view code does not change.
     *
     * @param column The surface column index
     * @param scene The scene index
     * @return The representative slot or null
     */
    public ISlot getDisplaySlot (final int column, final int scene)
    {
        final ITrack spawn = this.getSpawnTrack (column);
        if (spawn == null)
            return null;
        final ISlot slot = spawn.getSlotBank ().getItem (scene);
        return slot.doesExist () ? slot : null;
    }


    /**
     * Whether the loop (spawn) track of a column is record-armed - used as the "is armed" input to
     * the normal pad coloring.
     *
     * @param column The surface column index
     * @return True if armed
     */
    public boolean isColumnArmed (final int column)
    {
        final ITrack spawn = this.getSpawnTrack (column);
        return spawn != null && spawn.isRecArm ();
    }


    /**
     * Handle a press on a looper pad (one scene of a looper column): empty -&gt; start recording a
     * new free-length loop; recording -&gt; finish recording at the quantization boundary and loop;
     * has content -&gt; (re)launch it. Stopping a playing loop is done with the column's stop button.
     *
     * @param column The surface column index
     * @param scene The scene index
     * @return True if the press was handled
     */
    public boolean handlePad (final int column, final int scene)
    {
        final ITrack spawn = this.getSpawnTrack (column);
        if (spawn == null)
            return false;
        final ISlot slot = spawn.getSlotBank ().getItem (scene);
        if (!slot.doesExist ())
            return false;

        if (slot.isRecording () || slot.isRecordingQueued ())
        {
            // Finish: relaunch the recording slot - this ends recording at the launch-quantization
            // boundary and transitions the clip into looping playback. Disarm once recording clears.
            slot.launch (true, false);
            this.scheduleDisarmWhenIdle (spawn, 200);
        }
        else if (slot.hasContent ())
        {
            // Always (re)launch - stopping is handled by the column's stop button.
            slot.launch (true, false);
        }
        else
        {
            // Empty: arm the loop track and start recording a new free-length loop. Bitwig quantizes
            // the start to the project's launch quantization.
            spawn.setRecArm (true);
            slot.startRecording ();
        }
        return true;
    }


    /**
     * Stop the playing loop of a looper column (driven by the column's stop button).
     *
     * @param column The surface column index
     */
    public void stopColumn (final int column)
    {
        final ITrack spawn = this.getSpawnTrack (column);
        if (spawn != null)
            spawn.stop (false);
    }


    /**
     * Whether any loop of a looper column is currently playing (or queued to play).
     *
     * @param column The surface column index
     * @return True if a loop is playing/queued
     */
    public boolean isColumnPlaying (final int column)
    {
        final ITrack spawn = this.getSpawnTrack (column);
        if (spawn == null)
            return false;
        final ISlotBank slotBank = spawn.getSlotBank ();
        final int count = Math.min (slotBank.getPageSize (), slotBank.getItemCount ());
        for (int i = 0; i < count; i++)
        {
            final ISlot slot = slotBank.getItem (i);
            if (slot.isPlaying () || slot.isPlayingQueued ())
                return true;
        }
        return false;
    }


    private void scheduleDisarmWhenIdle (final ITrack track, final int attemptsLeft)
    {
        this.host.scheduleTask ( () -> {
            boolean stillRecording = false;
            final ISlotBank slotBank = track.getSlotBank ();
            final int count = Math.min (slotBank.getPageSize (), slotBank.getItemCount ());
            for (int i = 0; i < count; i++)
            {
                final ISlot slot = slotBank.getItem (i);
                if (slot.isRecording () || slot.isRecordingQueued ())
                {
                    stillRecording = true;
                    break;
                }
            }
            if (stillRecording && attemptsLeft > 0)
            {
                this.scheduleDisarmWhenIdle (track, attemptsLeft - 1);
                return;
            }
            track.setRecArm (false);
        }, 50);
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


    private boolean isLooperGroupName (final ITrack track)
    {
        if (!track.isGroup ())
            return false;
        final String groupSubstring = nullToEmpty (this.configuration.getLooperGroupName ());
        return !groupSubstring.isEmpty () && track.getName ().contains (groupSubstring);
    }


    private static String nullToEmpty (final String text)
    {
        return text == null ? "" : text;
    }


    /**
     * TEMPORARY debug helper: print every visible column, its child bank contents, and the computed
     * status to the controller console, to diagnose looper detection/validation.
     */
    public void dumpToConsole ()
    {
        this.host.println ("=== Looper dump: enabled=" + this.configuration.isLooperEnabled () + " group='" + this.configuration.getLooperGroupName () + "' spawn='" + this.configuration.getLooperSpawnName () + "' monitor='" + this.configuration.getLooperMonitorName () + "'");
        for (int column = 0; column < this.statusByColumn.length; column++)
        {
            final ITrack track = this.trackBank.getItem (column);
            if (!track.doesExist ())
                continue;
            this.host.println ("  col " + column + " name='" + track.getName () + "' isGroup=" + track.isGroup () + " status=" + this.computeColumnStatus (column));
            final ITrackBank childBank = this.childBankByColumn[column];
            for (int j = 0; j < childBank.getPageSize (); j++)
            {
                final ITrack c = childBank.getItem (j);
                if (!c.doesExist ())
                    continue;
                this.host.println ("       child[" + j + "] name='" + c.getName () + "' canHoldAudio=" + c.canHoldAudioData ());
            }
        }
    }
}
