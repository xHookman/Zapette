# -*- coding: utf-8 -*-
"""
Replacement of script.module.youtube.dl (YDStreamExtractor) based on yt-dlp.
Only the API used by codequick's ``Resolver.extract_source`` is implemented.
"""
from __future__ import unicode_literals

_output_callback = None
_params = {}


def setOutputCallback(callback):
    global _output_callback
    _output_callback = callback


def overrideParam(key, value):
    _params[key] = value


class _Logger(object):
    def _emit(self, prefix, msg):
        if _output_callback:
            try:
                _output_callback(prefix + msg)
            except Exception:
                pass

    def debug(self, msg):
        self._emit("", msg)

    def warning(self, msg):
        self._emit("WARNING: ", msg)

    def error(self, msg):
        self._emit("ERROR: ", msg.replace("ERROR: ", ""))


_FORMATS = {
    0: "best[height<=480]/best",
    1: "best[height<=720]/best",
    2: "best[height<=1080]/best",
    3: "best",
}


class VideoInfo(object):
    def __init__(self, entries, source_name):
        self._entries = entries
        self._selected = 0
        self.sourceName = source_name
        self.title = entries[0].get("title", "") if entries else ""

    def hasMultipleStreams(self):
        return len(self._entries) > 1

    def streams(self):
        return [{"title": e.get("title", ""),
                 "ytdl_format": {"extractor": e.get("extractor", "")},
                 "xbmc_url": e.get("url", "")} for e in self._entries]

    def selectStream(self, index):
        self._selected = index

    def streamURL(self):
        entry = self._entries[self._selected]
        url = entry.get("url", "")
        headers = entry.get("http_headers") or {}
        if headers and url:
            try:
                from urllib.parse import urlencode
            except ImportError:  # pragma: no cover
                from urllib import urlencode
            url = url + "|" + urlencode(headers)
        return url


def getVideoInfo(url, quality=None, resolve_redirects=False):
    try:
        import yt_dlp as youtube_dl
    except ImportError:
        try:
            import youtube_dl
        except ImportError:
            _Logger().error("yt-dlp n'est pas disponible")
            return None
    opts = {"quiet": True, "no_warnings": True, "logger": _Logger(),
            "format": _FORMATS.get(quality if quality is not None else 3, "best"),
            "noplaylist": False, "skip_download": True}
    opts.update(_params)
    try:
        with youtube_dl.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
    except Exception as e:
        _Logger().error("%s" % e)
        return None
    if not info:
        return None
    entries = info.get("entries") or [info]
    entries = [e for e in entries if e and e.get("url")]
    if not entries:
        return None
    return VideoInfo(entries, info.get("extractor_key", "").lower() or info.get("extractor", ""))


def handleDownload(info, bg=False, path=None, filename=None):
    _Logger().error("Le téléchargement n'est pas pris en charge")
