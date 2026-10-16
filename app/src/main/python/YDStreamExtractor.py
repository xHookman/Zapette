# -*- coding: utf-8 -*-
"""
Replacement of script.module.youtube.dl (YDStreamExtractor) based on yt-dlp.
Only the API used by codequick's ``Resolver.extract_source`` is implemented.

Unlike Kodi, Zapette does not let yt-dlp pick a format: it reads the list
itself and prefers the HLS manifest (adaptive, and the only option for most
YouTube live streams), then the best stream with both audio and video.
This avoids "Requested format is not available".
"""
from __future__ import unicode_literals

_output_callback = None
_params = {}

# Max height for the quality setting of the add-on (0 SD, 1 720p, 2 1080p, 3 best)
_MAX_HEIGHT = {0: 480, 1: 720, 2: 1080, 3: 100000}

# YouTube clients that still return playable formats without a JavaScript runtime
_FALLBACK_CLIENTS = {"youtube": {"player_client": ["tv", "android_vr", "ios", "web_safari"]}}


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

    def info(self, msg):
        self._emit("", msg)

    def warning(self, msg):
        self._emit("WARNING: ", msg)

    def error(self, msg):
        self._emit("ERROR: ", msg.replace("ERROR: ", ""))


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


def _is_media(f):
    if not f.get("url"):
        return False
    note = ("%s %s" % (f.get("format_note") or "", f.get("format_id") or "")).lower()
    return "storyboard" not in note and f.get("ext") != "mhtml" and not note.startswith(" sb")


def _pick(info, max_height):
    """Returns (url, http_headers) of the best playable stream of one video."""
    formats = [f for f in (info.get("formats") or []) if _is_media(f)]
    if not formats and info.get("url"):
        return info["url"], info.get("http_headers") or {}

    # 1. HLS master playlist: adaptive quality, works for live streams
    for f in formats:
        if "m3u8" in (f.get("protocol") or "") and f.get("manifest_url"):
            return f["manifest_url"], f.get("http_headers") or {}

    # 2. Best stream with both audio and video, within the quality limit
    def has_av(f):
        return f.get("vcodec") != "none" and f.get("acodec") != "none"

    def score(f):
        return (f.get("height") or 0, f.get("tbr") or 0)

    combined = [f for f in formats if has_av(f)]
    within = [f for f in combined if (f.get("height") or 0) <= max_height] or combined
    if within:
        best = max(within, key=score)
        return best["url"], best.get("http_headers") or {}

    # 3. DASH manifest (separate audio and video, ExoPlayer combines them)
    for f in formats:
        if "dash" in (f.get("protocol") or "") and f.get("manifest_url"):
            return f["manifest_url"], f.get("http_headers") or {}
    return None, {}


def _extract(ydl_module, url, extra):
    opts = {"quiet": True, "no_warnings": True, "logger": _Logger(), "skip_download": True,
            "noplaylist": True, "ignore_no_formats_error": True}
    opts.update(_params)
    opts.update(extra)
    with ydl_module.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(url, download=False, process=False)
        # embed pages / redirects return a reference to the real video
        for _ in range(3):
            if info and info.get("_type") in ("url", "url_transparent") and info.get("url"):
                info = ydl.extract_info(info["url"], download=False, process=False)
            else:
                break
        if info and info.get("_type") == "playlist":
            entries = [e for e in (info.get("entries") or []) if e]
            info = entries[0] if entries else None
            if info and info.get("_type") in ("url", "url_transparent"):
                info = ydl.extract_info(info["url"], download=False, process=False)
        return info


def getVideoInfo(url, quality=None, resolve_redirects=False):
    try:
        import yt_dlp as youtube_dl
    except ImportError:
        try:
            import youtube_dl
        except ImportError:
            _Logger().error("yt-dlp n'est pas disponible")
            return None

    max_height = _MAX_HEIGHT.get(quality if quality is not None else 3, 100000)
    last_error = None
    for extra in ({}, {"extractor_args": _FALLBACK_CLIENTS}):
        try:
            info = _extract(youtube_dl, url, extra)
        except Exception as e:  # try the next set of clients
            last_error = e
            continue
        if not info:
            continue
        stream, headers = _pick(info, max_height)
        if stream:
            entry = {"url": stream, "http_headers": headers, "title": info.get("title", ""),
                     "extractor": info.get("extractor_key") or info.get("extractor") or ""}
            return VideoInfo([entry], (info.get("extractor_key") or info.get("extractor") or "").lower())
    _Logger().error("%s" % (last_error or "Aucun flux lisible trouvé"))
    return None


def handleDownload(info, bg=False, path=None, filename=None):
    _Logger().error("Le téléchargement n'est pas pris en charge")
