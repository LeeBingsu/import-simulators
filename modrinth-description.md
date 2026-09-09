# Import Simulators

Adds an **Import Simulators** button to the singleplayer **Select World** screen. Click it to pick maps from a shared Google Drive folder and download them straight into your `saves/` folder — no manual unzipping, no file browsing.

## How it works

1. Open **Singleplayer**.
2. Click **Import Simulators** (top-left of the world list).
3. Tick the maps you want. Use **All** / **None**, and scroll for long lists. A map you already have shows a **re-download** tag so you know downloading it again will duplicate or replace it.
4. Hit **Download (N)**. A progress bar tracks bytes downloaded against the total size, with a live percentage; when it finishes you drop back into the world list with the new maps ready to play.

Each map is downloaded to a temporary folder first and only moved into `saves/` once it's complete, so a failed download never leaves a broken world. If a map already exists it's imported alongside as `Name (1)` (unless `overwriteExisting` is set).

## Import Kits

Click **Import Kits** to sync the VEX kit files into a `vexbot_kits` folder next to your `saves/` folder. The folder is created if it doesn't exist, and only files you're missing are downloaded — anything you already have is left alone.

## Setup

Works out of the box with the default Drive folders. To point it at your own, edit `config/import-simulators.json`:

```json
{
  "folder": "https://drive.google.com/drive/folders/YOUR_FOLDER_ID",
  "kitsFolder": "https://drive.google.com/drive/folders/YOUR_KITS_FOLDER_ID",
  "googleApiKey": "",
  "overwriteExisting": false
}
```

- **folder** – a Google Drive folder link or ID. The folder must be shared as **"Anyone with the link"** and contain one sub-folder per world.
- **kitsFolder** – a Google Drive folder link or ID for the kit files synced by **Import Kits**. Same sharing requirement as above.
- **googleApiKey** – optional. Leave blank for zero setup. Set your own [Drive API key](https://console.cloud.google.com/) for maximum reliability on very large folders.
- **overwriteExisting** – replace an existing save of the same name instead of keeping both.

## Requirements

- Fabric Loader + **Fabric API**
- Client-side only (not needed on servers)

## Notes

- Large map packs can take a while — a 40 MB world is roughly two minutes.
- If any map or kit file fails, the Drive link is copied to your clipboard and the details are written to `logs/latest.log` so you can grab it by hand.
