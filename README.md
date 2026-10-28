# NES HD

NES HD is an NES emulator for Android, powered by the Mesen emulation core. It provides a mobile-friendly game library and fullscreen gameplay with support for high-resolution texture packs and RetroAchievements.

## Features

- NES, Famicom Disk System (FDS), UNIF, NSF, and NSFe file support
- Zipped ROMs are extracted automatically when the library is scanned
- [RetroAchievements](https://retroachievements.org) support with unlock notifications and an in-game achievement list
- HD texture pack support, with in-app HD pack downloads or ZIP import
- Anti-Flicker option that removes the sprite limit to reduce flickering
- ROM library scanning with list, cover-grid, and Coverflow views, plus a Refresh Library action
- Cover art for North American, Japanese, and FDS games, matched offline for fast loading
- On-screen controls with turbo buttons and an A + B combo button, plus physical controller/keyboard input
- Multiple video filters and aspect ratios
- Save states with preview images
- Battery-backed saves and a built-in Cheat Manager
- Light and dark themes

## Requirements

- Android 8.0 (API 26) or newer
- An OpenGL ES 3.0-capable device
- A 64-bit ARM (`arm64-v8a`) or x86-64 (`x86_64`) device
- Your own legally obtained game files and, for FDS games, a compatible FDS BIOS

## Build from Source

Open the `Android` directory in Android Studio, or build from the command line:

```bash
cd Android
./gradlew assembleDebug
```

The debug APK is created at:

```text
Android/app/build/outputs/apk/debug/app-debug.apk
```

The project requires JDK 17, Android SDK 36, NDK 30.0.15729638, and CMake 3.22.1. Android Studio can install the required SDK components automatically.

To install the debug build on a connected device:

```bash
cd Android
./gradlew installDebug
```

## Using NES HD

1. Tap the add button to open a single game or scan a ROM folder.
2. Select a game from the library to start playing.
3. Open the settings drawer to choose a video filter, aspect ratio, or FDS BIOS, and to toggle HD packs, Anti-Flicker, and RetroAchievements.
4. Use the in-game menu to save or load a state, view achievements, and manage FDS disks when applicable.

## HD Packs

Press and hold a game in the library and choose **Download HD Pack** to get a high-resolution texture pack from the in-app catalog, or **Install HD Pack from ZIP** to use a pack you already have. Games with an installed pack show an HD icon in the library, and the pack loads automatically when the game starts. HD packs can be turned on or off with the **HD Packs** switch in Settings.

## RetroAchievements

NES HD can track [RetroAchievements](https://retroachievements.org) while you play.

1. Open the settings drawer and, under **RetroAchievements**, tap **Account** to sign in. If you don't have an account, tap **Create a free account**.
2. Start a game. Its achievement set loads in the background, and a badge pops up when you unlock one (you can turn unlock notifications off in Settings).
3. Open the in-game menu and select **Achievements** to see your progress and points for the current game.

Achievements are earned in casual (softcore) mode, so save states and cheats remain available. Your password is only used to sign in and is never stored; NES HD keeps the login token that RetroAchievements returns. Achievement settings take effect the next time a game starts.

RetroAchievements identifies games by their ROM data, so some dumps or modified ROMs may not have an achievement set.

## Cheat Manager

NES HD includes an offline cheat database for supported games. To use it, press and hold a game in the library, select **Cheat Manager**, and tap individual cheats to enable or disable them. Your selections are saved separately for each game and are applied automatically the next time that game starts.

Games are matched to the cheat database by their ROM data, so modified ROMs and some regional or revision variants may not have an available match.

Game files, BIOS files, and commercial artwork are not included. Only use content that you are legally permitted to use.

## License

NES HD includes code derived from [Mesen](https://github.com/SourMesen/Mesen2), originally copyright (C) 2014-2025 Sour, and is distributed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).

RetroAchievements support uses [rcheevos](https://github.com/RetroAchievements/rcheevos), which is licensed under the MIT License (see `Android/app/rcheevos/LICENSE`). NES HD is not affiliated with RetroAchievements.org.

This program is distributed in the hope that it will be useful, but without any warranty; without even the implied warranty of merchantability or fitness for a particular purpose. See the GNU General Public License for more details.
