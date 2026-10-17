# -*- coding: utf-8 -*-
"""Live TV: countries, channel list with TV numbering, and the TV guide (XMLTV)."""
from __future__ import unicode_literals

import calendar
import datetime
import importlib

from zapette_kodi import runtime, addoninfo
from zapette_kodi.convert import clean_label, map_art

_IMAGES = "resource://resource.images.catchuptvandmore/"


def menu_url(menu_id):
    """plugin:// url of one of the add-on's skeleton menus (fr_replay, root...)."""
    with runtime.lock:
        support = importlib.import_module("codequick.support")
        route = importlib.import_module("codequick.route")
        return support.build_path(route.Route.ref("/resources/lib/main:generic_menu"), query={"item_id": menu_id})


def countries():
    with runtime.lock:
        skeleton = importlib.import_module("resources.lib.skeletons.live_tv").menu
        out = []
        for item_id, infos in sorted(skeleton.items(), key=lambda x: x[1].get("order", 999)):
            if not infos.get("enabled", True):
                continue
            label = infos.get("label", item_id)
            if isinstance(label, int):
                label = addoninfo.localize(runtime.ADDON_ID, label)
            thumb = infos.get("thumb")
            out.append({"id": item_id, "label": clean_label(label),
                        "thumb": map_art(_IMAGES + thumb) if isinstance(thumb, str) else ""})
        return out


def channels(country_id):
    """Channels of a *_live skeleton, numbered like on TV: TNT channels keep their
    official number (1 TF1, 2 France 2...), the others follow in the add-on's order."""
    with runtime.lock:
        skeleton = importlib.import_module("resources.lib.skeletons." + country_id).menu
        support = importlib.import_module("codequick.support")
        resolver = importlib.import_module("codequick.resolver")
        addon_utils = importlib.import_module("resources.lib.addon_utils")
        menu_utils = importlib.import_module("resources.lib.menu_utils")

        entries = []
        for item_id, infos in skeleton.items():
            if not infos.get("enabled", True) or "resolver" not in infos:
                continue
            if menu_utils.is_item_hidden(item_id, country_id):   # "Masquer" in the add-on
                continue
            xmltv_id = infos.get("xmltv_id")
            languages = infos.get("available_languages")
            if isinstance(languages, dict) and languages:
                language = addoninfo.get_setting(runtime.ADDON_ID, item_id + ".language") or list(languages)[0]
                xmltv_id = (languages.get(language) or {}).get("xmltv_id", xmltv_id)
            query = {"item_id": item_id}
            if xmltv_id:
                query["xmltv_id"] = xmltv_id
            thumb, fanart = infos.get("thumb"), infos.get("fanart")
            entries.append({
                "id": item_id,
                "label": clean_label(addon_utils.get_item_label(item_id, infos)),
                "url": support.build_path(resolver.Resolver.ref(infos["resolver"]), query=query),
                "logo": map_art(addon_utils.get_item_media_path(thumb)) if thumb else "",
                "fanart": map_art(addon_utils.get_item_media_path(fanart)) if fanart else "",
                "xmltv_id": xmltv_id or "",
                "group": infos.get("m3u_group", ""),
                "_tnt": infos.get("m3u_order") if infos.get("m3u_group") == "TNT" else None,
                "_order": infos.get("order", 999),
            })

        used = set()
        for e in entries:
            if isinstance(e["_tnt"], int):
                e["number"] = e["_tnt"]
                used.add(e["_tnt"])
        number = max(used) + 1 if used else 1
        for e in sorted((e for e in entries if "number" not in e), key=lambda e: (e["_order"], e["label"])):
            while number in used:
                number += 1
            e["number"] = number
            used.add(number)
        for e in entries:
            del e["_tnt"], e["_order"]
        return sorted(entries, key=lambda e: e["number"])


def guide(country_id, day_delta):
    """Programmes of one day: {xmltv channel id: [programme...]} with UTC epoch times."""
    xmltv = importlib.import_module("resources.lib.xmltv")
    result = {}
    for p in xmltv.grab_programmes(country_id, int(day_delta)):
        start, stop = _epoch(p.get("start")), _epoch(p.get("stop"))
        if not start or not stop:
            continue
        programme = {
            "start": start, "stop": stop,
            "title": clean_label(p.get("title", "")),
            "subtitle": clean_label(p.get("sub-title", "")),
            "desc": clean_label(p.get("desc", "")),
            "category": clean_label(p.get("category", "")),
            "icon": map_art(p.get("icon", "")),
            "episode": p.get("episode", ""),
        }
        try:
            programme["rating"] = "%s" % p["rating"][0]["value"]
        except (KeyError, IndexError, TypeError):
            pass
        result.setdefault(p.get("channel", ""), []).append(programme)
    for programmes in result.values():
        programmes.sort(key=lambda e: e["start"])
    return result


def _epoch(iso_utc):
    try:
        return calendar.timegm(datetime.datetime.strptime(iso_utc, "%Y-%m-%dT%H:%M:%S").timetuple())
    except (TypeError, ValueError):
        return 0
