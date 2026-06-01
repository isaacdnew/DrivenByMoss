// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.akai.apc;

import java.util.List;

import de.mossgrabers.framework.configuration.AbstractConfiguration;
import de.mossgrabers.framework.configuration.IEnumSetting;
import de.mossgrabers.framework.configuration.ISettingsUI;
import de.mossgrabers.framework.configuration.IStringSetting;
import de.mossgrabers.framework.controller.valuechanger.IValueChanger;
import de.mossgrabers.framework.daw.IHost;
import de.mossgrabers.framework.daw.constants.Capability;
import de.mossgrabers.framework.daw.midi.ArpeggiatorMode;
import de.mossgrabers.framework.view.Views;


/**
 * The configuration settings for APC.
 *
 * @author Jürgen Moßgraber
 */
public class APCConfiguration extends AbstractConfiguration
{
    private static final Views [] PREFERRED_NOTE_VIEWS =
    {
        Views.PLAY,
        Views.DRUM,
        Views.SEQUENCER,
        Views.RAINDROPS
    };

    private static final String   CATEGORY_LOOPER = "Looper";

    private final boolean         isMkII;

    private IEnumSetting           looperEnabledSetting;
    private IStringSetting         looperGroupNameSetting;
    private IStringSetting         looperSpawnNameSetting;
    private IStringSetting         looperMonitorNameSetting;


    /**
     * Constructor.
     *
     * @param host The DAW host
     * @param valueChanger The value changer
     * @param arpeggiatorModes The available arpeggiator modes
     * @param isMkII True if is MkII
     */
    public APCConfiguration (final IHost host, final IValueChanger valueChanger, final List<ArpeggiatorMode> arpeggiatorModes, final boolean isMkII)
    {
        super (host, valueChanger, arpeggiatorModes);

        this.isMkII = isMkII;
    }


    /** {@inheritDoc} */
    @Override
    public void init (final ISettingsUI globalSettings, final ISettingsUI documentSettings)
    {
        ///////////////////////////
        // Scale

        this.activateScaleSetting (documentSettings);
        this.activateScaleBaseSetting (documentSettings);
        this.activateScaleInScaleSetting (documentSettings);
        this.activateScaleLayoutSetting (documentSettings);

        ///////////////////////////
        // Note Repeat

        this.activateNoteRepeatSetting (documentSettings);

        ///////////////////////////
        // Session

        this.activateSelectClipOnLaunchSetting (globalSettings);
        this.activateDrawRecordStripeSetting (globalSettings);
        this.activateActionForRecArmedPad (globalSettings);

        ///////////////////////////
        // Play and Sequence

        this.activateQuantizeAmountSetting (globalSettings);
        this.activateStartupViewSetting (globalSettings, PREFERRED_NOTE_VIEWS);
        this.activateStartWithSessionViewSetting (globalSettings);
        if (this.isMkII)
        {
            this.activateTurnOffScalePadsSetting (globalSettings);
            this.activateShowPlayedChordsSetting (globalSettings);
        }

        ///////////////////////////
        // Transport

        this.activateBehaviourOnPauseSetting (globalSettings);

        ///////////////////////////
        // Drum Sequencer

        if (this.host.supports (Capability.HAS_DRUM_DEVICE))
            this.activateTurnOffEmptyDrumPadsSetting (globalSettings);

        ///////////////////////////
        // Workflow

        this.activateExcludeDeactivatedItemsSetting (globalSettings);
        this.activateNewClipLengthSetting (globalSettings);
        this.activateFootswitchSetting (globalSettings, 0, "Footswitch 1");
        if (!this.isMkII)
            this.activateFootswitchSetting (globalSettings, 1, "Footswitch 2");

        ///////////////////////////
        // Looper

        this.looperEnabledSetting = globalSettings.getEnumSetting ("Enable looper", CATEGORY_LOOPER, new String []
        {
            "Off",
            "On"
        }, "On");
        this.looperGroupNameSetting = globalSettings.getStringSetting ("Looper group name (matched as substring)", CATEGORY_LOOPER, 32, "LOOPER");
        this.looperSpawnNameSetting = globalSettings.getStringSetting ("Spawn track name", CATEGORY_LOOPER, 32, "Spawn");
        this.looperMonitorNameSetting = globalSettings.getStringSetting ("Monitor track name", CATEGORY_LOOPER, 32, "Monitor");
    }


    /**
     * Is the looper feature enabled?
     *
     * @return True if enabled
     */
    public boolean isLooperEnabled ()
    {
        return "On".equals (this.looperEnabledSetting.get ());
    }


    /**
     * Get the configured substring that marks a group track as a looper group.
     *
     * @return The substring
     */
    public String getLooperGroupName ()
    {
        return this.looperGroupNameSetting.get ();
    }


    /**
     * Get the configured required name of the spawn template track inside a looper group.
     *
     * @return The spawn track name
     */
    public String getLooperSpawnName ()
    {
        return this.looperSpawnNameSetting.get ();
    }


    /**
     * Get the configured required name of the monitor/source track inside a looper group.
     *
     * @return The monitor track name
     */
    public String getLooperMonitorName ()
    {
        return this.looperMonitorNameSetting.get ();
    }
}
