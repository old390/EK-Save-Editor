# EK Save Editor — v0.8.0 (US English)

**An independent Android save editor for *Exiled Kingdoms*, created by [old390](https://github.com/old390).**

Edit and restore `EK.bak` backups without root, Shizuku, or a computer. The interface, dialogs, validation messages, and documentation are in **American English (en-US)**.

> **Important:** Back up your original `EK.bak` before making any changes. This app is not affiliated with, endorsed by, or maintained by the developers of *Exiled Kingdoms*.

## Download and install

Download the APK from [GitHub Releases](https://github.com/old390/EK-Save-Editor/releases). If a release is not available yet, use the latest successful [GitHub Actions](https://github.com/old390/EK-Save-Editor/actions) run and download the `EKSaveEditor-debug-apk` artifact ZIP. Extract `app-debug.apk` and install it on your Android phone.

**Build type:** The provided APK is a debug-signed build intended for community use and testing. Android may require you to allow installation from your file manager or browser. Keep your original save until you have confirmed a successful restore in the game.

## Features

| Feature | Description |
| --- | --- |
| Multiple characters | Discover up to 10 game character slots dynamically and select the character you want to edit |
| Character isolation | Each change affects only the selected character's save files, leaving other characters untouched |
| Gold | Set the amount of gold |
| Attribute points | View actual unspent points, add points, or safely reset unspent points |
| Skill points | View unspent points based on skill costs, add points, or safely reset them |
| Active companion | Edit the active companion's available attribute and skill points |
| Visual inventory | Tap item slots to add copies, set stack quantities, delete copies, or replace items |
| Item catalog | Search by item name or numeric ID; catalog is available offline |
| Non-stackable items | Tomes and equipment are limited to one copy per slot and shown in red |
| Town Hall vaults | Choose Vault 1–4, view their 20-slot grids, manage contents, and unlock vaults |
| Save verification | Validate edited saves and preserve unrelated characters, items, and files |
| Export | Save an updated `EK.bak` and restore it through the game |

### How to use

1. In *Exiled Kingdoms*, create a **fresh backup**. Keep an unmodified copy somewhere safe.
2. Open **EK Save Editor** and tap **Select EK.bak**.
3. If the backup has multiple characters, use **Choose Character** and select one. The editor lists only the characters present in the backup; missing character slots are not invented.
4. Edit gold, attributes, skills, companion stats, or items. Inventory and vault grids are interactive: tap an occupied slot for actions, or an empty slot to insert an item.
5. Use **Review All Saves for This Character** to inspect the edited values. You can switch to another character and edit their saves in the same session.
6. Tap **Export Edited EK.bak**. Then copy the edited file to:

   ```text
   /storage/emulated/0/Android/data/net.fdgames.ek.android/files/EK.bak
   ```

7. Open *Exiled Kingdoms* and select **RESTORE BACKUP**. Check that the final filename is exactly `EK.bak`; some Android file managers add suffixes such as `(1)`.

**The app modifies a backup file, not the game's active saves.** Changes only take effect after you restore the edited backup in the game.

## Safety and limitations

- **Character-aware editing:** A backup may contain multiple characters under `saves/0/`, `saves/1/`, `saves/2/`, etc. The editor groups them by folder and changes only the saves belonging to the selected character. Unselected character data and global files are retained.
- **Inventory differences:** Auto-saves and manual saves may have different inventory contents or slot positions. When a replacement would overwrite an item, the app shows what occupies that slot in each selected save and asks for confirmation.
- **Non-stackable items:** A new tome or piece of equipment uses one slot per copy. Items with unknown stacking behavior are conservatively treated as non-stackable to reduce the risk of corrupting the backup.
- **Available point totals:** The editor calculates attribute and skill points using level progress, recorded bonuses, and spent points. Unknown or modded skill costs may prevent a safe reset.
- **Town Hall vaults:** Each character's `vault`, `vault2`, `vault3`, and `vault4` containers are managed independently when present. A newly created character may not yet have vault data; the editor does not manufacture missing containers.
- **Unlocking vaults:** The vault unlock option sets the selected character's vault flags in the backup without charging gold. It is a save edit, not an in-game purchase.
- **Transactional behavior:** If an edit cannot be validated for the selected character's saves, the editor reports the issue instead of applying partial changes.
- **Visual icons:** Item grid icons are symbolic category indicators, not the original game's item artwork.
- **No warranty:** Save formats, mods, or future game updates may differ. Always verify changes in-game before overwriting your original backup.

## Build from source

The repository includes Android source code and a GitHub Actions workflow for automatic unit tests and debug APK builds.

- Java: 17 (Temurin)
- Android SDK: API 35
- Gradle: 8.9
- CI tasks: `:app:testDebugUnitTest :app:assembleDebug`
- Output: `app/build/outputs/apk/debug/app-debug.apk`

The CI artifact is automatically published after the tests and build succeed.

## Version history

**v0.8.0 — US English:** Complete translation of the user interface, dialogs, error messages, code documentation, and README. No change to save-file structure or item IDs.

**v0.7.0 — Multiple characters:** Character selector, character-specific editing, and recognition of saves without bonus point fields.

**v0.6.0 — Town Hall vaults:** Four selectable storage vaults, slot editing, and unlock support.

**v0.5.x — Inventory and points:** Interactive inventory grids, non-stackable item rules, duplicate-item handling, and companion point fixes.

## Credits and references

Created by **old390**. Independent community project.

- [Exiled Kingdoms community wiki — Level](https://www.exiledkingdoms.com/wiki/index.php/Level)
- [Exiled Kingdoms community wiki — Skill Table](https://www.exiledkingdoms.com/wiki/index.php?title=Skill_Table)
- [Community item catalog discussion](https://steamcommunity.com/app/788270/discussions/0/2515771867786715062/)

Please report problems through the repository's **Issues** tab. When reporting a bug, describe the steps to reproduce it; do not post personal save files publicly.
