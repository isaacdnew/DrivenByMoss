// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.akai.apc.looper;

import java.util.Arrays;

import de.mossgrabers.controller.akai.apc.APCConfiguration;
import de.mossgrabers.framework.daw.IHost;
import de.mossgrabers.framework.daw.IModel;
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
    private static final long          RESCAN_INTERVAL_MS = 750;

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
        this.scheduleRescan ();
    }


    private void scheduleRescan ()
    {
        this.host.scheduleTask ( () -> {
            this.rescan ();
            this.scheduleRescan ();
        }, RESCAN_INTERVAL_MS);
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
        boolean hasValidSpawn = false;
        final int count = Math.min (childBank.getPageSize (), childBank.getItemCount ());
        for (int i = 0; i < count; i++)
        {
            final ITrack child = childBank.getItem (i);
            if (!child.doesExist ())
                continue;
            final String name = child.getName ();
            if (name.equals (spawnName))
                hasValidSpawn = child.canHoldAudioData () && !hasAnyContent (child);
            else if (name.equals (monitorName))
                hasMonitor = true;
        }
        return hasMonitor && hasValidSpawn;
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


    private static boolean hasAnyContent (final ITrack track)
    {
        final ISlotBank slotBank = track.getSlotBank ();
        final int count = Math.min (slotBank.getPageSize (), slotBank.getItemCount ());
        for (int i = 0; i < count; i++)
        {
            if (slotBank.getItem (i).hasContent ())
                return true;
        }
        return false;
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
