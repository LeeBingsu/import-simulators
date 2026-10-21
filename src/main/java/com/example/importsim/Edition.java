package com.example.importsim;

/**
 * What sets this mod apart from its sibling builds, Import Carpet Maps for 1.21.11 (carpet/) and
 * for 1.21 (carpet-1.21/). They share every other source file, compiling them into their own
 * package next to their own copy of this class (and, on 1.21, of VersionScreen).
 */
final class Edition {

    /** The stem of this mod's config, log and temp file names. */
    static final String ID = "import-simulators";
    static final String LOG = "[" + ID + "] ";

    /** The world-select button and the list screen's title. */
    static final String NAME = "Import Simulators";
    /** What the list holds, as the player reads it ("No simulators match …"). */
    static final String NOUN = "simulators";
    /** The catalog file listing this edition's maps. */
    static final String MAPS_FILE = "maps.json";
    /** Whether this edition also syncs kits. */
    static final boolean KITS = true;
    /** The catalog file listing this edition's kits. */
    static final String KITS_FILE = "kits.json";
    /** The repo folder the kit files are served from. */
    static final String KITS_SOURCE = "kits";
    /** The folder in the game directory the kits go into (the config can override it). */
    static final String KITS_DIR = "vexbot_kits";
    /** Where the button sits on the world-select screen; the editions stack so both fit. */
    static final int BUTTON_Y = 8;

    private Edition() {
    }
}
