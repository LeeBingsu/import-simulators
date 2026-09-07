# Import Simulators

Fabric client mod. Adds an **Import Simulators** button to the singleplayer
**Select World** screen. It opens a list of every map folder in a Google Drive
folder with a checkbox each; **Download (N)** imports only the ticked maps into
`saves/`, then returns to the refreshed world list.

- Minecraft **1.21.11** (see *Other versions* below)
- **Client-side only.** Not needed on servers.
- Requires **Fabric Loader** + **Fabric API**.

Default Drive folder (from the request):
`https://drive.google.com/drive/folders/1idhELV0qMMFqgJhaJCYEzFeL5wtfekVH`

---

## Build

The Gradle **wrapper JAR is not included** (binary). Do one of:

- Open the folder in **IntelliJ IDEA** → it imports from `build.gradle` and
  generates the wrapper automatically. Then run the `build` Gradle task.
- Or, with Gradle installed:
  ```bash
  gradle wrapper --gradle-version 9.7.1   # fabric-loom 1.17.x needs Gradle >= 9.5
  ./gradlew build
  ```
- Or copy `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat` from
  the [Fabric example mod](https://github.com/FabricMC/fabric-example-mod),
  then `./gradlew build`.

Output jar: `build/libs/import-simulators-1.0.0.jar` → drop into `.minecraft/mods/`
(alongside the Fabric API jar).

Needs **JDK 21**.

### GitHub Actions

`.github/workflows/build.yml` builds on every push / PR. It provisions Gradle
directly (no wrapper jar needed), runs `gradle build`, and uploads the jar as a
workflow artifact (**Actions → run → Artifacts → `import-simulators`**). Push a
`vX.Y.Z` tag to also attach the jars to a GitHub Release.

## Other versions

`gradle.properties` holds **placeholder** `yarn_mappings` / `loader_version` /
`fabric_version` strings. Before the first build, open
<https://fabricmc.net/develop>, pick your exact Minecraft version, and paste the
four strings it shows. IntelliJ's Gradle sync will fail loudly if a version does
not resolve — that just means one string is wrong.

The code uses only long-stable APIs (`ScreenEvents`, `Screens.getButtons`,
`SelectWorldScreen`, `ButtonWidget`, `Screen.resize`). If a future MC snapshot
renames `SelectWorldScreen`, update the two references in
`ImportSimulatorsClient.java`; nothing else should need touching.

---

## Config

`config/import-simulators.json` (created on first run):

```json
{
  "folder": "https://drive.google.com/drive/folders/1idhELV0qMMFqgJhaJCYEzFeL5wtfekVH",
  "googleApiKey": "",
  "overwriteExisting": false
}
```

| Field | Meaning |
|---|---|
| `folder` | Drive folder URL **or** bare folder id. |
| `googleApiKey` | Optional. Blank = use the Drive web endpoint (zero setup, paginated). Set = use the official Drive API v3 with your own key. |
| `overwriteExisting` | `false` → import as `Name (1)`, `Name (2)`… when `saves/Name` exists. `true` → delete and replace. |

### How the download works

**Without an API key** (default) the mod lists folders through
`clients6.google.com/drive/v2beta` — the endpoint `drive.google.com`'s own web
app uses, reachable with Google's public web key plus an `X-Origin` header. It is
**paginated**, so there is no ~50-item cap, and needs zero setup. File bytes come
from `drive.usercontent.google.com` (handles the large-file virus-scan page).
Requirements / limits:

- The folder must be shared as **"Anyone with the link"**.
- Google can still rate-limit a busy IP (the mod retries with backoff).
- If the web endpoint ever refuses, the mod falls back to scraping
  `window['_DRIVE_ivd']` from the folder page (fragile, ~50 items/folder) and
  logs a warning so you know that map may be incomplete.

Verified end-to-end against the default folder: a 177-file `region/` that the old
scrape capped at 51 now downloads in full; a full 37 MB world writes to disk with
a valid `level.dat` and all region files.

**With an API key** the mod uses Drive API v3 instead. Getting a free key (no
billing):

1. <https://console.cloud.google.com/> → create a project.
2. **APIs & Services → Library →** enable **Google Drive API**.
3. **Credentials → Create credentials → API key.** Copy it.
4. Paste into `googleApiKey`. (Optionally restrict the key to the Drive API.)

The shared folder still has to be public for an API key (no OAuth) to read it.

---

## Behaviour notes

- Pick maps in the list (click a row to toggle; **All** / **None** buttons;
  scroll wheel for long lists). **Download (N)** starts the import.
- Import runs on a background thread; the select screen shows progress
  (`Importing 2/5: MyMap — r.0.0.mca`) and returns to the world list when done.
- Each map is downloaded into `saves/.import-simulators-tmp/` first, then moved
  into place only if it completed — no half-imported worlds on failure.
- Per-map failures are logged and skipped; the rest still import.
- **On any failure** (a map failed, or listing the folder failed) the mod copies
  the Drive folder link to your clipboard and the button changes to
  `N map(s) failed — Drive link copied, download by hand`. Open the link in a
  browser, download the missing map folders, and drop them into
  `.minecraft/saves/`. The exact folder path and the list of missing maps are in
  `logs/latest.log`. Clicking the button again retries.
- Nested subfolders inside a map folder are mirrored.
- Loose files at the top level of the Drive folder are ignored (only folders
  are treated as maps).
- Check `logs/latest.log` (`[import-simulators]`) for details.

## Layout

```
build.gradle, settings.gradle, gradle.properties
gradle/wrapper/gradle-wrapper.properties
gradlew, gradlew.bat
src/main/resources/fabric.mod.json
src/main/java/com/example/importsim/
    ImportSimulatorsClient.java   entrypoint, adds the button
    MapSelectScreen.java          checkbox list of maps + Download button
    Config.java                   config file + folder-id parsing
    GDrive.java                   Drive listing + file download (web endpoint / API / scrape)
    ImportTask.java               background download of the chosen maps + progress
```

MIT.
