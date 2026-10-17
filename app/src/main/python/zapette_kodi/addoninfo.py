# -*- coding: utf-8 -*-
"""
Add-on metadata, translations (strings.po) and settings storage.

Settings are stored as JSON in the add-on profile folder. Default values are
read from ``resources/settings.xml`` like Kodi does.
"""
from __future__ import unicode_literals

import io
import json
import os
import re
import xml.etree.ElementTree as ET

from zapette_kodi import runtime

_po_cache = {}
_defaults_cache = {}
_settings_cache = {}
_info_cache = {}

# Kodi core strings used by codequick / the add-on
KODI_STRINGS = {
    "fr_fr": {
        136: "Listes de lecture", 137: "Rechercher", 1210: "Supprimer",
        16017: "Saisir le terme de recherche", 25006: "Choisir l'élément à lire",
        33077: "Aucune donnée", 33078: "Page suivante", 222: "Annuler",
        186: "OK", 106: "Non", 107: "Oui", 117: "Supprimer", 118: "Renommer",
        13208: "Lecture", 20323: "Ajouter aux favoris", 10008: "Fichiers",
        19033: "Information", 257: "Erreur", 20186: "Veuillez patienter",
    },
    "en_gb": {
        136: "Playlists", 137: "Search", 1210: "Remove",
        16017: "Enter search string", 25006: "Select playback item",
        33077: "No data", 33078: "Next page", 222: "Cancel", 186: "OK",
        106: "No", 107: "Yes", 117: "Delete", 118: "Rename", 13208: "Play",
        20323: "Add to favourites", 10008: "Files", 19033: "Information",
        257: "Error", 20186: "Please wait",
    },
}


# ---------------------------------------------------------------- strings.po
_msgctxt_re = re.compile(r'^msgctxt\s+"#(\d+)"')
_msgid_re = re.compile(r'^msgid\s+"(.*)"')
_msgstr_re = re.compile(r'^msgstr\s+"(.*)"')
_cont_re = re.compile(r'^"(.*)"')


def _unescape(s):
    return s.replace('\\"', '"').replace("\\n", "\n").replace("\\\\", "\\")


def _parse_po(path):
    strings = {}
    if not os.path.exists(path):
        return strings
    ident, msgid, msgstr, field = None, "", "", None
    with io.open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            m = _msgctxt_re.match(line)
            if m:
                if ident is not None:
                    strings[ident] = _unescape(msgstr or msgid)
                ident, msgid, msgstr, field = int(m.group(1)), "", "", None
                continue
            m = _msgid_re.match(line)
            if m:
                msgid, field = m.group(1), "id"
                continue
            m = _msgstr_re.match(line)
            if m:
                msgstr, field = m.group(1), "str"
                continue
            m = _cont_re.match(line)
            if m and field == "id":
                msgid += m.group(1)
            elif m and field == "str":
                msgstr += m.group(1)
    if ident is not None:
        strings[ident] = _unescape(msgstr or msgid)
    return strings


def strings_for(addon_id):
    if addon_id in _po_cache:
        return _po_cache[addon_id]
    base = os.path.join(runtime.addon_path(addon_id), "resources", "language")
    merged = _parse_po(os.path.join(base, "resource.language.en_gb", "strings.po"))
    if runtime.LANGUAGE != "en_gb":
        local = _parse_po(os.path.join(base, "resource.language." + runtime.LANGUAGE, "strings.po"))
        merged.update({k: v for k, v in local.items() if v})
    _po_cache[addon_id] = merged
    return merged


def localize(addon_id, string_id):
    try:
        string_id = int(string_id)
    except (TypeError, ValueError):
        return ""
    if addon_id:
        value = strings_for(addon_id).get(string_id)
        if value:
            return value
    table = KODI_STRINGS.get(runtime.LANGUAGE, KODI_STRINGS["en_gb"])
    return table.get(string_id, KODI_STRINGS["en_gb"].get(string_id, ""))


# ------------------------------------------------------------------ addon.xml
def addon_info(addon_id):
    if addon_id in _info_cache:
        return _info_cache[addon_id]
    info = {"id": addon_id, "name": addon_id, "version": "0.0.0", "author": "",
            "summary": "", "description": "", "disclaimer": "", "changelog": "",
            "stars": "0", "type": "xbmc.python.pluginsource"}
    path = runtime.addon_path(addon_id)
    xml_path = os.path.join(path, "addon.xml")
    if os.path.exists(xml_path):
        try:
            root = ET.parse(xml_path).getroot()
            info["name"] = root.get("name", addon_id)
            info["version"] = root.get("version", "0.0.0")
            info["author"] = root.get("provider-name", "")
            for ext in root.findall("extension"):
                if ext.get("point") == "xbmc.addon.metadata":
                    lang = "fr_FR" if runtime.LANGUAGE == "fr_fr" else "en_GB"
                    for key in ("summary", "description", "disclaimer"):
                        nodes = ext.findall(key)
                        chosen = [n for n in nodes if n.get("lang") == lang] or nodes
                        if chosen:
                            info[key] = chosen[0].text or ""
                    news = ext.find("news")
                    if news is not None:
                        info["changelog"] = news.text or ""
        except ET.ParseError:
            pass
    info["path"] = path
    info["profile"] = runtime.addon_profile(addon_id)
    for art in ("icon.png", "fanart.jpg"):
        key = art.split(".")[0]
        info[key] = os.path.join(path, art) if os.path.exists(os.path.join(path, art)) else ""
    _info_cache[addon_id] = info
    return info


# ------------------------------------------------------------------- settings
def settings_xml_path(addon_id):
    return os.path.join(runtime.addon_path(addon_id), "resources", "settings.xml")


def setting_defaults(addon_id):
    if addon_id in _defaults_cache:
        return _defaults_cache[addon_id]
    defaults = {}
    path = settings_xml_path(addon_id)
    if os.path.exists(path):
        try:
            for node in ET.parse(path).getroot().iter("setting"):
                sid = node.get("id")
                if sid:
                    defaults[sid] = node.get("default", "")
        except ET.ParseError:
            pass
    _defaults_cache[addon_id] = defaults
    return defaults


def _settings_file(addon_id):
    return os.path.join(runtime.addon_profile(addon_id), "settings.json")


def load_settings(addon_id):
    if addon_id not in _settings_cache:
        values = {}
        try:
            with io.open(_settings_file(addon_id), "r", encoding="utf-8") as f:
                values = json.load(f)
        except (IOError, OSError, ValueError):
            pass
        _settings_cache[addon_id] = values
    return _settings_cache[addon_id]


def get_setting(addon_id, key):
    values = load_settings(addon_id)
    if key in values:
        return values[key]
    return setting_defaults(addon_id).get(key, "")


def set_setting(addon_id, key, value):
    if isinstance(value, bool):
        value = "true" if value else "false"
    values = load_settings(addon_id)
    values[key] = "%s" % value
    folder = runtime.addon_profile(addon_id)
    if not os.path.isdir(folder):
        os.makedirs(folder)
    tmp = _settings_file(addon_id) + ".tmp"
    with io.open(tmp, "w", encoding="utf-8") as f:
        f.write(json.dumps(values, ensure_ascii=False, indent=1))
    os.replace(tmp, _settings_file(addon_id))


def reset_caches():
    """Called after the add-on has been updated."""
    _po_cache.clear()
    _defaults_cache.clear()
    _info_cache.clear()
    _settings_cache.clear()
