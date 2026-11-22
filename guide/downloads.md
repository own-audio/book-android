---
title: Downloads and offline
nav_order: 7
description: "Download books in own.audio Books for Android, limit the space they take, listen offline, and choose what happens on mobile data."
---

# Downloads and offline
{: .no_toc }

1. TOC
{:toc}

## Downloading a book

Tap the download button beside **Play** on a [book's page](book.md#play-and-download), or the
download button on a series, an author's or narrator's list, a collection or a filtered list to
download every book in it.

Downloads go on in the background, also when you leave the app, and pick up where they
stopped if they are interrupted or the app is closed. By default they wait for Wi-Fi
(**Download on Wi-Fi Only** in Settings). If you start a book while it is still downloading, the
app switches to the downloaded copy at the next pause or skip, without losing your place.

Downloaded books are kept in the app's own storage on the phone and are left out of Android's
cloud backup (they can always be downloaded again). They are removed when you uninstall the app
or remove their server.

## The Downloads tab

**Downloads** lists what is on this phone, from every server, newest first. Each row shows the
cover, title, author, the server it came from and its size, or the download's progress.

![The Downloads tab](images/downloads_light.png){: width="300"}

- With more than one server, the books are grouped by server.
- While downloads run, **Downloading** shows how many. Failed ones can be tried again together
  with **Retry 3 Failed**.
- Swipe a book to the left to **Remove** its download.
- **Remove All Downloads** removes them all, after asking. They stay on the server.

Settings also has a **Downloads** row with the total size, which opens the same list.

## Storage limit

**Keep at Most** in [Settings](settings.md#downloads-and-offline) sets a limit: **No Limit**
(the default), 1 GB, 2 GB, 5 GB or 10 GB. Over the limit, the app removes finished books first,
then the books not played for longest. It never removes the book that is playing.

## Offline mode

With no network, the app goes offline by itself. You can also turn **Offline Mode** on yourself,
with the Wi-Fi button at the top of [Home](home.md) or in Settings. Offline, the app asks
nothing of your servers and shows and plays only what is on the phone.

While offline, a line above the mini player says so: **No network · downloaded books only**, or
**Offline Mode · downloaded books only** when you turned it on. Then it has a **Turn Off**
button.

Your positions are saved on the phone while offline and sent to the server when you are back
online.

### The whole library offline (own.audio)

The app keeps a list of everything on an own.audio server, without the audio, so the library
can be browsed with no signal. With **Whole Library When Offline** on in Settings, the Library
shows the whole own.audio library when offline, with the books you haven't downloaded grayed
out. Only downloaded books play. The line above the mini player then says **only downloaded
books play**.

This setting appears only when you have an own.audio server. Audiobookshelf libraries show
only their downloads when offline.

## Mobile data

**On Mobile Data** in Settings decides what happens without Wi-Fi:

- **Original** (default): streams the original files.
- **Saver**: asks the server for a smaller stream (64 kbps, plenty for speech) where it makes
  one.
- **Downloaded Only**: on mobile data the app behaves as if offline and plays only what is on
  the phone.

## Keeping ahead while streaming

While a book streams, its next minutes are kept on the phone (on mobile data too), so short
gaps in the signal go unheard. **Keep Ahead** in Settings sets how many: 5, 10 (default) or 15
minutes. With Android's Data Saver on, it is 5 minutes. **Saver** streams are not kept ahead.
