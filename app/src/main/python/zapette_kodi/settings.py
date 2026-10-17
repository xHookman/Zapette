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
            entries = [e for e in (_entry(n) for n in category.findall("setting") if n.get("visible") != "false") if e]
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
