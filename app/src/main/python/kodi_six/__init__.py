# -*- coding: utf-8 -*-
"""kodi_six for Python 3: the Kodi modules are already unicode-friendly."""
import xbmc  # noqa: F401
import xbmcaddon  # noqa: F401
import xbmcgui  # noqa: F401
import xbmcplugin  # noqa: F401
import xbmcvfs  # noqa: F401

__all__ = ["xbmc", "xbmcaddon", "xbmcgui", "xbmcplugin", "xbmcvfs"]
