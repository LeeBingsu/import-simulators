package com.example.importsim.carpet;

/**
 * Import Carpet Maps for 1.21.11: the same mod as Import Simulators, pointed at the carpet-map
 * release and the carpet-kits folder. Every other source file is shared with the root project;
 * see gradle/edition.gradle.
 */
final class Edition {

    /** The stem of this mod's config, log and temp file names. */
    static final String ID = "import-carpet-maps";
    static final String LOG = "[" + ID + "] ";

    /** The world-select button and the list screen's title. */
    static final String NAME = "Import Carpet Maps";
    /** What the list holds, as the player reads it ("No carpet maps match …"). */
    static final String NOUN = "carpet maps";
    /** The catalog file listing this edition's maps. */
    static final String MAPS_FILE = "carpet-maps.json";
    /** Whether this edition also syncs kits. */
    static final boolean KITS = true;
    /** The catalog file listing this edition's kits. */
    static final String KITS_FILE = "carpet-kits.json";
    /** The repo folder the kit files are served from. */
    static final String KITS_SOURCE = "carpet-kits";
    /** The folder in the game directory the kits go into (the config can override it). */
    static final String KITS_DIR = "carpet_kits";
    /** Below Import Simulators' button, so both mods can be installed together. */
    static final int BUTTON_Y = 32;

    private Edition() {
    }
}
