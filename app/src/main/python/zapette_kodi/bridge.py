# -*- coding: utf-8 -*-
"""
Zapette <-> Catch-up TV & More bridge.

Every public function takes/returns plain strings (JSON) so that it can be
called from Kotlin through Chaquopy without any type conversion surprises.

    init(home, language, timezone, host)  -> JSON {addon_version, ...}
    invoke(url, selected_json)            -> JSON PluginResult
    live_channels(country_id)             -> JSON [Channel]
    live_countries()                      -> JSON [{id, label}]
    epg(country_id, day_delta)            -> JSON {xmltv_id: [Programme]}
    settings_schema()                     -> JSON [Category]
    set_setting(id, value)                -> "ok"
    menu_url(menu_id)                     -> plugin:// url of a skeleton menu
    reload_addon()                        -> called after an add-on update
"""
from __future__ import unicode_literals

import binascii
import calendar
import datetime
import importlib
import json
import os
import pickle
import re
import sys
import time
import traceback
import xml.etree.ElementTree as ET

try:
    from urllib.parse import urlsplit, parse_qsl, unquote_plus
except ImportError:  # pragma: no cover
    from urlparse import urlsplit, parse_qsl
    from urllib import unquote_plus

from zapette_kodi import runtime, addoninfo

IMAGES_BASE = ("https://raw.githubusercontent.com/Catch-up-TV-and-More/"
               "resource.images.catchuptvandmore/master/"
               "resource.images.catchuptvandmore/resources/")

CODEQUICK_ID = "script.module.codequick"
PLUGIN_PREFIX = "plugin://%s" % runtime.ADDON_ID

# First-run pop-ups of the add-on that make no sense in Zapette
_SILENT_DEFAULTS = {
    "show_live_tv_m3u_info": "false",
    "log_pop_up": "false",
}

_initialized = False


# ------------------------------------------------------------------ helpers
def _dumps(obj):
    return json.dumps(obj, ensure_ascii=False, default=lambda o: "%s" % (o,))


_tag_re = re.compile(r"\[/?(B|I|LIGHT|UPPERCASE|LOWERCASE|CAPITALIZE|COLOR[^\]]*)\]", re.I)


def clean_label(label):
    if not label:
        return ""
    label = "%s" % label
    label = label.replace("[CR]", "\n")
    label = _tag_re.sub("", label)
    return re.sub(r"[ \t]{2,}", " ", label).strip()


def map_art(value):
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
        # Kodi allows "url|headers" for images too: keep the url only
        return value.split("|", 1)[0]
    return ""


def decode_params(url):
    """Decode codequick's pickled query string into a JSON-safe dict."""
    try:
        query = urlsplit(url).query
    except Exception:
        return {}
    params = dict(parse_qsl(query, keep_blank_values=True))
    if "_pickle_" in params:
        try:
            params.update(_safe_unpickle(binascii.unhexlify(params.pop("_pickle_"))))
        except Exception:
            pass
    safe = {}
    for key, value in params.items():
        if isinstance(value, (str, int, float, bool)) or value is None:
            safe["%s" % key] = value
    return safe


class _PlainUnpickler(pickle.Unpickler):
    """Only plain data (dict, list, str, numbers): reading item parameters can never run code."""

    def find_class(self, module, name):
        raise pickle.UnpicklingError("objet interdit: %s.%s" % (module, name))


def _safe_unpickle(data):
    import io
    return _PlainUnpickler(io.BytesIO(data)).load()


def _int(value, default=0):
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return default


def _info_to_json(info):
    out = {}
    for key, value in (info or {}).items():
        if value is None or value == "":
            continue
        if key in ("plot", "plotoutline", "title", "tvshowtitle", "tagline", "originaltitle"):
            out[key] = clean_label(value)
        elif key in ("duration", "year", "episode", "season", "playcount"):
            v = _int(value, -1)
            if v >= 0:
                out[key] = v
        elif key in ("genre", "studio", "country", "director", "credits", "cast"):
            if isinstance(value, (list, tuple)):
                out[key] = ", ".join("%s" % v for v in value if v)
            else:
                out[key] = "%s" % value
        elif key in ("aired", "premiered", "date", "dateadded", "mediatype", "mpaa", "rating"):
            out[key] = "%s" % value
    return out


def item_to_json(url, li, is_folder):
    data = li.to_dict()
    props = data["properties"]
    params = decode_params(url)
    if props.get("isplayable") in ("true", "false"):
        playable = props.get("isplayable") == "true"
    else:
        playable = not is_folder
    art = {k: map_art(v) for k, v in data["art"].items()}
    art = {k: v for k, v in art.items() if v}
    label = clean_label(data["label"])
    kind = "folder" if is_folder else ("playable" if playable else "action")
    if "_nextpagecount_" in params:
        kind = "next_page"
    elif "/codequick/search/saved_searches" in url and params.get("first_load"):
        kind = "search"
    return {
        "url": url,
        "kind": kind,
        "label": label,
        "label2": clean_label(data["label2"]),
        "art": art,
        "info": _info_to_json(data["info"]),
        "params": params,
        "context": [{"label": _localized_label(c["label"]), "action": c["action"]}
                    for c in data["context"] if _useful_context(c["action"])],
    }


def _useful_context(action):
    """Keep the context entries the app can perform (Kodi window actions are dropped)."""
    name = action.split("(", 1)[0].strip().lower()
    if "/resources/lib/vpn/" in action:  # OpenVPN is not available on Android TV
        return False
    return name in ("runplugin", "container.update", "playmedia", "container.refresh")


def _localized_label(label):
    m = re.match(r"^\$LOCALIZE\[(\d+)\]$", label or "")
    if m:
        return addoninfo.localize(runtime.ADDON_ID, int(m.group(1))) or label
    return clean_label(label)


def _parse_headers(raw):
    headers = {}
    if not raw:
        return headers
    for key, value in parse_qsl(raw, keep_blank_values=True):
        headers[key] = value
    return headers


def _guess_manifest(path, props, mime):
    t = props.get("inputstream.adaptive.manifest_type", "")
    if t in ("mpd", "hls", "ism"):
        return {"mpd": "dash", "hls": "hls", "ism": "ss"}[t]
    mime = (mime or "").lower()
    if "dash" in mime:
        return "dash"
    if "mpegurl" in mime:
        return "hls"
    low = path.lower().split("?", 1)[0]
    if low.endswith(".mpd") or "/dash/" in low or "format=mpd" in path.lower():
        return "dash"
    if low.endswith(".m3u8") or ".m3u8" in path.lower():
        return "hls"
    if low.endswith(".ism") or low.endswith("/manifest") or ".ism/" in low:
        return "ss"
    return "auto"


def resolved_to_json(li):
    data = li.to_dict()
    props = data["properties"]
    path = data["path"]
    headers = {}
    if "|" in path:
        path, raw = path.split("|", 1)
        headers.update(_parse_headers(raw))
    for key in ("inputstream.adaptive.common_headers", "inputstream.adaptive.manifest_headers",
                "inputstream.adaptive.stream_headers"):
        if props.get(key):
            headers.update(_parse_headers(props[key]))

    drm = None
    license_key = props.get("inputstream.adaptive.license_key", "")
    license_type = props.get("inputstream.adaptive.license_type", "")
    if license_key or (license_type and license_type != "com.widevine.alpha" and license_type):
        parts = license_key.split("|")
        while len(parts) < 4:
            parts.append("")
        drm = {
            "type": license_type or "com.widevine.alpha",
            "license_url": parts[0],
            "license_headers": _parse_headers(parts[1]),
            "challenge": parts[2] or "R{SSM}",
            "response": parts[3],
            "server_certificate": props.get("inputstream.adaptive.server_certificate", ""),
        }
        if drm["type"] == "org.w3.clearkey" and not drm["license_url"]:
            drm["license_url"] = license_key
    subtitles = [s for s in data["subtitles"] if s]
    return {
        "url": path,
        "headers": headers,
        "manifest": _guess_manifest(path, props, data["mimetype"]),
        "mimetype": data["mimetype"],
        "drm": drm,
        "subtitles": subtitles,
        "label": clean_label(data["label"]),
        "art": {k: map_art(v) for k, v in data["art"].items() if map_art(v)},
        "info": _info_to_json(data["info"]),
        "is_live": props.get("inputstream.adaptive.play_timeshift_buffer") == "true"
                   or props.get("totaltime") is not None and props.get("resumetime") == "43200",
    }


# --------------------------------------------------------------------- init
def init(home_dir, language="fr_fr", timezone="Europe/Paris", host=None):
    global _initialized
    runtime.HOME_DIR = home_dir
    runtime.LANGUAGE = language or "fr_fr"
    runtime.TIMEZONE = timezone or "Europe/Paris"
    if host is not None:
        runtime.host = host
    os.environ["TZ"] = runtime.TIMEZONE
    try:
        time.tzset()
    except AttributeError:  # pragma: no cover - windows
        pass
    for folder in (runtime.addons_dir(), runtime.userdata_dir(), runtime.temp_dir(),
                   runtime.addon_profile()):
        if not os.path.isdir(folder):
            os.makedirs(folder)
    _setup_paths()
    from zapette_kodi import patches
    patches.install()
    for key, value in _SILENT_DEFAULTS.items():
        stored = addoninfo._load(runtime.ADDON_ID)
        if key not in stored:
            addoninfo.set_setting(runtime.ADDON_ID, key, value)
    _initialized = True
    return _dumps({"addon_version": addoninfo.addon_info(runtime.ADDON_ID)["version"],
                   "python": sys.version.split()[0]})


def _setup_paths():
    for path in (os.path.join(runtime.addon_path(CODEQUICK_ID), "lib"),
                 runtime.addon_path(runtime.ADDON_ID)):
        if path not in sys.path:
            sys.path.insert(0, path)


def reload_addon():
    """Forget every module of the add-on so the next call imports the new version."""
    with runtime.lock:
        for name in list(sys.modules):
            if name == "resources" or name.startswith("resources.") or name.startswith("codequick") \
                    or name == "urlquick" or name == "addon":
                del sys.modules[name]
        addoninfo.reset_caches()
        _setup_paths()
    return _dumps({"addon_version": addoninfo.addon_info(runtime.ADDON_ID)["version"]})


# ------------------------------------------------------------------- invoke
def _run_plugin(url):
    """Run the add-on exactly like Kodi does for one plugin:// url."""
    parts = urlsplit(url)
    base = "%s://%s%s" % (parts.scheme, parts.netloc, parts.path or "/")
    query = "?" + parts.query if parts.query else ""
    sys.argv = [base, "1", query, "resume:false"]
    session = runtime.new_session(url)
    codequick = importlib.import_module("codequick")
    exception = codequick.run()
    return session, exception


def invoke(url, selected_json=""):
    with runtime.lock:
        try:
            runtime.selected_item = json.loads(selected_json) if selected_json else {}
        except ValueError:
            runtime.selected_item = {}
        result = _invoke(url, depth=0)
        return _dumps(result)


def _invoke(url, depth):
    if not url.startswith("plugin://"):
        url = PLUGIN_PREFIX + "/" + url.lstrip("/")
    started = time.time()
    try:
        session, exception = _run_plugin(url)
    except SystemExit:
        session, exception = runtime.current, None
    except Exception as e:  # import errors, ...
        runtime.call_host("log", 3, traceback.format_exc())
        return {"kind": "error", "url": url,
                "error": "%s: %s" % (e.__class__.__name__, e),
                "notifications": runtime.current.notifications}

    result = {
        "url": url,
        "category": clean_label(session.category),
        "content": session.content,
        "update_listing": session.update_listing,
        "builtins": session.builtins,
        "notifications": session.notifications,
        "elapsed_ms": int((time.time() - started) * 1000),
    }

    if session.resolved is not None:
        ok, li = session.resolved
        if ok and li is not None:
            stream = resolved_to_json(li)
            target = stream["url"]
            if target.startswith(PLUGIN_PREFIX) and depth < 3:
                return _invoke(target, depth + 1)
            if target.startswith("plugin://plugin.video.youtube") or target.startswith("plugin://plugin.video.dailymotion"):
                converted = _external_plugin_to_web(target)
                if converted:
                    stream = _resolve_with_ytdl(converted) or stream
            if stream["url"].startswith("plugin://"):
                result.update(kind="error", error="Ce contenu nécessite une autre extension Kodi (%s)."
                              % stream["url"].split("/")[2])
                return result
            result.update(kind="resolved", stream=stream)
            return result
        result.update(kind="error", error=_error_text(exception, session) or "Lecture impossible.")
        return result

    if exception is not None and not session.items:
        result.update(kind="error", error=_error_text(exception, session))
        return result

    if session.items or session.ended:
        items = []
        for item_url, li, is_folder in session.items:
            try:
                items.append(item_to_json(item_url, li, is_folder))
            except Exception:
                runtime.call_host("log", 3, traceback.format_exc())
        result.update(kind="directory", items=items)
        if not items and not session.succeeded:
            result.update(kind="empty")
        return result

    # A script (Script.register) or a callback that only executed builtins
    result.update(kind="action")
    return result


def _error_text(exception, session):
    return runtime.redact(_error_text_raw(exception, session))


def _error_text_raw(exception, session):
    if exception is None:
        if session.notifications:
            n = session.notifications[-1]
            return clean_label("%s : %s" % (n["heading"], n["message"]))
        return ""
    name = exception.__class__.__name__
    msg = "%s" % exception
    if name == "HTTPError":
        try:
            code = exception.response.status_code
            if code in (401, 403):
                return "Accès refusé par la chaîne (%d). Le contenu est peut-être géo-restreint ou réservé aux abonnés." % code
            if code == 404:
                return "Ce contenu n'est plus disponible (404)."
            return "Erreur du serveur de la chaîne (%d)." % code
        except Exception:
            pass
    if "No items found" in msg:
        return "Aucun contenu disponible ici pour le moment."
    try:
        import requests
        if isinstance(exception, (requests.exceptions.ConnectionError, requests.exceptions.Timeout)):
            return "Connexion à la chaîne impossible. Vérifiez votre accès internet."
    except ImportError:  # pragma: no cover
        pass
    return clean_label("%s : %s" % (name, msg)) if msg else name


def _external_plugin_to_web(url):
    params = dict(parse_qsl(urlsplit(url).query))
    vid = params.get("video_id") or params.get("videoid") or params.get("url")
    if "youtube" in url and vid:
        return "https://www.youtube.com/watch?v=" + vid
    if "dailymotion" in url and vid:
        return "https://www.dailymotion.com/video/" + vid
    return None


def _resolve_with_ytdl(web_url):
    import YDStreamExtractor
    info = YDStreamExtractor.getVideoInfo(web_url, 3)
    if not info:
        return None
    url = info.streamURL()
    import xbmcgui
    return resolved_to_json(xbmcgui.ListItem(path=url))


# -------------------------------------------------------------- live / menus
def menu_url(menu_id):
    with runtime.lock:
        support = importlib.import_module("codequick.support")
        route = importlib.import_module("codequick.route")
        ref = route.Route.ref("/resources/lib/main:generic_menu")
        return support.build_path(ref, query={"item_id": menu_id})


def live_countries():
    with runtime.lock:
        skel = importlib.import_module("resources.lib.skeletons.live_tv")
        out = []
        for item_id, infos in sorted(skel.menu.items(), key=lambda x: x[1].get("order", 999)):
            if not infos.get("enabled", True):
                continue
            label = infos.get("label", item_id)
            if isinstance(label, int):
                label = addoninfo.localize(runtime.ADDON_ID, label)
            out.append({"id": item_id, "label": clean_label(label),
                        "thumb": map_art("resource://resource.images.catchuptvandmore/" + infos["thumb"])
                        if isinstance(infos.get("thumb"), str) else ""})
        return _dumps(out)


def live_channels(country_id="fr_live"):
    """Structured channel list of a *_live skeleton, with TV numbering (LCN)."""
    with runtime.lock:
        skel = importlib.import_module("resources.lib.skeletons." + country_id)
        support = importlib.import_module("codequick.support")
        resolver = importlib.import_module("codequick.resolver")
        add_utils = importlib.import_module("resources.lib.addon_utils")
        menu_utils = importlib.import_module("resources.lib.menu_utils")
        entries = []
        for item_id, infos in skel.menu.items():
            if not infos.get("enabled", True) or "resolver" not in infos:
                continue
            if menu_utils.is_item_hidden(item_id, country_id):  # "Masquer" in the add-on menus
                continue
            xmltv_id = infos.get("xmltv_id")
            label = add_utils.get_item_label(item_id, infos)
            langs = infos.get("available_languages")
            if isinstance(langs, dict) and langs:
                lang = addoninfo.get_setting(runtime.ADDON_ID, item_id + ".language") or list(langs)[0]
                xmltv_id = (langs.get(lang) or {}).get("xmltv_id", xmltv_id)
            params = {"item_id": item_id}
            if xmltv_id:
                params["xmltv_id"] = xmltv_id
            ref = resolver.Resolver.ref(infos["resolver"])
            url = support.build_path(ref, query=params)
            thumb = infos.get("thumb")
            fanart = infos.get("fanart")
            entries.append({
                "id": item_id,
                "label": clean_label(label),
                "url": url,
                "logo": map_art(add_utils.get_item_media_path(thumb)) if thumb else "",
                "fanart": map_art(add_utils.get_item_media_path(fanart)) if fanart else "",
                "xmltv_id": xmltv_id or "",
                "group": infos.get("m3u_group", ""),
                "m3u_order": infos.get("m3u_order"),
                "order": infos.get("order", 999),
            })
        # TV numbering: TNT channels keep their official number, others follow
        tnt = [e for e in entries if e["group"] == "TNT" and isinstance(e["m3u_order"], int)]
        used = set(e["m3u_order"] for e in tnt)
        for e in tnt:
            e["number"] = e["m3u_order"]
        others = sorted([e for e in entries if e not in tnt], key=lambda e: (e["order"], e["label"]))
        nxt = max(used) + 1 if used else 1
        for e in others:
            while nxt in used:
                nxt += 1
            e["number"] = nxt
            used.add(nxt)
        entries.sort(key=lambda e: e["number"])
        for e in entries:
            e.pop("m3u_order", None)
            e.pop("order", None)
        return _dumps(entries)


# --------------------------------------------------------------------- EPG
def _iso_to_epoch(value):
    try:
        dt = datetime.datetime.strptime(value, "%Y-%m-%dT%H:%M:%S")
        return calendar.timegm(dt.timetuple())
    except (TypeError, ValueError):
        return 0


def epg(country_id="fr_live", day_delta=0):
    xmltv = importlib.import_module("resources.lib.xmltv")
    programmes = xmltv.grab_programmes(country_id, int(day_delta))
    guide = {}
    for p in programmes:
        start = _iso_to_epoch(p.get("start"))
        stop = _iso_to_epoch(p.get("stop"))
        if not start or not stop:
            continue
        entry = {
            "start": start,
            "stop": stop,
            "title": clean_label(p.get("title", "")),
            "subtitle": clean_label(p.get("sub-title", "")),
            "desc": clean_label(p.get("desc", "")),
            "category": clean_label(p.get("category", "")),
            "icon": map_art(p.get("icon", "")),
            "episode": p.get("episode", ""),
        }
        rating = p.get("rating")
        if rating:
            try:
                entry["rating"] = "%s" % rating[0]["value"]
            except Exception:
                pass
        guide.setdefault(p.get("channel", ""), []).append(entry)
    for lst in guide.values():
        lst.sort(key=lambda e: e["start"])
    return _dumps(guide)


# ----------------------------------------------------------------- settings
def _label(value):
    if value is None:
        return ""
    try:
        return clean_label(addoninfo.localize(runtime.ADDON_ID, int(value)))
    except ValueError:
        return clean_label(value)


def settings_schema():
    """Settings of the add-on as JSON, following resources/settings.xml."""
    with runtime.lock:
        path = addoninfo.settings_xml_path(runtime.ADDON_ID)
        root = ET.parse(path).getroot()
        categories = []
        for cat in root.findall("category"):
            entries = []
            for node in cat.findall("setting"):
                stype = node.get("type", "")
                if node.get("visible") == "false":
                    continue
                entry = {"type": stype, "label": _label(node.get("label")), "id": node.get("id", "")}
                if stype == "lsep":
                    entries.append({"type": "section", "label": entry["label"]})
                    continue
                if stype == "action":
                    action = node.get("action", "")
                    m = re.match(r"RunPlugin\((.*)\)", action)
                    if not m:
                        continue  # Kodi-only actions (open another add-on settings...)
                    entry["action"] = m.group(1)
                elif stype in ("bool", "text", "number", "enum", "labelenum", "select", "slider"):
                    entry["value"] = "%s" % addoninfo.get_setting(runtime.ADDON_ID, entry["id"])
                    entry["default"] = node.get("default", "")
                    entry["hidden"] = node.get("option") == "hidden"
                    if stype == "enum":
                        if node.get("lvalues"):
                            entry["options"] = [{"value": "%d" % i, "label": _label(v)}
                                                for i, v in enumerate(node.get("lvalues").split("|"))]
                        else:
                            entry["options"] = [{"value": "%d" % i, "label": v}
                                                for i, v in enumerate((node.get("values") or "").split("|"))]
                    elif stype in ("labelenum", "select"):
                        values = (node.get("values") or "").split("|")
                        if node.get("lvalues"):
                            labels = [_label(v) for v in node.get("lvalues").split("|")]
                            values = labels
                        entry["options"] = [{"value": v, "label": v} for v in values if v != ""]
                        if entry["value"] == "" and entry["options"]:
                            entry["value"] = entry["options"][0]["value"]
                else:
                    continue  # file / folder pickers are not useful on a TV
                entries.append(entry)
            # drop empty sections
            cleaned = []
            for i, e in enumerate(entries):
                if e["type"] == "section" and (i + 1 >= len(entries) or entries[i + 1]["type"] == "section"):
                    continue
                cleaned.append(e)
            if cleaned:
                categories.append({"label": _label(cat.get("label")), "entries": cleaned})
        return _dumps(categories)


def get_setting(key):
    return "%s" % addoninfo.get_setting(runtime.ADDON_ID, key)


def set_setting(key, value):
    with runtime.lock:
        addoninfo.set_setting(runtime.ADDON_ID, key, value)
        return "ok"


def addon_version():
    return addoninfo.addon_info(runtime.ADDON_ID)["version"]


def clear_cache():
    with runtime.lock:
        try:
            urlquick = importlib.import_module("urlquick")
            urlquick.cache_cleanup(-1)
        except Exception:
            pass
        profile = runtime.addon_profile()
        for name in os.listdir(profile):
            if name.endswith(".xml") or (name.endswith(".json") and name != "settings.json"):
                try:
                    os.remove(os.path.join(profile, name))
                except OSError:
                    pass
        return "ok"


# ----------------------------------------------------------- account check
# Login function of each service in the add-on, and how to read its result.
_ACCOUNT_CHECKS = {
    "tf1plus": ("resources.lib.channels.fr.tf1plus", "get_token", {}, lambda r: bool(r) and bool(r[0])),
    "6play": ("resources.lib.channels.fr.6play", "get_login_token", {}, bool),
    "rmcplus": ("resources.lib.channels.fr.rmcplus", "get_login_token", {}, bool),
    "rtbf": ("resources.lib.channels.be.rtbf", "get_redbee_session_token", {}, lambda r: bool(r) and bool(r[0])),
    "rtlplaybe": ("resources.lib.channels.be.rtlplaybe", "get_login_token", {}, bool),
    "sfrtv": ("resources.lib.providers.sfrtv", "get_token", {"with_dialog": False}, bool),
}


def check_account(service):
    """Logs in to a service with the saved credentials, exactly like the add-on does.

    Returns JSON {status: ok | invalid | network | unsupported, message}.
    """
    check = _ACCOUNT_CHECKS.get(service)
    if check is None:
        return _dumps({"status": "unsupported", "message": ""})
    module_name, func_name, kwargs, is_ok = check
    with runtime.lock:
        session = runtime.new_session("check:" + service)
        runtime.silent_dialogs = True
        try:
            importlib.import_module("codequick")
            module = importlib.import_module(module_name)
            func = getattr(module, func_name)
            plugin = importlib.import_module("codequick").Script()
            result = func(plugin, **kwargs)
            ok = is_ok(result)
            return _dumps({"status": "ok" if ok else "invalid", "message": _session_message(session)})
        except Exception as e:  # wrong credentials usually end in a KeyError/HTTPError
            try:
                import requests
                if isinstance(e, (requests.exceptions.ConnectionError, requests.exceptions.Timeout)):
                    return _dumps({"status": "network", "message": runtime.redact(e)})
                if isinstance(e, requests.exceptions.HTTPError) and e.response is not None \
                        and e.response.status_code >= 500:
                    return _dumps({"status": "network", "message": "HTTP %d" % e.response.status_code})
            except ImportError:  # pragma: no cover
                pass
            return _dumps({"status": "invalid", "message": runtime.redact(
                _session_message(session) or "%s: %s" % (e.__class__.__name__, e))})
        finally:
            runtime.silent_dialogs = False


def _session_message(session):
    if session.notifications:
        n = session.notifications[-1]
        return clean_label(n.get("message") or n.get("heading") or "")
    return ""
