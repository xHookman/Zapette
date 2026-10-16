# -*- coding: utf-8 -*-
"""Emulation of Kodi's ``xbmcgui`` module for Zapette.

ListItems are plain data holders, dialogs are forwarded to the Android host.
"""
from __future__ import unicode_literals

import json

from zapette_kodi import runtime

NOTIFICATION_INFO = "info"
NOTIFICATION_WARNING = "warning"
NOTIFICATION_ERROR = "error"

INPUT_ALPHANUM = 0
INPUT_NUMERIC = 1
INPUT_DATE = 2
INPUT_TIME = 3
INPUT_IPADDRESS = 4
INPUT_PASSWORD = 5
PASSWORD_VERIFY = 1
ALPHANUM_HIDE_INPUT = 2

ACTION_PREVIOUS_MENU = 10
ACTION_NAV_BACK = 92
ACTION_SELECT_ITEM = 7

HORIZONTAL = 0
VERTICAL = 1


def _text(value):
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", "replace")
    return "%s" % (value,)


def getCurrentWindowId():
    return 10025


def getCurrentWindowDialogId():
    return 9999


def getScreenHeight():
    return 1080


def getScreenWidth():
    return 1920


class ListItem(object):
    def __init__(self, label="", label2="", iconImage="", thumbnailImage="", path="", offscreen=False):
        self._label = _text(label)
        self._label2 = _text(label2)
        self._path = _text(path)
        self._art = {}
        self._info = {}
        self._properties = {}
        self._stream_info = {}
        self._context = []
        self._subtitles = []
        self._mime = ""
        self._content_lookup = True
        self._selected = False
        self._is_folder = None
        self._unique_ids = {}
        self._rating = None
        self._cast = []
        self._date = ""
        if iconImage:
            self._art["icon"] = _text(iconImage)
        if thumbnailImage:
            self._art["thumb"] = _text(thumbnailImage)
        self._tag = None

    # --- labels
    def getLabel(self):
        return self._label

    def setLabel(self, label):
        self._label = _text(label)

    def getLabel2(self):
        return self._label2

    def setLabel2(self, label):
        self._label2 = _text(label)

    # --- art
    def setArt(self, values):
        for key, value in (values or {}).items():
            if value:
                self._art[_text(key)] = _text(value)

    def getArt(self, key):
        return self._art.get(key, "")

    def setIconImage(self, value):
        self._art["icon"] = _text(value)

    def setThumbnailImage(self, value):
        self._art["thumb"] = _text(value)

    # --- info
    def setInfo(self, type="video", infoLabels=None):
        for key, value in (infoLabels or {}).items():
            self._info[_text(key).lower()] = value

    def getVideoInfoTag(self):
        if self._tag is None:
            self._tag = _VideoTag(self._info)
        return self._tag

    def getMusicInfoTag(self):
        return self.getVideoInfoTag()

    def setDateTime(self, value):
        self._date = _text(value)

    def setRating(self, *args, **kwargs):
        pass

    def setCast(self, actors):
        self._cast = actors

    def setUniqueIDs(self, values, defaultrating=None):
        self._unique_ids.update(values or {})

    def getUniqueID(self, key):
        return self._unique_ids.get(key, "")

    def addStreamInfo(self, kind, values):
        self._stream_info.setdefault(kind, {}).update(values or {})

    def getDuration(self):
        try:
            return int(self._info.get("duration") or 0)
        except (TypeError, ValueError):
            return 0

    # --- properties
    def setProperty(self, key, value):
        self._properties[_text(key).lower()] = _text(value)

    def setProperties(self, values):
        for key, value in (values or {}).items():
            self.setProperty(key, value)

    def getProperty(self, key):
        return self._properties.get(_text(key).lower(), "")

    # --- path / playback
    def setPath(self, path):
        self._path = _text(path)

    def getPath(self):
        return self._path

    def getfilename(self):
        return self._path

    def setMimeType(self, mime):
        self._mime = _text(mime)

    def setContentLookup(self, enable):
        self._content_lookup = bool(enable)

    def setSubtitles(self, subtitles):
        self._subtitles = [_text(s) for s in (subtitles or [])]

    def addContextMenuItems(self, items, replaceItems=False):
        for entry in items or []:
            try:
                label, action = entry
                self._context.append({"label": _text(label), "action": _text(action)})
            except (TypeError, ValueError):
                pass

    def setIsFolder(self, value):
        self._is_folder = bool(value)

    def select(self, selected):
        self._selected = bool(selected)

    def isSelected(self):
        return self._selected

    # --- export used by the bridge
    def to_dict(self):
        return {
            "label": self._label,
            "label2": self._label2,
            "path": self._path,
            "art": dict(self._art),
            "info": dict(self._info),
            "properties": dict(self._properties),
            "stream_info": dict(self._stream_info),
            "context": list(self._context),
            "subtitles": list(self._subtitles),
            "mimetype": self._mime,
        }


class _VideoTag(object):
    """Minimal InfoTagVideo (Kodi 20+) writing into the item's info dict."""

    _aliases = {"setplot": "plot", "settitle": "title", "setduration": "duration",
                "setgenres": "genre", "setyear": "year", "setpremiered": "premiered",
                "setfirstaired": "aired", "setepisode": "episode", "setseason": "season",
                "settvshowtitle": "tvshowtitle", "setmediatype": "mediatype",
                "setplotoutline": "plotoutline", "setstudios": "studio",
                "setcountries": "country", "setdirectors": "director",
                "setmpaa": "mpaa", "setoriginaltitle": "originaltitle",
                "settagline": "tagline", "setdateadded": "dateadded"}

    def __init__(self, info):
        self._info = info

    def __getattr__(self, name):
        lname = name.lower()
        if lname.startswith("set") or lname.startswith("add"):
            key = self._aliases.get(lname, lname[3:])

            def setter(value=None, *args, **kwargs):
                self._info[key] = value
            return setter
        if lname.startswith("get"):
            key = lname[3:]
            return lambda *a, **k: self._info.get(key, "")
        raise AttributeError(name)


# ----------------------------------------------------------------- dialogs
class Dialog(object):
    def ok(self, heading, message="", *lines):
        text = "\n".join(_text(x) for x in (message,) + lines if x)
        runtime.call_host("ok", _text(heading), text)
        return True

    def yesno(self, heading, message="", *lines, **kwargs):
        nolabel = _text(kwargs.get("nolabel", ""))
        yeslabel = _text(kwargs.get("yeslabel", ""))
        text = "\n".join(_text(x) for x in (message,) + lines if x and not isinstance(x, int))
        return bool(runtime.call_host("yesno", _text(heading), text, nolabel, yeslabel))

    def yesnocustom(self, heading, message="", customlabel="", nolabel="", yeslabel="", **kwargs):
        return 1 if self.yesno(heading, message, nolabel=nolabel, yeslabel=yeslabel) else 0

    def _options(self, items):
        out = []
        for item in items or []:
            if isinstance(item, ListItem):
                out.append({"label": item.getLabel(), "label2": item.getLabel2(),
                            "thumb": item.getArt("thumb") or item.getArt("icon")})
            else:
                out.append({"label": _text(item)})
        return json.dumps(out)

    def select(self, heading, list=None, autoclose=0, preselect=-1, useDetails=False):
        answer = runtime.call_host("select", _text(heading), self._options(list), int(preselect), False)
        try:
            return int(answer)
        except (TypeError, ValueError):
            return -1

    def multiselect(self, heading, options=None, autoclose=0, preselect=None, useDetails=False):
        answer = runtime.call_host("select", _text(heading), self._options(options), -1, True)
        try:
            values = json.loads(answer)
            return [int(v) for v in values] if values else None
        except (TypeError, ValueError):
            return None

    def contextmenu(self, list):
        return self.select("", list)

    def input(self, heading, defaultt="", type=INPUT_ALPHANUM, option=0, autoclose=0):
        kind = {INPUT_NUMERIC: "number", INPUT_DATE: "date", INPUT_TIME: "time",
                INPUT_IPADDRESS: "ip", INPUT_PASSWORD: "password"}.get(type, "text")
        hidden = bool(option & ALPHANUM_HIDE_INPUT) or type == INPUT_PASSWORD
        answer = runtime.call_host("input", _text(heading), _text(defaultt), hidden, kind)
        return "" if answer is None else _text(answer)

    def numeric(self, type, heading, defaultt="", bHiddenInput=False):
        answer = runtime.call_host("input", _text(heading), _text(defaultt), bool(bHiddenInput), "number")
        return "" if answer is None else _text(answer)

    def notification(self, heading, message, icon="", time=5000, sound=True):
        # Recorded in the session; the app shows them as toasts once the call returns.
        kind = icon if icon in (NOTIFICATION_INFO, NOTIFICATION_WARNING, NOTIFICATION_ERROR) else "info"
        runtime.current.notifications.append({"heading": _text(heading), "message": _text(message),
                                              "kind": kind})

    def textviewer(self, heading, text, usemono=False):
        runtime.call_host("ok", _text(heading), _text(text))

    def browse(self, type, heading, shares, mask="", useThumbs=False, treatAsFolder=False,
               defaultt="", enableMultiple=False):
        return defaultt

    def browseSingle(self, *args, **kwargs):
        return ""

    def browseMultiple(self, *args, **kwargs):
        return []

    def info(self, item):
        return True


class DialogProgress(object):
    def __init__(self):
        self._cancelled = False

    def create(self, heading, message=""):
        self._cancelled = bool(runtime.call_host("progress", "create", _text(heading), _text(message), 0))

    def update(self, percent, message="", *args):
        self._cancelled = bool(runtime.call_host("progress", "update", "", _text(message), int(percent or 0)))

    def iscanceled(self):
        return self._cancelled

    def close(self):
        runtime.call_host("progress", "close", "", "", 100)


class DialogProgressBG(DialogProgress):
    def isFinished(self):
        return False


class Window(object):
    def __init__(self, existingWindowId=-1):
        self._id = existingWindowId

    def getProperty(self, key):
        return runtime.window_properties.get(_text(key).lower(), "")

    def setProperty(self, key, value):
        runtime.window_properties[_text(key).lower()] = _text(value)

    def clearProperty(self, key):
        runtime.window_properties.pop(_text(key).lower(), None)

    def clearProperties(self):
        runtime.window_properties.clear()

    def show(self):
        pass

    def close(self):
        pass

    def doModal(self):
        pass

    def getFocusId(self):
        return 0

    def getControl(self, control_id):
        return _Control()

    def addControl(self, control):
        pass

    def addControls(self, controls):
        pass

    def removeControl(self, control):
        pass

    def setFocus(self, control):
        pass

    def setFocusId(self, control_id):
        pass

    def onAction(self, action):
        pass

    def onClick(self, control_id):
        pass

    def onInit(self):
        pass


class WindowDialog(Window):
    pass


class WindowXML(Window):
    def __init__(self, xmlFilename="", scriptPath="", defaultSkin="Default", defaultRes="720p", *args, **kwargs):
        Window.__init__(self)


class WindowXMLDialog(WindowXML):
    pass


class _Control(object):
    def __getattr__(self, name):
        return lambda *a, **k: None


class Control(_Control):
    pass


class ControlImage(_Control):
    def __init__(self, *args, **kwargs):
        pass


class ControlLabel(ControlImage):
    pass


class ControlTextBox(ControlImage):
    pass


class ControlButton(ControlImage):
    pass


class Action(object):
    def getId(self):
        return 0

    def getButtonCode(self):
        return 0
