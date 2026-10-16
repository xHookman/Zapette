# -*- coding: utf-8 -*-
"""
Shared state of the emulated Kodi environment.

Kodi runs a plugin in a fresh interpreter for every menu: it passes
``sys.argv = [base_url, handle, query]`` and collects what the plugin sends
back through ``xbmcplugin`` (directory items, resolved url, ...).

Zapette keeps a single long-lived interpreter, so this module holds:
  * the paths of the fake Kodi installation (home, addons, profile),
  * the "host" object implemented on the Android side (dialogs, toasts),
  * the per-invocation ``Session`` where every xbmcplugin call is recorded.
"""
from __future__ import unicode_literals

import os
import threading
import time

ADDON_ID = "plugin.video.catchuptvandmore"

HOME_DIR = os.path.expanduser("~/.zapette")   # replaced by bridge.init()
LANGUAGE = "fr_fr"
TIMEZONE = "Europe/Paris"
# Reported as Kodi 20 ("Nexus"): the add-on then still declares the manifest type
# of each stream (inputstream.adaptive.manifest_type), which helps ExoPlayer.
KODI_VERSION = "20.5"

# Item currently selected in the UI (used by ListItem.* info labels)
selected_item = {}

# Window properties (xbmcgui.Window(10000).setProperty) are kept in memory,
# exactly like Kodi does during a session.
window_properties = {}

lock = threading.RLock()

# While checking an account, the add-on's pop-ups are not shown (the app reports the result).
silent_dialogs = False


def addons_dir():
    return os.path.join(HOME_DIR, "addons")


def userdata_dir():
    return os.path.join(HOME_DIR, "userdata")


def addon_path(addon_id=ADDON_ID):
    return os.path.join(addons_dir(), addon_id)


def addon_profile(addon_id=ADDON_ID):
    return os.path.join(userdata_dir(), "addon_data", addon_id)


def temp_dir():
    return os.path.join(HOME_DIR, "temp")


# --------------------------------------------------------------------------
# Host: implemented in Kotlin (fr.zapette.tv.engine.PythonHost).
# Every method must return quickly or block until the user answered.
# --------------------------------------------------------------------------
class ConsoleHost(object):
    """Fallback host used by the desktop test-suite (no UI)."""

    answers = []  # queue of scripted answers for tests

    def _next(self, default):
        return self.answers.pop(0) if self.answers else default

    def notify(self, heading, message, kind):
        print("[notify:%s] %s - %s" % (kind, heading, message))

    def select(self, heading, options_json, preselect, multi):
        print("[select] %s %s" % (heading, options_json))
        return self._next("-1" if not multi else "[]")

    def yesno(self, heading, message, nolabel, yeslabel):
        print("[yesno] %s - %s" % (heading, message))
        return bool(self._next(False))

    def ok(self, heading, message):
        print("[ok] %s - %s" % (heading, message))

    def input(self, heading, default, hidden, kind):
        print("[input] %s" % heading)
        return self._next(None)

    def progress(self, action, heading, message, percent):
        return False  # never cancelled

    def log(self, level, message):
        if level >= 2:
            print("[kodi:%d] %s" % (level, message))


host = ConsoleHost()


def call_host(method, *args):
    """Call a host method; never let a host failure break the add-on."""
    try:
        return getattr(host, method)(*args)
    except Exception as e:  # pragma: no cover - defensive
        try:
            print("host.%s failed: %s" % (method, e))
        except Exception:
            pass
        return None


# --------------------------------------------------------------------------
# Session: everything one plugin invocation sends back to "Kodi"
# --------------------------------------------------------------------------
class Session(object):
    def __init__(self, url):
        self.url = url
        self.started = time.time()
        self.items = []            # list of (url, listitem, is_folder)
        self.ended = False
        self.succeeded = True
        self.update_listing = False
        self.resolved = None       # (succeeded, listitem)
        self.category = ""
        self.content = ""
        self.sort_methods = []
        self.builtins = []         # executebuiltin() commands
        self.notifications = []    # [{heading, message}]
        self.playlist = []         # xbmc.PlayList additions


current = Session("")


def new_session(url):
    global current
    current = Session(url)
    return current
