# -*- coding: utf-8 -*-
"""Emulation of Kodi's ``xbmc`` module for Zapette."""
from __future__ import unicode_literals

import json
import os
import time

from zapette_kodi import runtime, addoninfo

LOGDEBUG, LOGINFO, LOGNOTICE, LOGWARNING, LOGERROR, LOGSEVERE, LOGFATAL, LOGNONE = 0, 1, 1, 2, 3, 4, 4, 5

PLAYLIST_MUSIC = 0
PLAYLIST_VIDEO = 1
ISO_639_1 = 0
ISO_639_2 = 1
ENGLISH_NAME = 2
TRAY_OPEN = 16
DRIVE_NOT_READY = 1
SERVER_WEBSERVER = 1

abortRequested = False

_LANG_NAMES = {"fr": "French", "en": "English"}


def log(msg, level=LOGDEBUG):
    runtime.call_host("log", int(level), "%s" % (msg,))


def sleep(ms):
    time.sleep(max(0, ms) / 1000.0)


def translatePath(path):
    import xbmcvfs
    return xbmcvfs.translatePath(path)


def executebuiltin(command, wait=False):
    runtime.current.builtins.append("%s" % command)


def executeJSONRPC(request):
    try:
        req = json.loads(request)
    except ValueError:
        return json.dumps({"jsonrpc": "2.0", "id": 1, "error": {"code": -32700}})
    method = req.get("method", "")
    params = req.get("params", {}) or {}
    result = None
    if method == "Settings.GetSettingValue":
        values = {
            "locale.timezone": runtime.TIMEZONE,
            "locale.language": "resource.language." + runtime.LANGUAGE,
            "network.usehttpproxy": False,
            "network.httpproxytype": 0,
            "network.httpproxyserver": "",
            "network.httpproxyport": 8080,
            "network.httpproxyusername": "",
            "network.httpproxypassword": "",
            "subtitles.languages": ["French"],
            "locale.subtitlelanguage": "fr",
            "locale.audiolanguage": "fr",
        }
        result = {"value": values.get(params.get("setting"))}
    elif method == "Addons.GetAddonDetails":
        result = {"addon": {"addonid": params.get("addonid"), "enabled": True, "version": "21.0.0"}}
    elif method == "Addons.ExecuteAddon" or method.startswith("Player.") or method.startswith("GUI."):
        result = "OK"
    else:
        result = {}
    return json.dumps({"jsonrpc": "2.0", "id": req.get("id", 1), "result": result})


def getInfoLabel(label):
    label = "%s" % label
    sel = runtime.selected_item
    if label == "System.BuildVersion":
        return "%s (%s.0) Git:zapette" % (runtime.KODI_VERSION, runtime.KODI_VERSION)
    if label == "System.Language":
        return _LANG_NAMES.get(runtime.LANGUAGE[:2], "English")
    if label == "ListItem.Label":
        return sel.get("label", "")
    if label == "ListItem.Title":
        return sel.get("info", {}).get("title", sel.get("label", ""))
    if label == "ListItem.Plot":
        return sel.get("info", {}).get("plot", "")
    if label == "ListItem.FilenameAndPath" or label == "ListItem.Path":
        return sel.get("url", "")
    if label.startswith("ListItem.Art(") and label.endswith(")"):
        return sel.get("art", {}).get(label[13:-1], "")
    if label.startswith("ListItem.Property(") and label.endswith(")"):
        return sel.get("properties", {}).get(label[18:-1], "")
    if label.startswith("ListItem."):
        return "%s" % sel.get("info", {}).get(label[9:].lower(), "")
    if label == "Container.FolderPath":
        return runtime.current.url
    if label == "System.Date":
        return time.strftime("%d/%m/%Y")
    if label == "System.Time":
        return time.strftime("%H:%M")
    if label.startswith("Window(") and ".Property(" in label:
        key = label.split(".Property(", 1)[1].rstrip(")")
        return runtime.window_properties.get(key, "")
    return ""


def getCondVisibility(condition):
    condition = "%s" % condition
    if condition.startswith("System.HasAddon(") or condition.startswith("System.AddonIsEnabled("):
        name = condition[condition.index("(") + 1:-1]
        return name.startswith("inputstream.") or name.startswith("script.module.") \
            or name == runtime.ADDON_ID or name.startswith("resource.")
    if condition in ("System.Platform.Android",):
        return True
    if condition.startswith("System.Platform."):
        return False
    if condition.startswith("Player.") or condition.startswith("Window.IsActive"):
        return False
    return False


def getRegion(id):
    return {
        "datelong": "%A %d %B %Y", "dateshort": "%d/%m/%Y", "tempunit": "°C",
        "speedunit": "km/h", "time": "%H:%M:%S", "meridiem": "",
    }.get(id, "")


def getLanguage(format=ENGLISH_NAME, region=False):
    lang = runtime.LANGUAGE[:2]
    if format == ISO_639_1:
        return lang + ("-" + runtime.LANGUAGE[3:].upper() if region else "")
    if format == ISO_639_2:
        return {"fr": "fre", "en": "eng"}.get(lang, "eng")
    return _LANG_NAMES.get(lang, "English")


def convertLanguage(language, format):
    table = {"fr": ("fr", "fre", "French"), "french": ("fr", "fre", "French"),
             "fre": ("fr", "fre", "French"), "fra": ("fr", "fre", "French"),
             "en": ("en", "eng", "English"), "english": ("en", "eng", "English"),
             "eng": ("en", "eng", "English")}
    entry = table.get(("%s" % language).lower())
    return entry[format] if entry else language


def getLocalizedString(string_id):
    return addoninfo.localize(None, string_id)


def getSkinDir():
    return "skin.estuary"


def getUserAgent():
    return "Kodi/%s (Linux; Android) App_Bitness/64 Version/%s" % (runtime.KODI_VERSION, runtime.KODI_VERSION)


def getFreeMem():
    return 1024


def getIPAddress():
    return "127.0.0.1"


def getCacheThumbName(path):
    import zlib
    return "%08x.tbn" % (zlib.crc32(path.encode("utf-8")) & 0xffffffff)


def getSupportedMedia(media):
    return ".m3u8|.mpd|.mp4|.mkv|.ts|.mp3|.aac"


def validatePlatformPath(path):
    return path


def makeLegalFilename(path):
    return path


def audioResume():
    pass


def audioSuspend():
    pass


def stopSFX():
    pass


def playSFX(*args, **kwargs):
    pass


def enableNavSounds(yesno):
    pass


def getGlobalIdleTime():
    return 0


def startServer(*args, **kwargs):
    return True


class Keyboard(object):
    def __init__(self, line="", heading="", hidden=False):
        self._text = line or ""
        self._heading = heading or ""
        self._hidden = hidden
        self._confirmed = False

    def doModal(self, autoclose=0):
        answer = runtime.call_host("input", "%s" % self._heading, "%s" % self._text,
                                   bool(self._hidden), "text")
        if answer is None:
            self._confirmed = False
        else:
            self._text = "%s" % answer
            self._confirmed = True

    def setDefault(self, line=""):
        self._text = line

    def setHeading(self, heading):
        self._heading = heading

    def setHiddenInput(self, hidden=False):
        self._hidden = hidden

    def isConfirmed(self):
        return self._confirmed

    def getText(self):
        return self._text


class Monitor(object):
    def __init__(self, *args, **kwargs):
        pass

    def abortRequested(self):
        return False

    def waitForAbort(self, timeout=None):
        if timeout:
            time.sleep(min(timeout, 0.05))
        return False

    def onSettingsChanged(self):
        pass

    def onNotification(self, sender, method, data):
        pass


class InfoTagVideo(object):
    def __init__(self, *args, **kwargs):
        self._data = {}

    def __getattr__(self, name):
        if name.startswith("get"):
            key = name[3:].lower()
            return lambda *a, **k: self._data.get(key, "")
        if name.startswith("set") or name.startswith("add"):
            key = name[3:].lower()

            def setter(value=None, *a, **k):
                self._data[key] = value
            return setter
        raise AttributeError(name)


class Player(object):
    def __init__(self, *args, **kwargs):
        pass

    def play(self, item=None, listitem=None, windowed=False, startpos=-1):
        path = item if isinstance(item, (str, bytes)) else None
        if listitem is not None:
            runtime.current.resolved = (True, listitem)
        elif path:
            import xbmcgui
            li = xbmcgui.ListItem(path=path)
            runtime.current.resolved = (True, li)

    def stop(self):
        pass

    def pause(self):
        pass

    def isPlaying(self):
        return False

    def isPlayingVideo(self):
        return False

    def isPlayingAudio(self):
        return False

    def getTime(self):
        return 0.0

    def getTotalTime(self):
        return 0.0

    def seekTime(self, t):
        pass

    def getPlayingFile(self):
        return ""

    def getVideoInfoTag(self):
        return InfoTagVideo()

    def showSubtitles(self, visible):
        pass

    def setSubtitles(self, path):
        pass

    def getAvailableSubtitleStreams(self):
        return []

    def getAvailableAudioStreams(self):
        return []


class PlayList(object):
    def __init__(self, playlist=PLAYLIST_VIDEO):
        self._kind = playlist

    def add(self, url, listitem=None, index=-1):
        runtime.current.playlist.append((url, listitem))

    def clear(self):
        del runtime.current.playlist[:]

    def size(self):
        return len(runtime.current.playlist)

    def __len__(self):
        return len(runtime.current.playlist)

    def getposition(self):
        return 0

    def getPlayListId(self):
        return self._kind

    def shuffle(self):
        pass

    def unshuffle(self):
        pass

    def remove(self, filename):
        runtime.current.playlist[:] = [p for p in runtime.current.playlist if p[0] != filename]

    def __getitem__(self, i):
        return runtime.current.playlist[i][1]


class RenderCapture(object):
    pass
