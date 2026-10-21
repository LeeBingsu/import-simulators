package com.example.importsim;

/**
 * What sets this mod apart from its sibling build, Import Carpet Maps. The two share every other
 * source file; carpet/ compiles them into its own package next to its own copy of this class.
 */
final class Edition {

    /** Mod id, and the stem of its config, log and temp file names. */
    static final String ID = "import-simulators";
    static final String LOG = "[" + ID + "] ";

    /** The world-select button and the list screen's title. */
    static final String NAME = "Import Simulators";
    /** What the list holds, as the player reads it ("No simulators match …"). */
    static final String NOUN = "simulators";
    /** The catalog file listing this edition's maps. */
    static final String MAPS_FILE = "maps.json";
    /** Whether this edition also syncs vexbot kits. */
    static final boolean KITS = true;
    /** Where the button sits on the world-select screen; the editions stack so both fit. */
    static final int BUTTON_Y = 8;

    private Edition() {
    }
}
