# EK Save Editor v0.8.0 — US English Release

**EK Save Editor** is an independent Android save editor for *Exiled Kingdoms*, created by **old390**.

## What's new

- The entire Android interface is now translated into **American English (en-US)**.
- Menus, character selection, inventory actions, Town Hall vaults, confirmations, status messages, and error messages are available in English.
- GitHub README, usage instructions, and developer comments are now in English.
- Existing save-editing behavior, item IDs, and character isolation are preserved.

## Features

- Edit up to ten game character slots when present in the same backup, with separate saves for each character.
- Set gold, add and reset unspent attribute or skill points, and manage the active companion.
- Tap visual inventory slots to add, duplicate, delete, set quantities, or replace items.
- Respect non-stackable items: tomes and equipment use one unit per slot.
- Access and edit four Town Hall vaults and their 20-slot grids.
- Export a verified edited `EK.bak` file for restoration within the game.

## Installation

Download the **EK-Save-Editor-v0.8.0-debug.apk** asset, then install it on Android. This is a **debug-signed** APK built by GitHub Actions. Android may ask you to allow installation from your file manager or browser.

1. Make a fresh in-game `EK.bak` backup and keep the original.
2. Import it into EK Save Editor and select a character.
3. Make changes, review the selected character's saves, and export the edited file.
4. Copy `EK.bak` to `/storage/emulated/0/Android/data/net.fdgames.ek.android/files/EK.bak`.
5. Use the game's **RESTORE BACKUP** option.

**Always keep an unmodified backup.** This application is not affiliated with or endorsed by the developers of *Exiled Kingdoms*.
