# -*- coding: utf-8 -*-
"""
Conversion of what the add-on gives Kodi (ListItems, urls, labels) into the
plain JSON the Android app understands.
"""
from __future__ import unicode_literals

import binascii
import io
import json
import os
import pickle
import re

try:
    from urllib.parse import urlsplit, parse_qsl
except ImportError:  # pragma: no cover
    from urlparse import urlsplit, parse_qsl

from zapette_kodi import runtime, addoninfo

IMAGES_BASE = ("https://raw.githubusercontent.com/Catch-up-TV-and-More/"
               "resource.images.catchuptvandmore/master/"
               "resource.images.catchuptvandmore/resources/")


def to_json(obj):
    return json.dumps(obj, ensure_ascii=False, default=lambda o: "%s" % (o,))


# --------------------------------------------------------------- labels / art
_KODI_TAGS = re.compile(r"\[/?(B|I|LIGHT|UPPERCASE|LOWERCASE|CAPITALIZE|COLOR[^\]]*)\]", re.I)


def clean_label(label):
    """Removes Kodi formatting tags ([B], [COLOR red]...)."""
    if not label:
        return ""
    label = _KODI_TAGS.sub("", ("%s" % label).replace("[CR]", "\n"))
    return re.sub(r"[ \t]{2,}", " ", label).strip()


def localized_label(label):
    m = re.match(r"^\$LOCALIZE\[(\d+)\]$", label or "")
    if m:
        return addoninfo.localize(runtime.ADDON_ID, int(m.group(1))) or label
    return clean_label(label)


def map_art(value):
    """Kodi image path -> URL the app can load ("" when not usable)."""
    if not value:
        return ""
    value = "%s" % value
    if value.startswith("resource://resource.images.catchuptvandmore/"):
        return IMAGES_BASE + value.split("resource.images.catchuptvandmore/", 1)[1]
    if value.startswith("special://"):
        import xbmcvfs
        value = xbmcvfs.translatePath(value)
    if value.startswith("/"):
        return "file://" + value if os.path.exists(value) else ""
    if value.startswith("//"):
        return "https:" + value
    if value.startswith("http"):
        return value.split("|", 1)[0]  # Kodi allows "url|headers" for images too
    return ""


# ----------------------------------------------------------------- parameters
class _PlainUnpickler(pickle.Unpickler):
    """Plain data only (dict, list, str, numbers): reading parameters can never run code."""

    def find_class(self, module, name):
        raise pickle.UnpicklingError("objet interdit: %s.%s" % (module, name))


def decode_params(url):
    """codequick's pickled query string -> JSON-safe dict."""
    try:
        params = dict(parse_qsl(urlsplit(url).query, keep_blank_values=True))
    except Exception:
        return {}
    if "_pickle_" in params:
        try:
            data = binascii.unhexlify(params.pop("_pickle_"))
            params.update(_PlainUnpickler(io.BytesIO(data)).load())
        except Exception:
            pass
    return {"%s" % k: v for k, v in params.items()
            if v is None or isinstance(v, (str, int, float, bool))}


def parse_headers(raw):
    """"Name=value&Name2=value2" (Kodi's url|headers syntax) -> dict."""
    return dict(parse_qsl(raw, keep_blank_values=True)) if raw else {}


# ------------------------------------------------------------------- metadata
_TEXT_INFO = ("plot", "plotoutline", "title", "tvshowtitle", "tagline", "originaltitle")
_NUMBER_INFO = ("duration", "year", "episode", "season", "playcount")
_LIST_INFO = ("genre", "studio", "country", "director", "credits", "cast")
_PLAIN_INFO = ("aired", "premiered", "date", "dateadded", "mediatype", "mpaa", "rating")


def info_to_json(info):
    out = {}
    for key, value in (info or {}).items():
        if value is None or value == "":
            continue
        if key in _TEXT_INFO:
            out[key] = clean_label(value)
        elif key in _NUMBER_INFO:
            try:
                number = int(float(value))
            except (TypeError, ValueError):
                continue
            if number >= 0:
                out[key] = number
        elif key in _LIST_INFO:
            out[key] = ", ".join("%s" % v for v in value if v) if isinstance(value, (list, tuple)) else "%s" % value
        elif key in _PLAIN_INFO:
            out[key] = "%s" % value
    return out


# ---------------------------------------------------------------- menu items
# Context-menu actions the app knows how to perform
_SUPPORTED_ACTIONS = ("runplugin", "container.update", "playmedia", "container.refresh")


def _context_label(label):
    """"Ajouter aux favoris du plugin" → "Ajouter aux favoris": Zapette users don't know the plugin."""
    return re.sub(r"\s+(du plugin|de l'extension|de l’extension)$", "", localized_label(label))


def _useful_context(action):
    if "/resources/lib/vpn/" in action:  # OpenVPN does not exist on Android TV
        return False
    return action.split("(", 1)[0].strip().lower() in _SUPPORTED_ACTIONS


def item_to_json(url, listitem, is_folder):
    """One entry of a directory listing."""
    data = listitem.to_dict()
    props = data["properties"]
    params = decode_params(url)
    if props.get("isplayable") in ("true", "false"):
        playable = props["isplayable"] == "true"
    else:
        playable = not is_folder

    kind = "folder" if is_folder else ("playable" if playable else "action")
    if "_nextpagecount_" in params:
        kind = "next_page"
    elif "/codequick/search/saved_searches" in url and params.get("first_load"):
        kind = "search"

    art = {k: map_art(v) for k, v in data["art"].items()}
    return {
        "url": url,
        "kind": kind,
        "label": clean_label(data["label"]),
        "label2": clean_label(data["label2"]),
        "art": {k: v for k, v in art.items() if v},
        "info": info_to_json(data["info"]),
        "params": params,
        "context": [{"label": _context_label(c["label"]), "action": c["action"]}
                    for c in data["context"] if _useful_context(c["action"])],
    }


# -------------------------------------------------------------------- streams
def _manifest_type(path, props, mime):
    """hls | dash | ss | auto, from what the add-on declared, else from the URL."""
    declared = props.get("inputstream.adaptive.manifest_type", "")
    if declared in ("mpd", "hls", "ism"):
        return {"mpd": "dash", "hls": "hls", "ism": "ss"}[declared]
    mime = (mime or "").lower()
    if "dash" in mime:
        return "dash"
    if "mpegurl" in mime:
        return "hls"
    lower = path.lower()
    bare = lower.split("?", 1)[0]
    if bare.endswith(".mpd") or "/dash/" in bare or "format=mpd" in lower:
        return "dash"
    if ".m3u8" in lower:
        return "hls"
    if bare.endswith(".ism") or bare.endswith("/manifest") or ".ism/" in bare:
        return "ss"
    return "auto"


def _drm(props):
    """inputstream.adaptive license properties -> DRM description (or None).

    license_key syntax: url | headers | body template (R{SSM}...) | response format (JBlicense...)
    """
    license_key = props.get("inputstream.adaptive.license_key", "")
    license_type = props.get("inputstream.adaptive.license_type", "")
    if not license_key and (not license_type or license_type == "com.widevine.alpha"):
        return None
    parts = (license_key.split("|") + ["", "", "", ""])[:4]
    drm = {
        "type": license_type or "com.widevine.alpha",
        "license_url": parts[0],
        "license_headers": parse_headers(parts[1]),
        "challenge": parts[2] or "R{SSM}",
        "response": parts[3],
        "server_certificate": props.get("inputstream.adaptive.server_certificate", ""),
    }
    if drm["type"] == "org.w3.clearkey" and not drm["license_url"]:
        drm["license_url"] = license_key
    return drm


def stream_to_json(listitem):
    """The playable ListItem given to setResolvedUrl -> what ExoPlayer needs."""
    data = listitem.to_dict()
    props = data["properties"]
    path = data["path"]
    headers = {}
    if "|" in path:
        path, raw = path.split("|", 1)
        headers.update(parse_headers(raw))
    for key in ("inputstream.adaptive.common_headers", "inputstream.adaptive.manifest_headers",
                "inputstream.adaptive.stream_headers"):
        headers.update(parse_headers(props.get(key)))
    return {
        "url": path,
        "headers": headers,
        "manifest": _manifest_type(path, props, data["mimetype"]),
        "mimetype": data["mimetype"],
        "drm": _drm(props),
        "subtitles": [s for s in data["subtitles"] if s],
        "label": clean_label(data["label"]),
        "art": {k: map_art(v) for k, v in data["art"].items() if map_art(v)},
        "info": info_to_json(data["info"]),
    }
