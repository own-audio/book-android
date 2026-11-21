# Implementation plan: own.audio Books for Android

**own.audio Books** for Android: a native, open-source (MPL-2.0) audiobook player for **own.audio and
Audiobookshelf** servers. Chapters, speed, sleep timer, bookmarks, offline, small. Kotlin, Jetpack
Compose, Media3, Android 9+ (API 28). applicationId `audio.own.bookplayer`, home-screen name
**own.book**.

It is a port of [own.audio Books for iOS](https://github.com/own-audio/book-ios) (1.0.2): the same
functions, screens, words and look. own.audio servers are spoken to on **`/api/v2` only**.

Read [`docs/android-foundation.md`](docs/android-foundation.md) first: build, look, own.audio client,
servers, player core, downloads, sync, sign-in, settings pages, system integrations, tests and release
are described there once for all three Android apps. The foundation is built in
[music-android](https://github.com/own-audio/music-android); this app starts by copying it (B0).

## Progress

| Phase | What | State |
|---|---|---|
| B0 | Copy the foundation from music-android, make it own.book | ◐ skeleton 2026-11-21 |
| B1 | `:kit:audiobookshelf` | ☐ |
| B2 | Book library layer, own.audio and Audiobookshelf sources, catalogue database | ☐ |
| B3 | Spoken engine (`:player:spoken`) | ☐ |
| B4 | Screens: Home, Library, book, people, series, collections, search, player | ☐ |
| B5 | Welcome, sign-in, server page (Books specifics) | ☐ |
| B6 | Downloads, offline, set downloads, eviction | ☐ |
| B7 | Positions: pending progress, resume choice, "Continue from another device?" | ☐ |
| B8 | Bookmarks, favourites, finished/reset, sharing with the family | ☐ |
| B9 | Settings | ☐ |
| B10 | Import from this device (own.audio uploads) | ☐ |
| B11 | Narrate an ebook (hosted own.audio) | ☐ |
| B12 | Android Auto, widget, deep links, voice, background refresh | ☐ |
| B13 | Localization, accessibility, tablets, misuse tests | ☐ |
| B14 | Release | ☐ |

**Where we stopped:** B0 started 2026-11-21: the build skeleton from music-android's F0 is copied (empty tabs, both flavours, CI, drift check). The three apps are built in parallel: each shared module is copied here the day it lands in music-android, and the rest of B0 completes with F7's shared screens.

## Rules for whoever executes this plan

The same seven rules as music-android's plan: one phase at a time; read the named iOS files first (the
Swift code is the specification); CHANGELOG and guide with every change; port the Swift tests with the
code; **no `/api/v1`**; no secrets or internal hosts in the repo; the gate is
`./gradlew testDebugUnitTest lintDebug verifyRoborazziDebug` plus `tools/sibling-drift.sh`.

Two rules of the iOS app that matter most here:

- **Never lose a position.** Every position change is written to disk before it is sent.
- **Book time.** The engine and everything above it speaks one timeline in milliseconds across all of a
  book's files. Conversion to (file, offset) happens only at the edge.

---

## Scope

Everything in book-ios 1.0.2 (`music-ios/docs/ios-apps-feature-parity.md`, Books column):

| Kept the same | Different on Android, on purpose |
|---|---|
| Four tabs: Home, Library, Downloads, Search | Settings a full-screen page from the gear |
| own.audio (v2) and Audiobookshelf book libraries (podcast libraries hidden) | No v1 anywhere: demo, account deletion and Listening use v2 |
| Chapters (server, file, per file), speed 0.5–3× per book, skip intervals, smart rewind, sleep to end of chapter/book, bookmarks | No Apple sign-in in 1.0 |
| Home shelves, Library with sorts, filters, grid/list, series, authors, narrators, collections | AirPlay → system output switcher; CarPlay → Android Auto |
| Resume choice and "Continue from ‹device›?" | ABS Saver uses ABS's HLS transcode as on iOS; ExoPlayer plays more formats directly (Opus, Ogg) |
| Downloads incl. whole series/author/collection, Keep at Most, Whole Library When Offline | — |
| Import from this device (own.audio), Narrate an Ebook (hosted own.audio) | Import uses the Storage Access Framework (files and folders) |
| Family, Devices, Listening, Logs, Accent Color | Language row in Settings |
| Widget "Continue Listening", `ownbook://` links | Glance widget |

---

## B0 — Copy the foundation

- Copy from `music-android` at a named commit: `build-logic/`, `gradle/`, the wrapper, `tools/`,
  `docs/android-foundation.md`, `docs/design-tokens.json`, `:core:*`, `:player:core`, `:kit:ownaudio`,
  the shared screens (welcome shell, own.audio account sheet, two-factor, server page shell, Family,
  Devices, Listening, Logs, About, accent picker), `.github/workflows/ci.yml`.
- Make it own.book: `app.id=audio.own.bookplayer`, `app.name=own.book`; accent `#7A4A2B` / `#D7A97C`;
  radii card 12, cover 12, mini player 16, sheet top 28; cover crossfade 200 ms; the wordmark's book
  words (`audio, book, family, story, chapter, novel, page, bedtime`); the icon from book-ios
  `Design/AppIcon.svg` (family ring + a closed brown book); hero from book-ios `OwnAudioHero`; Books
  uses a **heart** for favourites.
- Tabs: **Home** (`Home`), **Library** (`LibraryBooks`), **Downloads** (`DownloadForOffline`),
  **Search** (`Search`).
- `ServerKind.OWN_AUDIO, AUDIOBOOKSHELF`.
- Note the source commit in CHANGELOG ("Foundation copied from music-android at <sha>").

**Done when:** the app builds in both flavours with empty tabs, signs in to own.audio, and
`tools/sibling-drift.sh` reports only the allowed differences.

## B1 — `:kit:audiobookshelf`

Port `Packages/AudiobookshelfKit` (verified against Audiobookshelf 2.37):

- **Sign-in** `POST /login {username, password}` with header **`x-return-tokens: true`** (otherwise the
  refresh token goes into an httpOnly cookie). Answer: user + access JWT (1 h; read `exp` from the JWT) +
  a 30-day refresh token. Refresh `POST /auth/refresh` with header **`x-refresh-token`**; it rotates;
  single-flight per server; 401/403 on refresh → sign-in refused. Servers before 2.26 have one
  non-expiring token, never refreshed. `POST /api/authorize` is the launch call and the connection check.
  Sign-out `POST /logout` with the bearer and `x-refresh-token`.
- **Media URLs carry `?token=<access JWT>`** (`/api/items/{id}/file/{ino}?token=`, ranges answer 206;
  a session's `contentUrl` made absolute + `?token=`). ExoPlayer can send headers, but keep the query form
  for the cache key rules and the downloads worker; on a 401 mid-book build a fresh URL with a fresh token.
- Covers `/api/items/{id}/cover?width=600&format=jpeg` (no token needed on 2.37; send it if present).
- Libraries: only `mediaType == "book"`.
- Items `GET /api/libraries/{id}/items?page&limit&sort&desc&filter&collapseseries`; sorts
  `media.metadata.title`, `media.metadata.authorNameLF`, `addedAt`, `media.duration`, `sequence` (series);
  filters `<group>.<base64url(value)>` — **URL-safe base64 without padding** (`+` becomes a space) for
  `authors.<id>`, `narrators.<name>`, `series.<id>`, `progress.finished|in-progress|not-started`.
- Item detail `GET /api/items/{id}?expanded=1&include=progress`; series books = items filtered by
  series, sorted by `sequence` (a series record has no books; a series-filtered row has
  `metadata.series` as **one object**); search `GET /api/libraries/{id}/search?q=`; personalized shelves
  `GET /api/libraries/{id}/personalized`; authors, narrators (keyed by **name**), collections.
- Progress: `/api/me` returns every `mediaProgress` and `bookmarks`. `PATCH /api/me/progress/{itemId}
  {currentTime, duration, progress, isFinished}` in **seconds**; deleting progress needs the
  **progress id**, not the item id.
- Bookmarks keyed by **time**: `POST /api/me/item/{id}/bookmark {time, title}`, rename `PATCH` with the
  same body, delete `DELETE …/bookmark/{time}` (an integer when whole).
- Play sessions: `POST /api/items/{id}/play` with `deviceInfo` (deviceId, clientName "own.audio Books",
  clientVersion, manufacturer, model, sdkVersion), `forceDirectPlay:false`, `forceTranscode` (true for
  Saver), `supportedMimeTypes`, `mediaPlayer:"ExoPlayer"`. Answer `audioTracks[]` (`contentUrl`,
  `startOffset`, `duration`, `mimeType`, `ino`), `currentTime`, `playMethod` (0 direct, **2 = HLS**).
  **A new session for the same book closes the open one** (its sync then 404s): keep one per book.
  `POST /api/session/{id}/sync` and `/close` `{currentTime, timeListened, duration}` answer plain text
  `OK`. On finish, an extra progress PATCH `isFinished:true`. A 404 on sync → send the position without a
  session (plain PATCH).
- `ino` is a number or a string depending on the version: decode both.
- Mojibake (UTF-8 read as Latin-1) repaired for display (port `Mojibake.swift`).
- **429**: honour `Retry-After` with a backoff (iOS has no handling; it keeps load low instead). Keep
  the low load too: progress at most once a minute, `/api/me` every 3 min only while playing in the
  foreground, no socket.io.

**Done when:** the kit's tests (MockWebServer, answers recorded from a test server) pass, and a live test
gated on `AUDIOBOOKSHELF_*` signs in, lists a library, opens a session, syncs and closes it.

## B2 — Book library layer, sources, catalogue database

Port `Packages/BookLibrary`:

- Models: `Book` (files as a timeline with `startMs`, chapters with ends, `series: List<SeriesRef>`,
  owner, visibility, `isMissing`), `BookFile`, `Chapter`, `ListeningProgress` (+ `DevicePosition`),
  `Bookmark`, `SeriesSummary`, `Collection`, `Shelf` (books / series / authors), `BookSort` (title,
  author, added, duration), `BookFilter` (all, not started, in progress, finished, favourites),
  `BookQuery`, `BooksPage`, `SearchResult`, `PlayPlan` (+ `Part` with `isHls`), `PlaybackReport`. Ids
  composite (`BookId = "<serverUUID>|<id>"`).
- `BookSource` interface and `Capabilities`: `SERIES, COLLECTIONS, SERVER_SEARCH, FAVOURITES,
  PER_DEVICE_POSITIONS, FAMILY_SHARING, UPLOAD, LIVE_UPDATES, CHANGE_FEED, LOWER_BITRATE, NARRATOR_IDS`.

  | Capability | own.audio | Audiobookshelf |
  |---|---|---|
  | series | built from the catalogue (`/series` exists on v2 too) | ✓ |
  | collections | ✓ (v2 `/collections`) | ✓ |
  | serverSearch | local search over Room | ✓ |
  | favourites, perDevicePositions, familySharing, upload, liveUpdates, changeFeed, narratorIds | ✓ | — |
  | lowerBitrate | ✓ (`max_bitrate_kbps`) | ✓ (HLS transcode) |

  (iOS shows collections for Audiobookshelf only; v2 has `/collections`, so Android shows them for
  own.audio too — port the change back to iOS.)
- **own.audio source**: catalogue in Room from `/sync/snapshot?kinds=contributor,book,series,collection,
  favourite,progress,bookmark` then `/sync/changes`; browsing, sorting, filtering and search over Room
  (title, author, narrator, series substring); per-file signed links from `POST /playback-info {kind:
  "book_file", id: <file id>}`, asked concurrently for the files of a book; detail `GET
  /books/{id}?include=contributors` when a record lacks files. Shelves built locally: **Continue
  Listening** (started, not finished, newest first, 10), **Recently Added** (10), **Listen Again**
  (finished, 10).
- **Audiobookshelf source**: server paging (60 per page), personalized shelves with the server's labels
  (types `book`, `series` — a card with the first book's cover, name, "N books" — and `authors`; unknown
  types dropped), one play session per book.
- `ServerRecord` gets `libraryId` and `libraries` (an ABS account with several book libraries gives one
  entry per library in the server menu: "server · library").
- Room: `book`, `book_file`, `chapter`, `book_credit`, `contributor`, `series`, `series_book`,
  `collection`, `collection_book`, `favourite`, `progress`, `bookmark`, `feed_state`.

Read: book-ios `Packages/BookLibrary/Sources/BookLibrary/*.swift`, tests.

**Done when:** both sources list books into a debug screen, own.audio after one snapshot and changes,
ABS by pages; mapper and source tests pass.

## B3 — Spoken engine (`:player:spoken`)

Port the behaviour of `Packages/PlayerEngine/Sources/SpokenEngine` onto Media3. This module is copied to
podcast-android unchanged (Tier 1 in the drift check).

- **One book = one ExoPlayer playlist of its files** (`SpokenPart`s end to end) under a `BookTimeline`
  that converts book time ↔ (part index, offset) (`locate`, `position`, `clamp`, `starts[]`). The UI and
  reports see only book time. Unresolved parts (`spoken-unresolved:<id>`) resolve through the
  `ResolvingDataSource` when reached.
- **Speed** 0.5–3.0 in 0.05 steps (`PlaybackParameters(speed, pitch = 1f)`; Sonic keeps the pitch).
  Remembered per book (`bookRates[compositeId]`); default for new books from Settings.
- **Skip** back/forward by the Settings intervals (defaults 15/30 s), clamped to the book; on the media
  notification as custom commands with the numbered icons; next/previous on the notification = chapters
  (enabled only with chapters).
- **Smart rewind** on play after a pause: under 10 s → 0; under 60 s → 3 s; under 10 min → 10 s; under
  1 h → 20 s; longer → 30 s (port `SmartRewind.swift` and its test table). A switch in Settings.
- **Chapters**: server chapters win (own.audio `chapters[]` with `start_ms` across the book, end = next
  start or the duration, titles default "Chapter N"; ABS `media.chapters` in seconds). Else chapters
  from the files, read with a **6-second limit**: MP3 ID3 `CHAP`/`CTOC` frames (Media3's
  `ChapterFrame`/`ChapterTocFrame` arrive in the metadata output), MP4/M4B Nero `chpl` atoms and
  QuickTime chapter tracks (Media3 doesn't expose these: a small parser over the first and last bytes
  with HTTP ranges, `M4bChapterReader`), Vorbis `CHAPTERxxx` comments. Else one chapter per file (file
  titles or "Part N"). Previous chapter has a 3-second grace. Next in the last chapter goes to the end.
- **Sleep timer**: presets 5/15/30/45/60 min, +10 min, **End of Chapter** (pause when the position
  reaches the chapter end − 250 ms; without chapters, the end of the part), **End of Book**. Modes
  exclusive. Never computed as "duration − position" (wrong under speed and scrubbing).
- **Position reports** (`onPosition` with reason periodic / pause / seek / chapter / stop / finished /
  teardown, listened ms since the last report, part id + offset): every 10 s of playing plus each event;
  going to the background saves at once.
- **Look-ahead** ("Keep Ahead" 5/10/15 min, default 10; 5 under Data Saver): the iOS `StreamWindow`
  becomes a `CacheDataSource` over the listening cache plus a `DefaultLoadControl` whose maximum buffer
  is the look-ahead converted to bytes and time; a prefetch helper (`CacheWriter`) keeps playhead +
  look-ahead on disk and fetches the start of the next part near the end of one. Show "N min saved" from
  the cached span ahead of the playhead. Never fetch a whole file on mobile data because of a resumed
  read (an iOS bug). Saver/HLS is not windowed.
- **Listening cache**: 1 GB LRU shared with the window, Wi-Fi only, not under Data Saver, never HLS;
  prefetch the **next** part at once and the current part after 45 s of listening. Cache keys
  `"<composite>#<fileId>"`.
- **Local first, per file** (port `PartLocator.swift`): for each part at play, at a seek into another
  part, and when the previous part ends: download → listening cache → stream. A download finishing
  mid-play is swapped in at the next pause or seek, never mid-sentence. Offline with no copy → "This part
  of the book is not on this device" (offline by choice) or wait for a signal.
- **Signal gaps** (port `SignalWait.swift`): no bytes and no network → "No signal — waiting", not an
  error; position saved as at a pause; waiting doesn't count as listening; the player is **held in
  buffering, not paused**, and the foreground service stays up so it can resume by itself (retry 2 s
  doubling to 30 s; a reload with a fresh link for failed items); it resumes when the network returns
  unless the user paused meanwhile; smart rewind applies.
- **Link refresh**: on an item error → own.audio storage-route probe once → else one fresh link for the
  part at the same position; a second failure shows the error, no loop. Links are refreshed before
  `expires_at`.
- **own.audio Saver**: `max_bitrate_kbps: 64` when metered and the mode is Saver; a windowed part always
  asks for the original.
- **ABS Saver**: `forceTranscode` → one HLS part for the whole book (`playMethod 2`), a normal HLS
  `MediaItem`; never cached or windowed; refreshed by a new session.
- Audio attributes `CONTENT_TYPE_SPEECH`, `USAGE_MEDIA`.

Read: book-ios `Packages/PlayerEngine/Sources/SpokenEngine/*`, `Sources/PlayerCore/*`,
`App/OwnAudioBooks/Core/PartLocator.swift`, `ProgressReporter.swift`, `PlaybackSettings.swift`; tests
(47 engine tests on iOS).

**Done when:** a multi-file own.audio book and an ABS book play across file boundaries with one
continuous timeline, at 1.75× without pitch change, with file chapters from an M4B and an MP3, smart
rewind, both sleep end modes, the look-ahead window, and a dropped network that resumes by itself; the
ported tests pass.

## B4 — Screens

Read the Swift file named for each.

- **Root** (`RootView.swift`): four tabs; offline banner above the mini player on every tab; server
  switching rebuilds the tabs while a book keeps playing; the global "Continue from ‹device›?" dialog (B7);
  `ownbook://play/<escaped composite id>`.
- **Home** (`Library/HomeView.swift`): greeting by hour ("Good morning / afternoon / evening") +
  "‹server› · ready for your next chapter?"; top bar **server menu** (only with more than one choice,
  `Group` icon for own.audio, `Dns` for ABS), **Offline Mode**, **Settings**. Shelves of 140 dp
  `BookCard`s. On **Continue Listening** a tap **plays** (play overlay on the cover) and a long press offers
  "Show Book"; elsewhere a tap opens the item. Offline: "Continue Listening" (downloaded + started) and "On
  This Device"; empty "Nothing Downloaded" / "No Books Yet". Pull to refresh; reload after a pause only
  when visible.
- **Library** (`Library/LibraryView.swift`, `BooksListView`, used for the root and for filtered lists):
  chips **Series**, **Authors**, **Narrators**, **Collections**; at the root the **Importing** (B10) and
  **Narrating** (B11) rows; top bar **+** (own.audio, online: "Import a Book", or a menu "Add a Book" with
  Import Audio Files… and Narrate an Ebook… when the server has `modules.narration`); on non-root lists a
  set download button; the sort/filter menu: Sort Title / Author / Recently Added / Length + Descending;
  Show All / Not Started / In Progress / Finished / Favorites (own.audio); **Grid** toggle (default grid).
  Grid: adaptive 140–200 dp cards. List: 56 dp cover, title, author, "4 h 12 min · 1 h 3 min left" /
  "Finished", the now-playing indicator; header "N books". 60 per page. Books out of reach offline at 45 %
  opacity. Empty texts per filter ("No favorite books yet. Mark one with the heart in its menu.").
- **People** (`PeopleView`, authors / narrators): name + book count, a filter field; opens the books list.
- **Series** (`SeriesListView`, `SeriesDetailView`): books in series order with the sequence number; set
  download. **Collections** (`CollectionsView`, `CollectionDetailView`).
- **Book** (`Library/BookDetailView.swift`): 120 dp cover, title, subtitle, author line, series links "Name
  #3" in the accent, "Shared with the family", progress summary. The **Play** capsule whose background *is*
  the progress bar (accent 55 % + the filled fraction), labelled Play / Continue Listening / Listen Again /
  Pause / Resume, spinner while starting; the download button beside it. Description (HTML stripped, 6
  lines, expandable). Details: Narrator, Length, Published, Publisher, Genres, Files ("N · size").
  Segmented **Chapters / Files / Bookmarks**; a tap on any row plays from there; the playing chapter tinted;
  "No chapter marks. The files are the chapters."; "No bookmarks yet. Add one from the player." ⋯: Add to
  Favorites / Remove Favorite, Share with the Family / Keep to Myself (owner, own.audio), Mark as Finished /
  Not Finished, Reset Progress (confirm "The position is forgotten on every device.").
- **Search** (`Library/SearchView.swift`): "Books, authors, narrators", ≥ 2 characters, 300 ms debounce,
  sections Books / Series / Authors / Narrators; ABS asks the server, own.audio searches Room, offline the
  downloads. Empty "Search the Library — Titles, authors, narrators and series."
- **Mini player** (`Player/MiniPlayerBar.swift`): radius 16, 40 dp cover (radius 8), title, "‹time left at
  speed› left" or "No signal — waiting", play/pause, **skip forward**, a 2 dp progress line along the
  bottom. After a relaunch the last book with a play symbol; a tap loads it, plays and opens the player.
- **Player** (`Player/PlayerView.swift`, `PlayerSheets.swift`): Close, Stop (stops, forgets the last book,
  closes); 260 dp cover with the sleep badge; title, author, **chapter button** (current chapter + chevron →
  chapters); the book bar (thin progress + percent), elapsed, **time left at the current speed**; the
  **chapter slider** (range = the current chapter, or the whole book without chapters) with "mm:ss" and
  "−mm:ss", seeking on release, TalkBack value "12:04 of 49:23"; status line "No signal — waiting" or "N
  min saved"; transport skip back (numbered icon), 64 dp play/pause, skip forward; previous/next chapter;
  tools row (max 500 dp, equal widths): **Speed** ("1×", tinted when not 1), **Sleep**, **Bookmarks**,
  **Chapters**, **Output**. Haptics on the transport.
- Sheets: **Speed** (big value, −/+ 0.05, presets 0.75 1 1.25 1.5 1.75 2 2.5 3 in a 4-column grid, "Pitch
  stays the same at any speed. The speed is remembered for this book."); **Sleep Timer** (as B3, with "Stops
  in", Add 10 Minutes, Turn Off); **Chapters** (current marked, scrolled to); **Bookmarks** ("Add Bookmark at
  12:34" → "New Bookmark" with an optional label; list; tap jumps; swipe Delete/Rename in words; menu
  Rename/Delete).
- Tablets: Library sidebar (All Books, Series, Authors, Narrators, Collections, Downloads); keyboard Space,
  Ctrl+←/→, Esc.

**Done when:** every screen works against own.audio and Audiobookshelf in light, dark, Czech and 200 %;
screenshots recorded; the guide pages written.

## B5 — Welcome, sign-in, server page (Books specifics)

Foundation §10, with:

- Welcome: "Your audiobooks, from your own server.", "own.audio · Audiobookshelf"; card text "own.audio
  keeps your audiobooks for you. Upload them once and listen here."; demo footnote "Public-domain books on a
  shared own.audio server, no account needed."; the server form (`books.example.com`, **User name or
  email**, Password, Name (optional), Connect), Google only for an own.audio address that offers it ("With
  your own.audio account.").
- **Detection** (book-ios `AppModel.addServer`): hosted mapping → candidates (https first) → own.audio
  discovery (v2 revision ≥ 16) → sign in; else Audiobookshelf `GET /status` with `app ==
  "audiobookshelf"` → `POST /login` → `GET /api/libraries` books only (none → "This account reaches no
  book library"). The record is saved only after a successful sign-in.
- Server page: **Library** (ABS with several book libraries → library picker); **Library on This Device**
  "Books: N · Authors: N"; ABS server icon fetched from the server; Add Address prompt
  `http://192.168.1.20:13378`.

**Done when:** foundation F7's list holds for Books, ABS sign-in and library choice included.

## B6 — Downloads, offline

Foundation §8. Books specifics:

- One work per **file** (`<composite>#<fileId>`); the index holds the whole book record (book, files,
  bytes, started, finished, last played) so offline screens need nothing else.
- **Set downloads**: series, author/narrator, collection, filtered list → every book of the query, not just
  the loaded pages; `SetDownloadButton` with the count.
- **Downloads** tab (`Library/DownloadsView.swift`): books from every server, newest first, grouped by
  server when more than one; row cover, title, author, server name, size or progress; Downloading N, Retry N
  Failed, swipe Remove, **Remove All Downloads** + total size; "Nothing Downloaded".
- **Eviction**: finished books first, then the least recently played, never the playing one; Keep at Most
  No Limit / 1 / 2 / 5 / 10 GB.
- **Offline banner**: "Offline Mode · downloaded books only", "No network · downloaded books only", "… only
  downloaded books play" (whole-library mode), **Turn Off** when offline by choice (also moves Downloaded
  Only → Saver).
- **Whole Library When Offline** (own.audio only): the full catalogue with books not downloaded greyed.

**Done when:** a book and a whole series download on Wi-Fi only, survive a kill, play in airplane mode with
chapters and bookmarks; eviction keeps the playing book.

## B7 — Positions

Port `Core/PendingProgress.swift`, `ProgressReporter.swift`, `BookLibrary/ResumeChoice.swift`:

- Every engine report goes to the pending-progress table first, merged per (server, book, session): newer
  position replaces, listened time adds up, `closes` ORed.
- Sent at once on pause / seek / chapter / stop / finished / teardown, otherwise at most every **60 s**;
  oldest first; network or offline → stop and try at the next checkpoint; `notFound` with an ABS session →
  re-queue without the session; sign-in refused → mark the server; other errors drop the entry; a pause
  during a slow flush → flush again after. Flushed at launch, on foreground, after a network change and in
  the periodic job.
- own.audio: `PUT /me/progress/book/{id} {position_ms, finished, at, file_id, file_position_ms}`; the answer
  `{progress, applied}`; `applied:false` → the server's newer position is taken and shown. Offline batches
  through `POST /sync/push` (iOS PUTs each queued position with its `at`; Android uses the batch).
  ABS: session sync/close, or the plain PATCH.
- **Resume choice**: start = an explicit position, else the server position if not finished, else the
  local resume store, else the plan's start (ABS session `currentTime`), else 0; finished books start at 0.
- **"Continue from ‹device›?"** (own.audio, online, when this device has a known position): if the server
  position differs from the local one by **more than 30 s** and a device entry (not this device, within
  30 s of the server position, from `GET /me/progress/book/{id}` `devices[]`) is **newer** than the local
  save, ask: **Continue from ‹device› (h:mm:ss)** / **Keep this device's position (h:mm:ss)**, message
  "‹Device› got to X; this device was at Y." Otherwise the newer one wins silently. ABS never asks (no
  device list). Android Auto never asks (takes the other device's position).
- Mark Finished / Reset first stop the engine **without reporting** and drop queued entries for that book.
- Progress from other devices: refreshed on foreground and every 180 s while playing in the foreground.

**Done when:** `ProgressReporterTests` and `ResumeChoiceTests` are ported and pass; offline listening
reaches the server after reconnecting; the dialog appears for a position made on another device.

## B8 — Bookmarks, favourites, finished, sharing

- Bookmarks: own.audio `GET /me/bookmarks?kind=book&item_id=`, `POST /me/bookmarks {kind, item_id,
  position_ms, file_id, file_position_ms, label}`, `PATCH /me/bookmarks/{id} {label}`, `DELETE`; ABS by
  time (B1). Online only, as on iOS (offline: the bookmark buttons say why they're unavailable).
- Favourites (own.audio): `PUT/DELETE /me/favourites/book/{id}`, the heart in the book menu, the badge on
  covers, the Favorites filter.
- Mark as Finished / Not Finished; Reset Progress (`DELETE /me/progress/book/{id}`; ABS needs the progress id).
- Share with the Family / Keep to Myself (owner): `PUT /books/{id}/visibility`.

**Done when:** each works against both servers where the capability exists, hidden where not.

## B9 — Settings

`Settings/SettingsView.swift`:

1. **Servers** (name, "own.audio · user" / "Audiobookshelf · user", red "Sign-in refused · Sign in again",
   check on the active one; swipe Remove; Add a Server; footer "The library shows the selected server. A book
   keeps playing when you switch.").
2. **Downloads and offline**: Downloads, Offline Mode, Download on Wi-Fi Only (on), Keep at Most, Whole
   Library When Offline (only with an own.audio server).
3. **Playback**: Skip Back / Skip Forward (5, 10, 15, 30, 45, 60, 90 s; 15/30), Speed for New Books (0.75–2×;
   1×), Smart Rewind (on). For own.audio the skip intervals and speed are also saved to `PATCH /me/settings`
   `audiobook{speed, skip_back_ms, skip_forward_ms}` so they follow the account (read at sign-in).
4. **Streaming**: On Mobile Data Original / Saver / Downloaded Only; Keep Ahead 5 / 10 / 15 min (10).
5. **Accent Color** (App Color is brown; Pink among the swatches instead of Brown).
6. **Language**.
7. **About**.

## B10 — Import from this device (own.audio)

Port `Library/ImportBookView.swift`, `Core/ImportManager.swift`, `SpokenEngine/ImportMetadata.swift`:

- **Choose Audio Files** (`OpenMultipleDocuments` for audio MIME types) and **Choose a Folder**
  (`OpenDocumentTree`, its audio files in name order); **Add More Files**; "Files, in playing order" with
  reorder and delete; total "N files · length · size".
- **Book**: Title, Author, Narrator (`;` separates several), Cover (`PickVisualMedia`; "From the file, or
  choose"); **Who sees it**: Just Me / The family; **Add to Library** (asks "Upload … over mobile data?" when
  metered and over 100 MB).
- Tags read with `MediaMetadataRetriever` (title, artist, album, duration, embedded picture).
- Upload in a **foreground WorkManager worker** that survives a kill: per file `POST /uploads {kind:
  book_file, filename, content_type, size_bytes}` → `PUT` with exactly the returned headers (streamed from
  the content URI) → `POST /books` with header **`Idempotency-Key: <import id>`** and the canonical JSON body
  `{title, author, narrator, description, visibility, cover_upload_id, files[{upload_id, relative_path "001 -
  name", title}]}`. A retry after a kill returns the same book.
- The **Importing** rows in the Library: progress, Try Again / Remove, Cancel.
- `402` → no room or credit; `403` → no upload right (hide the + for members without `can_upload`).

**Done when:** a three-file book imports from a folder, survives a kill mid-upload, appears in the
Library through the change feed; the idempotent retry is tested.

## B11 — Narrate an ebook (hosted own.audio)

Port `Library/NarrateBookView.swift`, `Core/NarrationManager.swift`, `OwnAudioKit/Narration.swift`,
`BookLibrary/BookNarration.swift`. Shown only when discovery has `modules.narration` and `me.family.can_generate`.

- **Choose an Ebook** (`.epub`, plain text, `.mobi`), Title; **Voice**: Written in, Read in (only languages
  with a voice), Voice ("name · female/male"), Files (One per chapter = `multi_file` / One for the whole book =
  `single_m4b`).
- `GET /narration/languages`, `/narration/voices`; **Get the Price** → `POST /narration/estimate` (multipart,
  streamed from the URI, 600 s timeout) → `POST /narration/quote` → "Family Credit"; **Narrate for ‹price›** →
  `POST /narration/jobs` (multipart, `Idempotency-Key`).
- Jobs in `narrations.json`; poll `GET /narration/jobs/{id}` every 4 s doubling to 60 s while in the
  foreground (also listen for `narration` events on `/events`); the **Narrating** rows ("Narrating · chapter
  3 of 12", "Done · price", failures, Remove); a finished job's book arrives through the feed.

**Done when:** against a hosted test account the form quotes a price (the tests never start a paid job).

## B12 — Android Auto, widget, deep links, voice, background refresh

Foundation §11. Books specifics:

- **Auto** (port `Core/CarPlayContent.swift`): root **Continue Listening**, **Downloads**, **Library**,
  **Series**, **Authors** (Continue and Downloads first: the car is often offline); ≤ 100 items; a
  speed custom action cycling 0.75 → 1 → 1.25 → 1.5 → 1.75 → 2; chapters as the queue; never the resume
  question.
- **Widget** "Continue Listening — Your last books, a tap away.": small (one book: cover, progress, time left)
  and medium (three); redrawn only when its entries change; tap → `ownbook://play/<id>`.
- **Voice**: "continue listening", "continue my book", "play ‹book›" (port `Intents/BookIntents.swift`:
  downloads + server search, ≤ 10; offline matches downloads).

## B13 — Localization, accessibility, tablets, misuse tests

- Generate `values-cs` from book-ios `App/OwnAudioBooks/Localizable.xcstrings` (474 keys) with the
  foundation script; the iOS table in `scripts/make-catalog.py` is the Czech source.
- Speed text: "1.75×" not "1.8×", "1.5×" not "1.50×", the locale's decimal sign ("1,75×").
- TalkBack: the chapter slider's value, every icon button, the Play capsule's progress.
- Misuse: hammered player, stop while loading, killed mid-download and mid-import, server removed mid-book,
  network lost for 10 minutes mid-book, restless navigation, wrong sign-ins, ABS 429.

## B14 — Release

Foundation §14, with book-ios `AppStore/` texts. Public-domain books (LibriVox) on a demo-only server for
the screenshots.

---

## Server work this app would like (none blocks 1.0)

1. **The public demo server on 1.0.4 or later** (discovery offers the demo account to v2 clients).
2. **Real `Content-Type`** on locally stored files.
3. **Audiobook translation** (narration translates ebooks; recorded audiobooks can't be translated).
4. **Sign in with Apple web callback**, if wanted (foundation §10.4).

## Open questions for the maintainer

1. Show collections for own.audio on Android (v2 has them) and port that back to book-ios? (Recommended.)
2. Use `POST /sync/push` for offline positions on Android while iOS PUTs one at a time? (Recommended; same
   server result, fewer requests.)
