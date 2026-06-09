// Written by Jürgen Moßgraber - mossgrabers.de
// (c) 2017-2026
// Licensed under LGPLv3 - http://www.gnu.org/licenses/lgpl-3.0.txt

package de.mossgrabers.controller.akai.apc.looper;

/**
 * The looper-relevant status of an APC track column.
 *
 * @author Isaac Newcomb
 */
public enum LooperValidity
{
    /** The column is not a looper group. */
    NONE,
    /** The column is a looper group whose required child tracks are present and configured. */
    VALID,
    /** The column is a looper group but its required child tracks are missing/misconfigured. */
    MISCONFIGURED
}
