# -*- coding: utf-8 -*-
"""
Runs Catch-up TV & More exactly like Kodi does: one plugin:// url per call,
with sys.argv set, then collects what it sent through xbmcplugin.
"""
from __future__ import unicode_literals

import importlib
import json
import os
import sys
import time
import traceback

try:
    from urllib.parse import urlsplit, parse_qsl
except ImportError:  # pragma: no cover
    from urlparse import urlsplit, parse_qsl

from zapette_kodi import runtime, addoninfo, patches
from zapette_kodi.convert import clean_label, item_to_json, stream_to_json

CODEQUICK_ID = "script.module.codequick"
PLUGIN_PREFIX = "plugin://%s" % runtime.ADDON_ID

# First-run pop-ups of the add-on that make no sense in Zapette
_SILENT_DEFAULTS = {"show_live_tv_m3u_info": "false", "log_pop_up": "false"}


# ------------------------------------------------------------------- set up
def init(home_dir, language, timezone, host):
    runtime.HOME_DIR = home_dir
    runtime.LANGUAGE = language or "fr_fr"
    runtime.TIMEZONE = timezone or "Europe/Paris"
    if host is not None:
        runtime.host = host
    os.environ["TZ"] = runtime.TIMEZONE
    if hasattr(time, "tzset"):
        time.tzset()
    for folder in (runtime.addons_dir(), runtime.userdata_dir(), runtime.temp_dir(), runtime.addon_profile()):
        if not os.path.isdir(folder):
            os.makedirs(folder)
    _add_import_paths()
    patches.install()
    stored = addoninfo.load_settings(runtime.ADDON_ID)
    for key, value in _SILENT_DEFAULTS.items():
        if key not in stored:
            addoninfo.set_setting(runtime.ADDON_ID, key, value)


def _add_import_paths():
    for path in (os.path.join(runtime.addon_path(CODEQUICK_ID), "lib"), runtime.addon_path(runtime.ADDON_ID)):
        if path not in sys.path:
            sys.path.insert(0, path)


def reload_addon():
    """Forgets every module of the add-on so the next call imports the new version."""
    with runtime.lock:
        for name in list(sys.modules):
            if name in ("resources", "urlquick", "addon") or name.startswith(("resources.", "codequick")):
                del sys.modules[name]
        addoninfo.reset_caches()
        _add_import_paths()


# ------------------------------------------------------------------- invoke
def invoke(url, selected):
    """Runs the add-on for one url. `selected` describes the item the user chose (ListItem.* labels)."""
    with runtime.lock:
        runtime.selected_item = selected or {}
        return _invoke(url, depth=0)


def _run(url):
    parts = urlsplit(url)
    sys.argv = ["%s://%s%s" % (parts.scheme, parts.netloc, parts.path or "/"), "1",
                "?" + parts.query if parts.query else "", "resume:false"]
    session = runtime.new_session(url)
    try:
        exception = importlib.import_module("codequick").run()
    except SystemExit:
        exception = None
    return session, exception


def _invoke(url, depth):
    if not url.startswith("plugin://"):
        url = PLUGIN_PREFIX + "/" + url.lstrip("/")
    try:
        session, exception = _run(url)
    except Exception as e:  # e.g. the add-on module failed to import
        runtime.call_host("log", 3, traceback.format_exc())
        return {"kind": "error", "url": url, "error": runtime.redact("%s: %s" % (e.__class__.__name__, e)),
                "notifications": runtime.current.notifications}

    result = {"url": url, "category": clean_label(session.category), "builtins": session.builtins,
              "notifications": session.notifications, "update_listing": session.update_listing}

    # 1. A playable item was resolved
    if session.resolved is not None:
        ok, listitem = session.resolved
        if not ok or listitem is None:
            result.update(kind="error", error=error_text(exception, session) or "Lecture impossible.")
            return result
        stream = stream_to_json(listitem)
        target = stream["url"]
        if target.startswith(PLUGIN_PREFIX) and depth < 3:     # redirect to another route
            return _invoke(target, depth + 1)
        if target.startswith(("plugin://plugin.video.youtube", "plugin://plugin.video.dailymotion")):
            stream = _external_video(target) or stream
        if stream["url"].startswith("plugin://"):
            result.update(kind="error", error="Ce contenu n'est pas lisible dans Zapette (%s)."
                          % stream["url"].split("/")[2])
            return result
        result.update(kind="resolved", stream=stream)
        return result

    # 2. The call failed before listing anything
    if exception is not None and not session.items:
        result.update(kind="error", error=error_text(exception, session))
        return result

    # 3. A directory listing
    if session.items or session.ended:
        items = []
        for item_url, listitem, is_folder in session.items:
            try:
                items.append(item_to_json(item_url, listitem, is_folder))
            except Exception:
                runtime.call_host("log", 3, traceback.format_exc())
        result.update(kind="directory" if items or session.succeeded else "empty", items=items)
        return result

    # 4. A script that only ran Kodi commands (builtins)
    result.update(kind="action")
    return result


def error_text(exception, session):
    """A short French message for the user (secrets masked)."""
    return runtime.redact(_error_text(exception, session))


def _error_text(exception, session):
    if exception is None:
        if session.notifications:
            n = session.notifications[-1]
            return clean_label("%s : %s" % (n["heading"], n["message"]))
        return ""
    name = exception.__class__.__name__
    message = "%s" % exception
    response = getattr(exception, "response", None)
    code = getattr(response, "status_code", None)
    if name == "HTTPError" and code:
        if code in (401, 403):
            return "Accès refusé par la chaîne (%d). Le contenu est peut-être géo-restreint ou réservé aux abonnés." % code
        if code == 404:
            return "Ce contenu n'est plus disponible (404)."
        return "Erreur du serveur de la chaîne (%d)." % code
    if "No items found" in message:
        return "Aucun contenu disponible ici pour le moment."
    if is_network_error(exception):
        return "Connexion à la chaîne impossible. Vérifiez votre accès internet."
    return clean_label("%s : %s" % (name, message)) if message else name


def is_network_error(exception):
    try:
        import requests
        return isinstance(exception, (requests.exceptions.ConnectionError, requests.exceptions.Timeout))
    except ImportError:  # pragma: no cover
        return False


def _external_video(url):
    """YouTube / Dailymotion add-on urls -> stream extracted with yt-dlp."""
    params = dict(parse_qsl(urlsplit(url).query))
    video = params.get("video_id") or params.get("videoid") or params.get("url")
    if not video:
        return None
    page = ("https://www.youtube.com/watch?v=" if "youtube" in url else "https://www.dailymotion.com/video/") + video
    import YDStreamExtractor
    import xbmcgui
    info = YDStreamExtractor.getVideoInfo(page, 3)
    return stream_to_json(xbmcgui.ListItem(path=info.streamURL())) if info else None
