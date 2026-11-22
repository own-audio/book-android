# Changelog

All notable changes to own.audio Books for Android. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

- The spoken player takes a part's real length from the file when the stated one is missing or wrong, accepts chapters that arrive after loading, and switches to a downloaded copy at the next pause or seek without losing the place.
- Narrate an Ebook sends the same Idempotency-Key for every attempt at the same request: a retry after a lost answer never starts a second paid job.
- B10 and B11: Import a Book from audio files or a folder on this device (tags fill the title, author and cover; the order can be changed; the upload survives the app being killed and a retry never makes the book twice), and Narrate an Ebook on own.audio's hosted service (language, voice, files, the price first). Importing and Narrating rows in the Library.
- B6 and B9: downloads (a book, or a whole series, author, narrator, collection or filtered list), the Downloads tab, Keep at Most by whole books (finished ones first, never the playing one), a download replacing the stream at the next pause, Offline Mode with the offline banner, the library from downloads (or the whole own.audio library) when offline, live updates while the app is open, an hourly refresh, and the Downloads and offline settings; skip intervals and speed for new books follow the own.audio account. Audiobookshelf descriptions show plain text instead of HTML entities.
- B7 and B8: positions are never lost (kept on disk, merged, sent at once on pause, seek or chapter and otherwise once a minute, batched when several wait), "Continue from ‹device›?" when another device got further, another device's newer position taken on own.audio; bookmarks (add, rename, delete; Audiobookshelf by time), favorites, Mark as Finished / Not Finished, Reset Progress, Share with the Family / Keep to Myself, and covers that load a fresh link once when theirs has expired.
- `:core:downloads` and `:core:sync` (download workers and index, outboxes, background refresh, live updates), copied from music-android.
- B3 and B4: the spoken player on Media3 and the book screens — Home with shelves, Library (series, authors, narrators, collections; sort, filter, grid or list), the book page with chapters, files and bookmarks, Search, the mini player and the player (chapter slider, speed, sleep timer, chapters); plays in the background with skip and chapter buttons on the notification; positions written to disk before they are sent. Players now wait through long signal gaps instead of failing after ten minutes.
- B5 and the app shell, ported from music-android: welcome, own.audio account, Try the Demo Library, Add Server for own.audio and Audiobookshelf (book libraries only, a library picker, the server's own icon), Settings, the server page, Family, Devices, Listening and Logs. A privacy policy (PRIVACY.md).
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
