# Third-Party Licenses

This application uses the following open-source libraries. This application is a fork of SubSonicSter by langhaarschneider (https://codeberg.org/langhaarschneider/SubSonicSter), used under the MIT License. See LICENSE.mit.

The libraries below are the ones this application uses. The versions are the ones in gradle/libs.versions.toml.

---

## Card data

The card sets in `card-sets/assets/csv` (in the top folder of the repository) map each Hitster card number to a song. They are a snapshot of
[songseeker-hitster-playlists](https://github.com/andygruber/songseeker-hitster-playlists)
by Andreas Gruber, commit `26e338f99acb087f3123cd5da7a5831c5895d573`, MIT License.
The license text is in `card-sets/assets/csv/LICENSE-songseeker-hitster-playlists.txt`.

One file is not in that commit: `hitster-de-aaaa0075.csv` comes from the open pull request
[#57](https://github.com/andygruber/songseeker-hitster-playlists/pull/57) ("Added some (all) sets
missing"), commit `45cc07c5f75a144447d29634642f6930dc52f12f`. It has not been merged yet. It lists
the cards from 308 down to 1. The app reads the card number from the column `Card#`, not from the
row. Replace the file with the merged version when there is one.

---

## Apache License 2.0

The following libraries are licensed under the Apache License, Version 2.0:

### AndroidX Libraries
- **androidx.core:core-ktx** (1.17.0)
- **androidx.lifecycle:lifecycle-runtime-ktx** (2.10.0)
- **androidx.lifecycle:lifecycle-viewmodel-compose** (2.10.0)
- **androidx.activity:activity-compose** (1.12.2)
- **androidx.compose.ui:ui** (Compose BOM 2026.02.00)
- **androidx.compose.ui:ui-graphics** (Compose BOM 2026.02.00)
- **androidx.compose.ui:ui-tooling-preview** (Compose BOM 2026.02.00)
- **androidx.compose.material3:material3** (Compose BOM 2026.02.00)
- **androidx.compose.material:material-icons-extended** (Compose BOM 2026.02.00)
- **androidx.navigation:navigation-compose** (2.9.7)
- **androidx.datastore:datastore-preferences** (1.2.0)
- Copyright: The Android Open Source Project
- License: Apache License 2.0
- https://source.android.com/license

### Dependency Injection
- **com.google.dagger:hilt-android** (2.58)
- **androidx.hilt:hilt-navigation-compose** (1.3.0)
- Copyright: Google Inc.
- License: Apache License 2.0
- https://github.com/google/dagger

### Networking
- **com.squareup.okhttp3:okhttp** (5.3.2)
- Copyright: Square, Inc.
- License: Apache License 2.0
- https://github.com/square/okhttp

### Coroutines
- **org.jetbrains.kotlinx:kotlinx-coroutines-core** (1.10.2)
- **org.jetbrains.kotlinx:kotlinx-coroutines-android** (1.10.2)
- Copyright: JetBrains s.r.o.
- License: Apache License 2.0
- https://github.com/Kotlin/kotlinx.coroutines

### Camera & QR Code Scanning
- **androidx.camera:camera-core** (1.5.2)
- **androidx.camera:camera-camera2** (1.5.2)
- **androidx.camera:camera-lifecycle** (1.5.2)
- **androidx.camera:camera-view** (1.5.2)
- Copyright: The Android Open Source Project
- License: Apache License 2.0
- https://developer.android.com/jetpack/androidx/releases/camera

- **com.journeyapps:zxing-android-embedded** (4.3.0)
- Copyright: ZXing authors, Journey Mobile, Inc.
- License: Apache License 2.0
- https://github.com/journeyapps/zxing-android-embedded

---

## BSD-2-Clause License (Simplified BSD License)

### Markdown Parsing
- **org.commonmark:commonmark** (0.27.1)
- Copyright: Atlassian Pty Ltd
- License: BSD-2-Clause License
- https://github.com/commonmark/commonmark-java

```
BSD 2-Clause License

Copyright (c) Atlassian and others

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

---

## Full License Texts

### Apache License 2.0

The complete Apache License 2.0 text is available at:
https://www.apache.org/licenses/LICENSE-2.0

```
Apache License
Version 2.0, January 2004
http://www.apache.org/licenses/

TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

1. Definitions.
   "License" shall mean the terms and conditions for use, reproduction, and
   distribution as defined by Sections 1 through 9 of this document.
   [... full license text continues ...]
```

For brevity, the full Apache 2.0 license text is not included here.
Please refer to the link above for the complete terms.

---

## License Compatibility Statement

All third-party libraries listed above use **permissive open-source licenses** (Apache 2.0 and BSD-2-Clause).
These licenses are fully compatible with the **MIT License** under which this fork is published.


## Card set names

`card-sets/assets/card_sets.csv` maps the file names of the bundled card sets to their display names. The 19 names are taken from the `Game` column of `playlists.csv` of [andygruber/songseeker-hitster-playlists](https://github.com/andygruber/songseeker-hitster-playlists), the name of `hitster-de-aaaa0075.csv` ("Hitster - Original") from the index of the pull request #57 of that project, which also delivered the card set. The playlists are MIT licensed, Copyright (c) 2024 Andreas Gruber. The license text is `card-sets/assets/csv/LICENSE-songseeker-hitster-playlists.txt`, the Open Source Licenses screen shows it.
