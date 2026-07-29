# NES HD

NES HD is an NES emulator for Android, powered by the Mesen emulation core. It provides a mobile-friendly game library and fullscreen gameplay with support for high-resolution texture packs.

## Features

- NES, Famicom Disk System (FDS), UNIF, NSF, and NSFe file support
- HD texture pack support
- ROM library scanning with list and cover-grid views
- Downloadable cover art for North American, Japanese, and FDS games
- On-screen controls and physical controller/keyboard input
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
3. Open the settings drawer to choose a video filter, aspect ratio, or FDS BIOS.
4. Use the in-game menu to save or load a state and manage FDS disks when applicable.

## Cheat Manager

NES HD includes an offline cheat database for supported games. To use it, press and hold a game in the library, select **Cheat Manager**, and tap individual cheats to enable or disable them. Your selections are saved separately for each game and are applied automatically the next time that game starts.

Games are matched to the cheat database by their ROM data, so modified ROMs and some regional or revision variants may not have an available match.

Game files, BIOS files, and commercial artwork are not included. Only use content that you are legally permitted to use.

## License

NES HD includes code derived from [Mesen](https://github.com/SourMesen/Mesen2), originally copyright (C) 2014-2025 Sour, and is distributed under the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html).

This program is distributed in the hope that it will be useful, but without any warranty; without even the implied warranty of merchantability or fitness for a particular purpose. See the GNU General Public License for more details.
