// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.akai.apc;

import java.util.HashSet;
import java.util.Set;

import de.mossgrabers.framework.daw.ITransport;
import de.mossgrabers.framework.daw.data.ISlot;
import de.mossgrabers.framework.daw.data.ITrack;
import de.mossgrabers.framework.daw.data.bank.ISlotBank;


/**
 * Fixes the launch of a just-recorded clip-launcher clip so it loops as intended instead of playing
 * once. When a launcher recording stops, Bitwig can treat the clip as a one-shot: it plays a single
 * pass of its content and then stops (the slot shows a queued stop), even with the clip's loop
 * attribute on. Launching the <i>finished</i> clip, by contrast, loops it - so this re-launches it.
 *
 * <p>
 * NOTE: the exact condition that decides loop-vs-one-shot is NOT known. It reproduces on a plain
 * audio track with no controller script involved, tends to happen with very short recordings, and
 * longer recordings loop fine - but whether the boundary is a time/beat threshold, the recording
 * crossing a loop/quantization point, or some other Bitwig state is unconfirmed.
 * </p>
 *
 * <p>
 * The re-launch is GESTURE-DRIVEN, not state-driven: a slot is only re-launched if the press that
 * ended its recording explicitly {@link #scheduleRelaunch(int, int) scheduled} it. So stopping a
 * recording via the transport (spacebar), a stop-clip button, etc. does NOT re-launch it - only
 * finishing it by pressing the clip does. The scheduled identity is the clip's (track position,
 * scene index), so a horizontal or vertical scroll that re-targets a slot handle onto a different
 * clip cannot match. Group tracks are never re-launched (their slot is an aggregate; launching it
 * would launch the whole sub-scene), and a re-launch is skipped while the transport is stopped.
 * </p>
 *
 * @author Jürgen Moßgraber
 */
public class RecordedClipLaunchFixer
{
    private final ITransport transport;
    private final Set<Long>  scheduled = new HashSet<> ();


    /**
     * Constructor.
     *
     * @param transport The transport (used to skip re-launching while stopped)
     */
    public RecordedClipLaunchFixer (final ITransport transport)
    {
        this.transport = transport;
    }


    /**
     * Schedule a clip so that, when its in-progress recording next stops, it is re-launched into a
     * loop. Call this from the press that finishes the recording. The identity is harmless if it
     * never matches: if the clip is scrolled away or stopped by other means, it simply does nothing.
     *
     * @param trackPosition The recording track's absolute position
     * @param scenePosition The recording clip's absolute scene index
     */
    public void scheduleRelaunch (final int trackPosition, final int scenePosition)
    {
        this.scheduled.add (Long.valueOf (key (trackPosition, scenePosition)));
    }


    /**
     * Cancel any scheduled re-launches for the given track (across all scenes). Call this when the
     * track's clips are stopped by other means (e.g. a stop-column button), so a pending re-launch
     * from an earlier finish press does not restart them.
     *
     * @param trackPosition The track's absolute position
     */
    public void cancelScheduledForTrack (final int trackPosition)
    {
        this.scheduled.removeIf (k -> (int) (k.longValue () >> 32) == trackPosition);
    }


    /**
     * Watch every scene slot of the given track and re-launch a scheduled clip the instant its
     * recording stops. Must be called during driver initialization (it registers observers).
     *
     * @param track The track whose slots to watch
     */
    public void watch (final ITrack track)
    {
        final ISlotBank slotBank = track.getSlotBank ();
        for (int scene = 0; scene < slotBank.getPageSize (); scene++)
        {
            final ISlot slot = slotBank.getItem (scene);
            slot.addIsRecordingObserver (isRecording -> {
                if (isRecording.booleanValue ())
                    return;
                // Recording stopped. Act only if THIS clip was scheduled by the gesture that ended
                // it; consume the schedule either way so it fires at most once.
                if (!this.scheduled.remove (Long.valueOf (key (track.getPosition (), slot.getPosition ()))))
                    return;
                if (this.transport.isPlaying () && !track.isGroup () && slot.hasContent ())
                    slot.launchWithOptions ("none", "continue_or_synced");
            });
        }
    }


    private static long key (final int trackPosition, final int scenePosition)
    {
        return ((long) trackPosition << 32) | (scenePosition & 0xFFFFFFFFL);
    }
}
