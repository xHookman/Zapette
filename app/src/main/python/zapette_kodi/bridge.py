# -*- coding: utf-8 -*-
"""
Entry points called from Kotlin (fr.zapette.tv.engine.AddonEngine) through Chaquopy.

Every function takes and returns strings (JSON) to keep the Java <-> Python
boundary trivial. The work is done in:
    plugin.py    run the add-on like Kodi (invoke a plugin:// url)
    convert.py   Kodi ListItems -> JSON
    live.py      channels, TV numbering, TV guide
    settings.py  the add-on's settings
    accounts.py  login checks
"""
from __future__ import unicode_literals

import json
import sys

from zapette_kodi import runtime, addoninfo, plugin, live, settings, accounts
from zapette_kodi.convert import to_json


def init(home_dir, language="fr_fr", timezone="Europe/Paris", host=None):
    plugin.init(home_dir, language, timezone, host)
    return to_json({"addon_version": addon_version(), "python": sys.version.split()[0]})


def addon_version():
    return addoninfo.addon_info(runtime.ADDON_ID)["version"]


def reload_addon():
    plugin.reload_addon()
    return addon_version()


def invoke(url, selected_json=""):
    try:
        selected = json.loads(selected_json) if selected_json else {}
    except ValueError:
        selected = {}
    return to_json(plugin.invoke(url, selected))


def menu_url(menu_id):
    return live.menu_url(menu_id)


def live_countries():
    return to_json(live.countries())


def live_channels(country_id="fr_live"):
    return to_json(live.channels(country_id))


def epg(country_id="fr_live", day_delta=0):
    return to_json(live.guide(country_id, day_delta))


def settings_schema():
    return to_json(settings.schema())


def get_setting(key):
    return settings.get(key)


def set_setting(key, value):
    settings.put(key, value)
    return "ok"


def clear_cache():
    settings.clear_cache()
    return "ok"


def check_account(service):
    return to_json(accounts.check(service))
