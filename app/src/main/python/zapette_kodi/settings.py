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
# The add-on is made for Kodi. What only works inside Kodi is left out: start with
# Kodi, Kodi's live TV, downloads, sending Kodi's logs, and the VPN (it drives the
# OpenVPN program of a computer, which an Android TV does not have). Technical
# switches of the player (inputstream, yt-dlp, X-Forwarded-For) keep their default.
# Pages left empty disappear. What stays is worded without "Kodi".
HIDDEN_IDS = {
    "auto_run", "iptv.enabled", "use_ia_hls_stream", "use_ytdl_stream",
    "tv_guide",            # Zapette has its own guide
    "active_subtitle",     # in Zapette's own page (Lecture → Sous-titres)
    "log_pop_up", "header_x-forwarded-for",
    "dl_quality", "dl_background", "dl_item_filename",
    "vpn.hide", "vpn.args", "vpn.sudo", "vpn.sudopsw",
    "schedulesdirect.login", "schedulesdirect.password", "schedulesdirect.lineup",
}

# Page names
CATEGORY_WORDING = {"Qualité et Contenu": "Qualité et chaînes"}
HIDDEN_ACTIONS = ("/iptvmanager/", "/vpn/", "/addon_utils/")   # parts of the action url

# Plain words, and a line of explanation shown under the focused setting.
# id or action url part -> (label, help, {option value: label})
# For the settings in ONLY_LISTED_OPTIONS, the other choices are left out.
WORDING = {
    "quality": ("Qualité de l'image",
                "La meilleure image, ou une image plus légère pour une connexion lente. "
                "« Demander » propose le choix à chaque vidéo.",
                {"0": "La meilleure", "1": "Automatique", "2": "Demander à chaque fois", "3": "La plus légère"}),
    "stream_bitrate_limit": ("Connexion lente",
                             "Si les vidéos s'arrêtent souvent pour charger : limitez la qualité.",
                             {"0": "Pas de limite", "750": "Image très légère", "1500": "Image légère",
                              "4000": "Qualité normale", "8000": "Haute définition"}),
    "device_l1": ("Haute définition des chaînes protégées",
                  "Pour les box certifiées Google (Chromecast, Shield, Freebox…) : certaines chaînes, "
                  "comme TF1+ ou M6+, passent alors en HD. Si la vidéo ne se lance plus, désactivez.", {}),
    "uk.channel4.programmes.sort.by": ("Channel 4 : ordre des émissions", "",
                                       {"popular": "Les plus regardées", "az": "De A à Z",
                                        "recently-added": "Ajoutées récemment"}),
    "qvc.language": ("QVC : pays", "", {"JP": "Japon", "DE": "Allemagne", "IT": "Italie",
                                         "UK": "Royaume-Uni", "US": "États-Unis"}),
    "nhkworld.language": ("NHK World : version", "", {"Outside Japan": "Internationale", "In Japan": "Japonaise"}),
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


ONLY_LISTED_OPTIONS = {"stream_bitrate_limit"}

# Language choices of the international channels ("FR", "de"…) in words
LANGUAGES = {"fr": "Français", "en": "Anglais", "de": "Allemand", "es": "Espagnol", "it": "Italien",
             "pl": "Polonais", "ar": "Arabe", "ru": "Russe", "pt": "Portugais", "bg": "Bulgare",
             "fa": "Persan", "el": "Grec", "hu": "Hongrois", "tr": "Turc", "uk": "Ukrainien",
             "ro": "Roumain", "es-la": "Espagnol (Amérique latine)"}


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
        if key in ONLY_LISTED_OPTIONS:
            entry["options"] = [o for o in entry.get("options", []) if o["value"] in options]
            if entry["value"] not in options:
                entry["value"] = "0"
        for option in entry.get("options", []):
            option["label"] = options.get(option["value"], option["label"])
    if entry.get("id", "").endswith(".language") and entry.get("id") not in WORDING:
        for option in entry.get("options", []):
            option["label"] = LANGUAGES.get(option["label"].lower(), option["label"])
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
                label = _label(category.get("label"))
                categories.append({"label": CATEGORY_WORDING.get(label, label), "entries": entries})
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
