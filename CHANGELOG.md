# Changelog

All notable changes to own.audio Books for Android. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

- `:player:core` with the Media3 playback pieces (link resolver, data source chain, listening cache, session callback, skip commands), copied from music-android.
- B2: the book library — one model over own.audio and Audiobookshelf, own.audio's catalogue on the phone (one Room database per server, fed by the sync snapshot and change feed; collections too), Audiobookshelf through its pages and shelves with one play session per book, the resume choice and the download eviction rules.
- The spoken engine's logic (B3, part): one timeline across a book's files, chapter navigation, chapters read from the files themselves (MP4/M4B chapter tracks and Nero `chpl`, MP3 ID3 `CHAP`/`CTOC`, FLAC and Ogg comments) within 6 seconds, smart rewind, the end-of-chapter and end-of-book sleep modes, speed steps and their text.
- `:player:core` (sleep timer, resume store, storage route, payload check), copied from music-android.
- `:core:servers`, copied from music-android (F3).
- `:kit:ownaudio`, the own.audio /api/v2 client, copied from music-android (F2).
- F1's look from music-android with the brown accent, the book words and the book icon.
- `:kit:audiobookshelf` (B1): the Audiobookshelf client with rotating tokens, play sessions, progress, bookmarks and podcast libraries.
- The build skeleton from music-android's F0: both flavours, the module layout, four empty tabs in English and Czech, CI, the drift check.
- Implementation plan and the shared Android foundation document.
