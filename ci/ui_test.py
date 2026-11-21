#!/usr/bin/env python3
"""
UI walkthrough of the multi-select feature on an Android emulator.

Drives the app with adb (uiautomator dump to find elements by text),
saves a screenshot + UI dump after every step and writes report.md.

Usage: ui_test.py OUT_DIR APK_DIR
"""
import glob
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

OUT = sys.argv[1]
APK_DIR = sys.argv[2]
PKG = "org.coolreader.fork"
ACTIVITY = PKG + "/org.coolreader.CoolReader"
BOOKS = "/storage/emulated/0/Books/Test"
os.makedirs(OUT, exist_ok=True)

report = []
step_no = [0]


def log(msg):
    print(msg, flush=True)
    report.append(msg)


def adb(*args, timeout=60):
    r = subprocess.run(["adb"] + list(args), capture_output=True, timeout=timeout)
    return r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")


def shell(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def nodes():
    for _ in range(6):
        shell("uiautomator dump /sdcard/ui.xml")
        xml = adb("exec-out", "cat /sdcard/ui.xml")
        start = xml.find("<?xml")
        if start >= 0:
            try:
                root = ET.fromstring(xml[start:xml.rfind(">") + 1])
                res = []
                for n in root.iter("node"):
                    b = re.findall(r"\d+", n.get("bounds", ""))
                    if len(b) != 4:
                        continue
                    x1, y1, x2, y2 = map(int, b)
                    res.append({
                        "text": n.get("text", ""), "desc": n.get("content-desc", ""),
                        "id": n.get("resource-id", ""), "cls": n.get("class", ""),
                        "checked": n.get("checked") == "true", "enabled": n.get("enabled") == "true",
                        "x": (x1 + x2) // 2, "y": (y1 + y2) // 2, "bounds": (x1, y1, x2, y2),
                    })
                # system "app isn't responding" dialogs of the slow emulator: press Wait
                anr = [n for n in res if "isn't responding" in n["text"] or "не отвечает" in n["text"]]
                if anr:
                    wait = [n for n in res if n["text"] in ("Wait", "Подождать")]
                    if wait:
                        print("dismissing ANR dialog: " + anr[0]["text"], flush=True)
                        shell("input tap %d %d" % (wait[0]["x"], wait[0]["y"]))
                        time.sleep(2)
                        continue
                return res, xml[start:]
            except ET.ParseError:
                pass
        time.sleep(1)
    return [], ""


def shot(name):
    step_no[0] += 1
    base = os.path.join(OUT, "%02d_%s" % (step_no[0], name))
    time.sleep(0.8)
    with open(base + ".png", "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True).stdout)
    ns, xml = nodes()
    with open(base + ".xml", "w") as f:
        f.write(xml)
    texts = [n["text"] or n["desc"] for n in ns if (n["text"] or n["desc"])]
    log("### %02d %s\nvisible: %s" % (step_no[0], name, " | ".join(texts)[:1500]))
    return ns


def find(pattern, ns=None, id_filter=None):
    if ns is None:
        ns, _ = nodes()
    rx = re.compile(pattern, re.IGNORECASE)
    for n in ns:
        if id_filter and id_filter not in n["id"]:
            continue
        if rx.search(n["text"]) or rx.search(n["desc"]):
            return n
    return None


def tap_node(n):
    shell("input tap %d %d" % (n["x"], n["y"]))
    time.sleep(1.2)


def tap(pattern, id_filter=None):
    n = find(pattern, id_filter=id_filter)
    if n is None:
        log("!! not found: %s" % pattern)
        return False
    log("tap '%s' at %d,%d" % (n["text"] or n["desc"], n["x"], n["y"]))
    tap_node(n)
    return True


def long_press(pattern):
    n = find(pattern)
    if n is None:
        log("!! not found for long press: %s" % pattern)
        return False
    log("long press '%s' at %d,%d" % (n["text"], n["x"], n["y"]))
    shell("input swipe %d %d %d %d 900" % (n["x"], n["y"], n["x"], n["y"]))
    time.sleep(1.5)
    return True


def checked_books(ns):
    """Check box state per book row: pairs (title, checked) by vertical position."""
    boxes = [n for n in ns if n["cls"].endswith("CheckBox")]
    titles = [n for n in ns if re.match(r"Книга \d\d", n["text"])]
    res = []
    for t in titles:
        box = min(boxes, key=lambda b: abs(b["y"] - t["y"]), default=None)
        if box is not None and abs(box["y"] - t["y"]) < 120:
            res.append("%s=%s" % (t["text"], "x" if box["checked"] else "-"))
        else:
            res.append("%s=nobox" % t["text"])
    return ", ".join(res)


def make_fb2(i):
    body = "".join("<p>Абзац %d книги %02d. Съешь же ещё этих мягких французских булок, да выпей чаю.</p>" % (k, i) for k in range(60))
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info>'
            '<genre>prose</genre><author><first-name>Иван</first-name><last-name>Тестов</last-name></author>'
            '<book-title>Книга %02d</book-title><lang>ru</lang></title-info></description>'
            '<body><section><title><p>Глава 1</p></title>%s</section></body></FictionBook>\n') % (i, body)


def main():
    apk = [p for p in glob.glob(os.path.join(APK_DIR, "*.apk")) if "x86_64" in p or "universal" in p]
    log("# CoolReaderFork multi-select UI walkthrough\napk: %s" % apk)
    log(adb("install", "-r", "-g", apk[0], timeout=240).strip())
    log(shell("appops set --uid %s MANAGE_EXTERNAL_STORAGE allow" % PKG).strip())
    log(shell("cmd locale set-app-locales %s --user 0 --locales ru-RU" % PKG).strip())
    shell("settings put secure immersive_mode_confirmations confirmed")
    shell("settings put global hide_error_dialogs 1")
    time.sleep(20)  # let the launcher settle after boot
    shell("settings put system screen_off_timeout 1800000")
    shell("svc power stayon true")

    # test books
    shell("rm -rf %s && mkdir -p %s/Archive" % (BOOKS, BOOKS))
    os.makedirs("/tmp/books", exist_ok=True)
    for i in range(1, 9):
        p = "/tmp/books/book%02d.fb2" % i
        with open(p, "w", encoding="utf-8") as f:
            f.write(make_fb2(i))
        adb("push", p, "%s/book%02d.fb2" % (BOOKS, i))
    log(shell("ls -R %s" % BOOKS))

    # first launch (may show first-run screens), then open the test folder
    shell("am start -W -n %s" % ACTIVITY)
    time.sleep(12)
    ns = shot("first_launch")
    for _ in range(3):
        b = find(r"^(OK|ОК|Ок|ДА|Да|YES|Yes|Закрыть|Close|Allow|Разрешить)$", ns)
        if b is None:
            break
        tap_node(b)
        ns = shot("dismiss_dialog")
    shell("am start -n %s --es org.coolreader.fork.OPEN_DIR %s" % (ACTIVITY, BOOKS))
    time.sleep(6)
    ns = shot("folder")
    if find(r"Книга 01", ns) is None:
        # books may still be scanned
        time.sleep(6)
        ns = shot("folder_retry")

    # 0. re-entering a folder keeps book titles
    tap(r"^Archive$")
    time.sleep(2)
    shot("in_archive")
    shell("input keyevent KEYCODE_BACK")
    time.sleep(5)
    ns = shot("back_to_folder")
    log("titles after re-enter: %s" % ("OK" if find(r"^Книга 01$", ns) else "LOST"))

    # 1. long press starts selection
    long_press(r"^Книга 01$")
    ns = shot("long_press_book01")
    log("checks: " + checked_books(ns))

    # 2. tap toggles more books
    tap(r"^Книга 02$")
    tap(r"^Книга 03$")
    ns = shot("three_selected")
    log("checks: " + checked_books(ns))

    # 3. tap on a selected book unchecks it
    tap(r"^Книга 03$")
    ns = shot("uncheck_book03")
    log("checks: " + checked_books(ns))

    # 4. select all / none
    tap(r"^Все$")
    ns = shot("select_all")
    log("checks: " + checked_books(ns))
    tap(r"^Снять все$")
    ns = shot("select_none")
    log("checks: " + checked_books(ns))
    n = find(r"^Перенести$", ns)
    log("move enabled with 0 selected: %s" % (n["enabled"] if n else "n/a"))

    # 5. tap on folder in selection mode is ignored
    tap(r"^Archive$")
    ns = shot("tap_folder_in_selection")

    # 6. move two books into a new folder
    tap(r"^Книга 01$")
    tap(r"^Книга 02$")
    tap(r"^Перенести$")
    ns = shot("move_dialog")
    n = find(r"Переместить сюда", ns)
    log("move-here enabled in source folder: %s" % (n["enabled"] if n else "n/a"))
    tap(r"Новая папка$")
    ns = shot("new_folder_dialog")
    shell("input text Moved")
    ns = shot("new_folder_typed")
    tap(r"^Создать$")
    ns = shot("in_new_folder")
    tap(r"Переместить сюда")
    time.sleep(2)
    ns = shot("after_move")
    log(shell("ls -R %s" % BOOKS))

    # 7. delete one book
    long_press(r"^Книга 03$")
    tap(r"^Удалить$")
    ns = shot("delete_confirm")
    tap(r"^Удалить$", id_filter="android:id/button1")
    time.sleep(2)
    ns = shot("after_delete")
    log(shell("ls -R %s" % BOOKS))

    # 8. status for two books
    long_press(r"^Книга 04$")
    tap(r"^Книга 05$")
    tap(r"^Статус$")
    ns = shot("status_dialog")
    tap(r"^Прочитана$")
    time.sleep(1.5)
    ns = shot("after_status")

    # 9. back key leaves selection mode
    long_press(r"^Книга 06$")
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1)
    ns = shot("back_exits_selection")

    # 10. close button
    long_press(r"^Книга 06$")
    tap(r"Закрыть выбор")
    ns = shot("close_button")

    # 11. moved books are in the new folder
    tap(r"^Moved$")
    time.sleep(3)
    ns = shot("moved_folder")

    # logs from the app
    with open(os.path.join(OUT, "app_log.txt"), "w") as f:
        f.write(shell("run-as %s cat files/logs/cr3fork.log" % PKG, timeout=60))
    with open(os.path.join(OUT, "logcat.txt"), "w") as f:
        f.write(adb("logcat", "-d", "-t", "4000", timeout=120))


try:
    main()
except Exception as e:
    log("!! EXCEPTION: %r" % e)
    try:
        shot("exception")
    except Exception:
        pass
finally:
    with open(os.path.join(OUT, "report.md"), "w") as f:
        f.write("\n".join(report) + "\n")
