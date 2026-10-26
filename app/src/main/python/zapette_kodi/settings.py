# -*- coding: utf-8 -*-
"""The add-on's settings (resources/settings.xml) as JSON for the settings screen."""
from __future__ import unicode_literals

import importlib
import os
import re
import xml.etree.ElementTree as ET

from zapette_kodi import runtime, addoninfo
from zapette_kodi.convert import clean_label

_VALUE_TYPES = ("bool", "text", "number", "enum", "labelenum", "select", "slider")

# ---------------------------------------------------------------- what is shown
# The add-on is made for Kodi. Its settings that only make sense in Kodi (start
# with Kodi, Kodi's live TV, VPN, downloads, Kodi logs, inputstream…) do nothing
# in Zapette and are left out. Pages left empty disappear.
HIDDEN_IDS = {
    "auto_run", "iptv.enabled", "use_ia_hls_stream", "use_ytdl_stream", "device_l1",
    "tv_guide",            # Zapette has its own guide
    "active_subtitle",     # in Zapette's own page (Lecture → Sous-titres)
    "log_pop_up", "header_x-forwarded-for",
    "dl_quality", "dl_background", "dl_item_filename",
    "vpn.hide", "vpn.args", "vpn.sudo", "vpn.sudopsw",
    "schedulesdirect.login", "schedulesdirect.password", "schedulesdirect.lineup",
}
HIDDEN_ACTIONS = ("/iptvmanager/", "/vpn/", "/addon_utils/")   # parts of the action url

# Plain words, and a line of explanation shown under the focused setting.
# id or action url part -> (label, help, {option value: label})
WORDING = {
    "quality": ("Qualité vidéo",
                "Maximale : la meilleure image. Minimale : moins de données, pour une connexion lente. "
                "« Demander » propose le choix à chaque vidéo.",
                {"0": "Maximale", "1": "Par défaut", "2": "Demander à chaque fois", "3": "Minimale"}),
    "stream_bitrate_limit": ("Limiter le débit",
                             "Pour une connexion lente : plafonne la qualité des vidéos (en kbit/s).",
                             {"0": "Pas de limite"}),
    "6play.login": ("Identifiant M6+", "", {}),
    "6play.password": ("Mot de passe M6+", "", {}),
    "/favourites/delete_favourites/": (
        "Supprimer tous les favoris",
        "Pour ajouter un favori : maintenez OK sur une émission dans Replay ou Explorer. "
        "Ils se retrouvent dans Explorer → Favoris.", {}),
    "/menu_utils/restore_default_order/": (
        "Remettre les menus dans l'ordre d'origine",
        "Annule les déplacements faits dans les menus de Replay et Explorer (OK maintenu → Déplacer).", {}),
    "/menu_utils/unmask_all_hidden_items/": (
        "Réafficher tout ce qui a été masqué",
        "Annule tous les « Masquer » faits dans les menus de Replay et Explorer.", {}),
    "/menu_utils/unmask_items/": (
        "Choisir les éléments à réafficher", "", {}),
}


def _hidden(entry):
    if entry.get("id") in HIDDEN_IDS:
        return True
    return entry["type"] == "action" and any(part in entry.get("action", "") for part in HIDDEN_ACTIONS)


def _reword(entry):
    key = entry.get("id") or next((k for k in WORDING if k.startswith("/") and k in entry.get("action", "")), None)
    wording = WORDING.get(key)
    if wording:
        label, help_text, options = wording
        entry["label"] = label
        entry["help"] = help_text
        for option in entry.get("options", []):
            option["label"] = options.get(option["value"], option["label"])
    return entry


def _label(value):
    if value is None:
        return ""
    try:
        return clean_label(addoninfo.localize(runtime.ADDON_ID, int(value)))
    except ValueError:
        return clean_label(value)


def _options(node, setting_type):
    if setting_type == "enum":
        if node.get("lvalues"):
            return [{"value": "%d" % i, "label": _label(v)} for i, v in enumerate(node.get("lvalues").split("|"))]
        return [{"value": "%d" % i, "label": v} for i, v in enumerate((node.get("values") or "").split("|"))]
    values = (node.get("values") or "").split("|")
    if node.get("lvalues"):
        values = [_label(v) for v in node.get("lvalues").split("|")]
    return [{"value": v, "label": v} for v in values if v != ""]


def _entry(node):
    setting_type = node.get("type", "")
    label = _label(node.get("label"))
    if setting_type == "lsep":
        return {"type": "section", "label": label}
    if setting_type == "action":
        m = re.match(r"RunPlugin\((.*)\)", node.get("action", ""))
        # Kodi-only actions (open another add-on's settings...) are left out
        return {"type": "action", "label": label, "action": m.group(1)} if m else None
    if setting_type not in _VALUE_TYPES:
        return None   # file / folder pickers make no sense on a TV
    entry = {"type": setting_type, "label": label, "id": node.get("id", ""),
             "value": "%s" % addoninfo.get_setting(runtime.ADDON_ID, node.get("id", "")),
             "default": node.get("default", ""), "hidden": node.get("option") == "hidden"}
    if setting_type in ("enum", "labelenum", "select"):
        entry["options"] = _options(node, setting_type)
        if entry["value"] == "" and entry["options"] and setting_type != "enum":
            entry["value"] = entry["options"][0]["value"]
    return entry


def schema():
    with runtime.lock:
        root = ET.parse(addoninfo.settings_xml_path(runtime.ADDON_ID)).getroot()
        categories = []
        for category in root.findall("category"):
            entries = [_reword(e) for e in (_entry(n) for n in category.findall("setting") if n.get("visible") != "false")
                       if e and not _hidden(e)]
            # sections without any setting after them are dropped
            entries = [e for i, e in enumerate(entries)
                       if e["type"] != "section" or (i + 1 < len(entries) and entries[i + 1]["type"] != "section")]
            if entries:
                categories.append({"label": _label(category.get("label")), "entries": entries})
        return categories


def get(key):
    return "%s" % addoninfo.get_setting(runtime.ADDON_ID, key)


def put(key, value):
    with runtime.lock:
        addoninfo.set_setting(runtime.ADDON_ID, key, value)


def clear_cache():
    """Web cache of the add-on (urlquick) and downloaded TV guides."""
    with runtime.lock:
        try:
            importlib.import_module("urlquick").cache_cleanup(-1)
        except Exception:
            pass
        profile = runtime.addon_profile()
        for name in os.listdir(profile):
            if name.endswith(".xml") or (name.endswith(".json") and name != "settings.json"):
                try:
                    os.remove(os.path.join(profile, name))
                except OSError:
                    pass
