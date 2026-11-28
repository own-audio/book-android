#!/usr/bin/env python3
"""Takes the Google Play screenshots on an emulator, in English and Czech, from the public demo library.

Drives the play debug build with adb: clears its data, signs in with Try the Demo Library, plays
Just So Stories and captures the library, the book, the player with its sleep timer and chapters,
Home, Downloads and (phone) the widgets on the home screen. Buttons are found by their text, read
from the app's own strings, so the same steps work in both languages. The device language is
switched with `cmd locale` and put back afterwards; the status bar is in demo mode (9:41, full
battery, Wi-Fi) while it runs.

Raw captures go to build/store-screenshots/<locale>/<device>/; tools/store-graphics.py frames
them into fastlane/metadata/android/<locale>/images/.

Before the first phone run, put the own.book Now Playing, Continue Listening and Player widgets
on the emulator's second home screen by hand (long-press the home screen, Widgets): they stay
there as long as the emulator keeps its data.

  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ANDROID_SERIAL=emulator-5556 ./gradlew :app:installPlayDebug
  tools/store-screenshots.py emulator-5556 phone            # also: seven, ten
  tools/store-screenshots.py emulator-5556 phone cs-CZ      # one language only
"""
import pathlib, re, subprocess, sys, time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACKAGE = "audio.own.bookplayer"
BOOK = "Just So Stories"
STRINGS = [ROOT / "app/src/main/res", ROOT / "core/strings/src/main/res"]
WIDGET_PAGE = 2

if len(sys.argv) < 3 or sys.argv[2] not in ("phone", "seven", "ten"):
    sys.exit(__doc__)
SERIAL, DEVICE = sys.argv[1], sys.argv[2]
LOCALES = sys.argv[3:] or ["en-US", "cs-CZ"]


def adb(*args, check=True):
    # A slow emulator can leave uiautomator hanging; a stuck call is retried once.
    for attempt in (1, 2):
        try:
            return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, check=check, timeout=60).stdout
        except subprocess.TimeoutExpired:
            if attempt == 2:
                raise


def shell(command):
    return adb("shell", command).decode()


def string(name, locale):
    folder = "values" if locale.startswith("en") else f"values-{locale.split('-')[0]}"
    for res in STRINGS:
        for path in sorted((res / folder).glob("strings*.xml")):
            for element in ET.parse(path).getroot().iter("string"):
                if element.get("name") == name:
                    return (element.text or "").replace("\\'", "'")
    sys.exit(f"no string {name} for {locale}")


def nodes():
    shell("uiautomator dump /sdcard/store-ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/store-ui.xml").decode()
    found = []
    for node in re.findall(r"<node [^>]*>", xml):
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        x1, y1, x2, y2 = map(int, bounds.groups())
        for key in ("text", "content-desc"):
            value = re.search(key + r'="([^"]*)"', node)
            if value and value.group(1):
                text = value.group(1).replace("&amp;", "&").replace("&apos;", "'").replace("&quot;", '"')
                found.append((text, (x1 + x2) // 2, (y1 + y2) // 2))
    return found


def size():
    width, height = re.search(r"(\d+)x(\d+)", shell("wm size").splitlines()[-1]).groups()
    return int(width), int(height)


def find(texts, index=0, scroll=False, wait=20, prefix=False):
    """The first of texts on screen; a book's cover is described as "Title, Author", hence prefix."""
    texts = [texts] if isinstance(texts, str) else texts
    deadline = time.time() + wait
    while time.time() < deadline:
        on_screen = nodes()
        for text in texts:
            hits = [(x, y) for value, x, y in on_screen
                    if value == text or (prefix and value.startswith(text + ","))]
            if len(hits) > index:
                return hits[index]
        if scroll:
            width, height = size()
            shell(f"input swipe {width // 2} {height * 2 // 3} {width // 2} {height // 3} 400")
        time.sleep(1)
    sys.exit(f"not on screen: {texts}")


def tap(texts, index=0, scroll=False, wait=20, prefix=False):
    x, y = find(texts, index, scroll, wait, prefix)
    shell(f"input tap {x} {y}")
    time.sleep(1.5)


def scroll_to_top():
    width, height = size()
    for _ in range(3):
        shell(f"input swipe {width // 2} {height // 3} {width // 2} {height * 5 // 6} 200")
    time.sleep(1)


def capture(folder, name):
    time.sleep(1.5)
    (folder / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p"))
    print(f"  {name}", flush=True)


def demo_mode(on):
    def demo(*args):
        shell("am broadcast -a com.android.systemui.demo -e command " + " ".join(args))
    if not on:
        demo("exit")
        return
    shell("settings put global sysui_demo_allowed 1")
    demo("enter")
    demo("clock", "-e", "hhmm", "0941")
    demo("battery", "-e", "level", "100", "-e", "plugged", "false")
    demo("network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true")
    demo("network", "-e", "mobile", "hide")
    demo("notifications", "-e", "visible", "false")


def run(locale):
    s = lambda name: string(name, locale)
    folder = ROOT / "build/store-screenshots" / locale / DEVICE
    folder.mkdir(parents=True, exist_ok=True)
    print(f"== {DEVICE}, {locale}")
    shell(f"cmd locale set-device-locale {locale}")
    time.sleep(3)
    demo_mode(True)
    shell(f"pm clear {PACKAGE}")
    shell(f"pm grant {PACKAGE} android.permission.POST_NOTIFICATIONS")
    shell("cmd uimode night no")
    shell(f"monkey -p {PACKAGE} -c android.intent.category.LAUNCHER 1")
    tap(s("try_the_demo"), scroll=True)
    find(s("tab_home"), wait=60)
    time.sleep(4)  # the covers

    if DEVICE == "phone":
        phone(s, folder)
    else:
        tablet(s, folder)


def open_book(s):
    tap(s("tab_library"))
    tap(BOOK, prefix=True, scroll=True)
    time.sleep(2)


def play_book(s):
    tap([s("play_continue"), s("resume"), s("play")])
    shell("cmd media_session volume --stream 3 --set 0")
    time.sleep(4)


def open_player(folder):
    # The mini player: the last line with the book's title on the book page.
    titles = [(x, y) for value, x, y in nodes() if value == BOOK]
    x, y = titles[-1]
    shell(f"input tap {x} {y}")
    time.sleep(3)
    capture(folder, "player")


def player_sheets(s, folder):
    tap(s("sleep_timer"))
    find(s("end_of_book"))
    capture(folder, "sleep")
    shell("input keyevent 4")
    time.sleep(1.5)
    tap(s("chapters"))
    find(s("done"))
    time.sleep(1)
    capture(folder, "chapters")
    shell("input keyevent 4")
    time.sleep(1.5)


def widgets(folder):
    shell("input keyevent 3")
    time.sleep(2)
    width, height = size()
    for _ in range(WIDGET_PAGE - 1):
        shell(f"input swipe {width * 5 // 6} {height // 2} {width // 6} {height // 2} 300")
        time.sleep(1.5)
    time.sleep(4)
    capture(folder, "widgets")
    shell("input keyevent 3")


def phone(s, folder):
    """With Just So Stories playing, so the mini player shows on every page."""
    open_book(s)
    play_book(s)
    tap(s("download"))
    tap(s("files"), index=1)  # the tab, below the Details row of the same name
    time.sleep(6)  # the download, so the button shows it done
    scroll_to_top()
    capture(folder, "book")
    open_player(folder)
    player_sheets(s, folder)
    tap(s("close"))
    shell("input keyevent 4")  # back to the library
    time.sleep(1.5)
    scroll_to_top()
    capture(folder, "library")
    tap(s("tab_downloads"))
    time.sleep(8)  # the download
    capture(folder, "downloads")
    tap(s("tab_home"))
    scroll_to_top()
    capture(folder, "home")
    widgets(folder)


def tablet(s, folder):
    capture(folder, "home")
    open_book(s)
    capture(folder, "book")
    shell("input keyevent 4")
    time.sleep(1.5)
    scroll_to_top()
    capture(folder, "library")
    tap(BOOK, prefix=True)
    play_book(s)
    open_player(folder)


previous = shell("cmd locale get-device-locale").strip()
try:
    for locale in LOCALES:
        run(locale)
finally:
    demo_mode(False)
    shell(f"cmd locale set-device-locale {previous or 'en-US'}")
print(f"raw screenshots in {ROOT / 'build/store-screenshots'}")
