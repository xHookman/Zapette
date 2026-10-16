# -*- coding: utf-8 -*-
"""Emulation of Kodi's ``xbmcaddon`` module."""
from __future__ import unicode_literals

from zapette_kodi import runtime, addoninfo


class Addon(object):
    def __init__(self, id=None):
        self._id = id or runtime.ADDON_ID

    def getAddonInfo(self, key):
        return "%s" % addoninfo.addon_info(self._id).get(key, "")

    def getLocalizedString(self, string_id):
        return addoninfo.localize(self._id, string_id)

    # --- settings
    def getSetting(self, key):
        return "%s" % addoninfo.get_setting(self._id, key)

    def getSettingString(self, key):
        return self.getSetting(key)

    def getSettingBool(self, key):
        return self.getSetting(key).lower() == "true"

    def getSettingInt(self, key):
        try:
            return int(float(self.getSetting(key) or 0))
        except ValueError:
            return 0

    def getSettingNumber(self, key):
        try:
            return float(self.getSetting(key) or 0)
        except ValueError:
            return 0.0

    def setSetting(self, key, value):
        addoninfo.set_setting(self._id, key, value)
        return True

    def setSettingString(self, key, value):
        return self.setSetting(key, value)

    def setSettingBool(self, key, value):
        return self.setSetting(key, "true" if value else "false")

    def setSettingInt(self, key, value):
        return self.setSetting(key, int(value))

    def setSettingNumber(self, key, value):
        return self.setSetting(key, value)

    def openSettings(self):
        runtime.current.builtins.append("Addon.OpenSettings(%s)" % self._id)

    def getSettings(self):
        return _Settings(self)


class _Settings(object):
    """Kodi 20 ``xbmcaddon.Settings`` subset."""

    def __init__(self, addon):
        self._addon = addon

    def getBool(self, key):
        return self._addon.getSettingBool(key)

    def getInt(self, key):
        return self._addon.getSettingInt(key)

    def getNumber(self, key):
        return self._addon.getSettingNumber(key)

    def getString(self, key):
        return self._addon.getSetting(key)

    def setBool(self, key, value):
        self._addon.setSettingBool(key, value)

    def setInt(self, key, value):
        self._addon.setSettingInt(key, value)

    def setNumber(self, key, value):
        self._addon.setSettingNumber(key, value)

    def setString(self, key, value):
        self._addon.setSetting(key, value)
