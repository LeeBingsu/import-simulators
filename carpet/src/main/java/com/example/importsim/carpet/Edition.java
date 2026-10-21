package com.example.importsim.carpet;

/**
 * Import Carpet Maps: the same mod as Import Simulators, pointed at the carpet-map release.
 * Every other source file is shared with the root project; see carpet/build.gradle.
 */
final class Edition {

    /** Mod id, and the stem of its config, log and temp file names. */
    static final String ID = "import-carpet-maps";
    static final String LOG = "[" + ID + "] ";

    /** The world-select button and the list screen's title. */
    static final String NAME = "Import Carpet Maps";
    /** What the list holds, as the player reads it ("No carpet maps match …"). */
    static final String NOUN = "carpet maps";
    /** The catalog file listing this edition's maps. */
    static final String MAPS_FILE = "carpet-maps.json";
    /** Carpet maps need no vexbot kits. */
    static final boolean KITS = false;
    /** Below Import Simulators' button, so both mods can be installed together. */
    static final int BUTTON_Y = 32;

    private Edition() {
    }
}
