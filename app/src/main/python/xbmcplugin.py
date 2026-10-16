# -*- coding: utf-8 -*-
"""Emulation of Kodi's ``xbmcplugin`` module: records everything in the session."""
from __future__ import unicode_literals

from zapette_kodi import runtime, addoninfo

_SORT_METHODS = [
    "NONE", "LABEL", "LABEL_IGNORE_THE", "DATE", "SIZE", "FILE", "DRIVE_TYPE", "TRACKNUM",
    "DURATION", "TITLE", "TITLE_IGNORE_THE", "ARTIST", "ARTIST_AND_YEAR", "ARTIST_IGNORE_THE",
    "ALBUM", "ALBUM_IGNORE_THE", "GENRE", "COUNTRY", "VIDEO_YEAR", "VIDEO_RATING",
    "VIDEO_USER_RATING", "DATEADDED", "PROGRAM_COUNT", "PLAYLIST_ORDER", "EPISODE",
    "VIDEO_TITLE", "VIDEO_SORT_TITLE", "VIDEO_SORT_TITLE_IGNORE_THE", "PRODUCTIONCODE",
    "SONG_RATING", "SONG_USER_RATING", "MPAA_RATING", "VIDEO_RUNTIME", "STUDIO",
    "STUDIO_IGNORE_THE", "FULLPATH", "LABEL_IGNORE_FOLDERS", "LASTPLAYED", "PLAYCOUNT",
    "LISTENERS", "UNSORTED", "CHANNEL", "BITRATE", "DATE_TAKEN",
]
for _i, _name in enumerate(_SORT_METHODS):
    globals()["SORT_METHOD_" + _name] = _i
del _i, _name


def addDirectoryItem(handle, url, listitem, isFolder=False, totalItems=0):
    runtime.current.items.append(("%s" % url, listitem, bool(isFolder)))
    return True


def addDirectoryItems(handle, items, totalItems=0):
    for entry in items:
        url, listitem = entry[0], entry[1]
        is_folder = entry[2] if len(entry) > 2 else False
        addDirectoryItem(handle, url, listitem, is_folder)
    return True


def endOfDirectory(handle, succeeded=True, updateListing=False, cacheToDisc=True):
    session = runtime.current
    session.ended = True
    session.succeeded = bool(succeeded)
    session.update_listing = bool(updateListing)


def setResolvedUrl(handle, succeeded, listitem):
    runtime.current.resolved = (bool(succeeded), listitem)


def setContent(handle, content):
    runtime.current.content = "%s" % content


def setPluginCategory(handle, category):
    runtime.current.category = "%s" % category


def addSortMethod(handle, sortMethod, label2Mask="", labelMask=""):
    runtime.current.sort_methods.append(sortMethod)


def setProperty(handle, key, value):
    pass


def setPluginFanart(handle, image=None, color1=None, color2=None, color3=None):
    pass


def getSetting(handle, key):
    return addoninfo.get_setting(runtime.ADDON_ID, key)


def setSetting(handle, key, value):
    addoninfo.set_setting(runtime.ADDON_ID, key, value)
