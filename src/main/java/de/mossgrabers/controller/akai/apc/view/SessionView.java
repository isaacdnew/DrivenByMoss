// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.akai.apc.view;

import de.mossgrabers.controller.akai.apc.APCConfiguration;
import de.mossgrabers.controller.akai.apc.RecordedClipLaunchFixer;
import de.mossgrabers.controller.akai.apc.controller.APCColorManager;
import de.mossgrabers.controller.akai.apc.controller.APCControlSurface;
import de.mossgrabers.controller.akai.apc.looper.LooperColumnStatus;
import de.mossgrabers.controller.akai.apc.looper.LooperManager;
import de.mossgrabers.framework.controller.ButtonID;
import de.mossgrabers.framework.controller.grid.LightInfo;
import de.mossgrabers.framework.daw.DAWColor;
import de.mossgrabers.framework.daw.IModel;
import de.mossgrabers.framework.daw.data.IScene;
import de.mossgrabers.framework.daw.data.ISlot;
import de.mossgrabers.framework.daw.data.ITrack;
import de.mossgrabers.framework.daw.data.bank.ISceneBank;
import de.mossgrabers.framework.featuregroup.AbstractFeatureGroup;
import de.mossgrabers.framework.utils.ButtonEvent;
import de.mossgrabers.framework.utils.Pair;
import de.mossgrabers.framework.view.AbstractSessionView;


/**
 * Session view.
 *
 * @author Jürgen Moßgraber
 */
public class SessionView extends AbstractSessionView<APCControlSurface, APCConfiguration>
{
    private final LooperManager           looperManager;
    private final RecordedClipLaunchFixer launchFixer;
    private final LightInfo               looperMisconfiguredColor;


    /**
     * Constructor.
     *
     * @param surface The surface
     * @param model The model
     * @param looperManager The looper manager (may be null if the looper feature is not wired)
     * @param launchFixer The launch fixer that re-loops just-recorded clips (may be null)
     */
    public SessionView (final APCControlSurface surface, final IModel model, final LooperManager looperManager, final RecordedClipLaunchFixer launchFixer)
    {
        super ("Session", surface, model, 5, 8, surface.isMkII ());

        this.looperManager = looperManager;
        this.launchFixer = launchFixer;
        // Misconfigured looper groups blink fast between magenta and orchid (purple) to stand out.
        this.looperMisconfiguredColor = surface.isMkII () ? new LightInfo (APCColorManager.APC_MKII_COLOR_MAGENTA, APCColorManager.APC_MKII_COLOR_ORCHID, true) : new LightInfo (APCColorManager.APC_COLOR_RED, APCColorManager.APC_COLOR_RED_BLINK, true);

        if (surface.isMkII ())
        {
            final LightInfo isRecording = new LightInfo (APCColorManager.APC_MKII_COLOR_RED_HI, APCColorManager.APC_MKII_COLOR_RED_HI, false);
            final LightInfo isRecordingQueued = new LightInfo (APCColorManager.APC_MKII_COLOR_RED_HI, APCColorManager.APC_MKII_COLOR_RED_HI, true);
            final LightInfo isPlaying = new LightInfo (APCColorManager.APC_MKII_COLOR_GREEN_HI, APCColorManager.APC_MKII_COLOR_GREEN_HI, false);
            final LightInfo isPlayingQueued = new LightInfo (APCColorManager.APC_MKII_COLOR_GREEN_HI, APCColorManager.APC_MKII_COLOR_GREEN_HI, true);
            final LightInfo isStopQueued = new LightInfo (APCColorManager.APC_MKII_COLOR_GREEN_HI, APCColorManager.APC_MKII_COLOR_GREEN_HI, true);
            final LightInfo hasContent = new LightInfo (APCColorManager.APC_MKII_COLOR_AMBER, APCColorManager.APC_MKII_COLOR_WHITE, false);
            final LightInfo noContent = new LightInfo (APCColorManager.APC_MKII_COLOR_BLACK, -1, false);
            final LightInfo recArmed = new LightInfo (APCColorManager.APC_MKII_COLOR_RED_LO, -1, false);
            final LightInfo isMuted = new LightInfo (APCColorManager.APC_MKII_COLOR_GREY_LO, -1, false);
            this.setColors (isRecording, isRecordingQueued, isPlaying, isPlayingQueued, isStopQueued, hasContent, noContent, recArmed, isMuted);
        }
        else
        {
            final LightInfo isRecording = new LightInfo (APCColorManager.APC_COLOR_RED, -1, false);
            final LightInfo isRecordingQueued = new LightInfo (APCColorManager.APC_COLOR_RED, APCColorManager.APC_COLOR_RED_BLINK, false);
            final LightInfo isPlaying = new LightInfo (APCColorManager.APC_COLOR_GREEN, -1, false);
            final LightInfo isPlayingQueued = new LightInfo (APCColorManager.APC_COLOR_GREEN, APCColorManager.APC_COLOR_GREEN_BLINK, false);
            final LightInfo isStopQueued = new LightInfo (APCColorManager.APC_COLOR_GREEN, APCColorManager.APC_COLOR_GREEN_BLINK, false);
            final LightInfo hasContent = new LightInfo (APCColorManager.APC_COLOR_YELLOW, APCColorManager.APC_COLOR_YELLOW_BLINK, false);
            final LightInfo noContent = new LightInfo (APCColorManager.APC_COLOR_BLACK, -1, false);
            final LightInfo recArmed = new LightInfo (APCColorManager.APC_COLOR_BLACK, -1, false);
            final LightInfo isMuted = new LightInfo (APCColorManager.APC_COLOR_BLACK, -1, false);
            this.setColors (isRecording, isRecordingQueued, isPlaying, isPlayingQueued, isStopQueued, hasContent, noContent, recArmed, isMuted);
        }
    }


    /** {@inheritDoc} */
    @Override
    public String getButtonColorID (final ButtonID buttonID)
    {
        final int scene = buttonID.ordinal () - ButtonID.SCENE1.ordinal ();
        if (scene < 0 || scene >= 8)
            return AbstractFeatureGroup.BUTTON_COLOR_OFF;

        final ISceneBank sceneBank = this.model.getSceneBank ();
        final IScene s = sceneBank.getItem (scene);
        if (!s.doesExist ())
            return AbstractSessionView.COLOR_SCENE_OFF;

        if (s.isSelected ())
            return AbstractSessionView.COLOR_SELECTED_SCENE;

        return this.useClipColor ? DAWColor.getColorID (s.getColor ()) : AbstractSessionView.COLOR_SCENE;
    }


    /** {@inheritDoc} */
    @Override
    public void onGridNote (final int note, final int velocity)
    {
        // Birds-eye-view navigation
        if (this.isBirdsEyeActive ())
        {
            if (velocity == 0)
                return;

            final int index = note - 36;
            final int x = index % this.columns;
            final int y = this.rows - 1 - index / this.columns;

            this.onGridNoteBirdsEyeView (x, y, 0);
            return;
        }

        // Looper columns drive their nested child tracks instead of the visible group track. A
        // misconfigured looper column consumes the press but does nothing (no default behavior).
        if (this.looperManager != null)
        {
            final Pair<Integer, Integer> pad = this.getPad (note);
            if (pad != null && this.looperManager.isLooperColumn (pad.getKey ().intValue ()))
            {
                final int column = pad.getKey ().intValue ();
                final int scene = pad.getValue ().intValue ();
                if (velocity != 0 && this.looperManager.getColumnStatus (column) == LooperColumnStatus.VALID)
                {
                    // Hold the column's Clip Stop button + press a pad = remove the newest layer at
                    // that scene. isButtonCombination consumes the button's UP event, so the column
                    // is not stopped when Clip Stop is released (as the stock delete-slot combo does).
                    if (this.isButtonCombination (ButtonID.get (ButtonID.ROW6_1, column)))
                        this.looperManager.removeLastLayerAtScene (column, scene);
                    else
                        this.looperManager.handlePad (column, scene);
                }
                return;
            }
        }

        // General (non-looper) clips: if this press finishes a recording, schedule that clip to be
        // re-launched into a loop. Looper columns are handled inside handlePad above.
        if (velocity != 0 && this.launchFixer != null)
        {
            final Pair<Integer, Integer> pad = this.getPad (note);
            if (pad != null)
            {
                final ITrack track = this.model.getCurrentTrackBank ().getItem (pad.getKey ().intValue ());
                final ISlot slot = track.getSlotBank ().getItem (pad.getValue ().intValue ());
                if (slot.isRecording () || slot.isRecordingQueued ())
                    this.launchFixer.scheduleRelaunch (track.getPosition (), slot.getPosition ());
            }
        }

        super.onGridNote (note, velocity);
    }


    /** {@inheritDoc} */
    @Override
    public void onButton (final ButtonID buttonID, final ButtonEvent event, final int velocity)
    {
        super.onButton (buttonID, event, velocity);

        if (ButtonID.isSceneButton (buttonID) && event == ButtonEvent.UP && this.surface.isShiftPressed ())
            this.setAlternateInteractionUsed (true);
    }


    /** {@inheritDoc} */
    @Override
    protected boolean isSceneSelectAction ()
    {
        // Sadly, no additional button available
        return false;
    }


    /** {@inheritDoc} */
    @Override
    protected boolean handleButtonCombinations (final ITrack track, final ISlot slot)
    {
        if (super.handleButtonCombinations (track, slot))
            return true;

        final int index = track.getIndex ();
        if (index < 0)
            return true;

        // Duplicate the slot with Select button
        if (this.isButtonCombination (ButtonID.get (ButtonID.ROW1_1, index)))
        {
            slot.duplicate ();
            return true;
        }

        // Delete the slot with Stop Clip button
        if (this.isButtonCombination (ButtonID.get (ButtonID.ROW6_1, index)))
        {
            slot.remove ();
            return true;
        }

        return false;
    }


    /** {@inheritDoc} */
    @Override
    protected void drawPad (final ISlot slot, final int x, final int y, final boolean isArmed)
    {
        // The APC cannot use session flip (rows != columns), so x is always the track column and y
        // the scene here.
        if (this.looperManager != null)
        {
            final LooperColumnStatus status = this.looperManager.getColumnStatus (x);
            if (status == LooperColumnStatus.MISCONFIGURED)
            {
                final LightInfo info = this.looperMisconfiguredColor;
                this.surface.getPadGrid ().lightEx (x, y + this.getYOffset (), info.getColor (), info.getBlinkColor (), info.isFast ());
                return;
            }
            if (status == LooperColumnStatus.VALID)
            {
                // Paint the looper pad exactly like a normal clip pad. The slot is the group track's
                // own slot (which Bitwig aggregates across the layers); only the armed state is the
                // per-column looper arm.
                super.drawPad (slot, x, y, this.looperManager.isColumnArmed (x));
                return;
            }
        }

        super.drawPad (slot, x, y, isArmed);
    }
}