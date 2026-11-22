---
title: Troubleshooting
nav_order: 13
description: "What to do when own.audio Books for Android can't sign in, can't reach the server, or doesn't play, and how to send the app's log with a bug report."
---

# Troubleshooting
{: .no_toc }

1. TOC
{:toc}

## "Sign-in refused · Sign in again"

The server no longer accepts the app's sign-in, for example after you changed your password.
Open **Settings**, tap the server, and tap **Sign In Again**. Downloads and the library on the
phone stay as they are. See [Servers and family](servers.md#sign-in-again).

## The server can't be reached

On the server's page, tap **Check Now**. It says whether the server answers and accepts the
sign-in, refuses it, or whether no address answers.

While the app is on screen it also asks a server that didn't answer again by itself: after 15
seconds, then less often, at most every 5 minutes, and at once when you come back to the app or
open the server's page. In the background, or while the phone is locked, it doesn't.

- Check that the phone is on a network that reaches the server. A server at home may only be
  reachable at home, or through a VPN.
- Add the server's other addresses under **Addresses** (home network, VPN, public name), so the
  app can use whichever works where you are.
- If the address you typed had no `http://` or `https://`, the app tried https first and then
  http. Type the address in full if your server needs one or the other.

## Messages when adding a server

- **This own.audio server is too old for this app.** The app needs own.audio 1.0.4 or later.
  Ask whoever runs the server to update it.
- **This account reaches no book library on that server.** The Audiobookshelf account has no
  book library it can see. Podcast libraries are not shown in this app.
- **The server didn't accept that username and password.** Check them; on own.audio, use your
  account's email.
- **Too many tries.** Wait a minute, then try again.

## No Google button

**Continue with Google** appears only in the Google Play build, and only for an own.audio server
that offers Google sign-in. The F-Droid build has no Google sign-in; use your email and
password there.

## Playback

- **No signal — waiting**: the signal dropped. The book plays on by itself when it comes back.
  Download books you want to hear where the signal is poor.
- **This book is not on this device**: the app is offline and the book isn't downloaded.
  Download it, or turn **Offline Mode** off.
- **Output does nothing**: Android's output switcher needs Android 14 or later. On older
  versions, pick the output in quick settings or Bluetooth settings.

## Downloads don't start

By default downloads wait for Wi-Fi. Turn off **Download on Wi-Fi Only** in Settings to use
mobile data. Failed downloads can be tried again with **Retry … Failed** in the **Downloads**
tab.

## The log

If something goes wrong, open **Settings → Logs**. It shows what the app wrote to its log since
it started: failed requests, playback trouble, syncing. The share button at the top makes a
text file to attach to [an issue](https://github.com/own-audio/book-android/issues). Nothing is
sent unless you share it. Addresses are logged without their sign-in tokens, but the log can
hold your server's addresses, so look it over before you post it publicly.
