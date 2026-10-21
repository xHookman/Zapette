# -*- coding: utf-8 -*-
"""
Fixes applied by Zapette on top of Catch-up TV & More, without modifying it.

A module listed in PATCHES is imported normally, then its patch function runs.
The add-on can be updated from GitHub at any time: the fixes stay active.
A patched callback replaces the original in codequick's routes (same path).
"""
from __future__ import unicode_literals

import importlib.abc
import re
import sys


# --------------------------------------------------------------- LMTV Sarthe
LMTV_CHANNEL = "UCidJ6dBJ7oEfdAVzqaL2nSg"


def _patch_lmtv(module):
    """lmtv.fr now embeds the channel's live (embed/live_stream?channel=...),
    which the add-on read as a video id named "live_stream"."""
    from codequick import Resolver
    import urlquick
    from resources.lib import resolver_proxy, web_utils

    def get_live_url(plugin, item_id, **kwargs):
        channel, video = LMTV_CHANNEL, None
        try:
            html = urlquick.get("https://lmtv.fr/", headers={"User-Agent": web_utils.get_random_ua()},
                                max_age=-1).text
            m = re.search(r"youtube(?:-nocookie)?\.com/embed/live_stream\?channel=([\w-]+)", html)
            if m:
                channel = m.group(1)
            else:
                m = re.search(r"youtube(?:-nocookie)?\.com/embed/([\w-]{11})", html)
                if m:
                    video = m.group(1)
        except Exception:
            pass
        if video:
            return resolver_proxy.get_stream_youtube(plugin, video)
        return resolver_proxy.get_stream_default(plugin, "https://www.youtube.com/channel/%s/live" % channel)

    get_live_url.__module__ = module.__name__
    module.get_live_url = Resolver.register(get_live_url)


# -------------------------------------------------------- France TV channels
# Live ids the add-on already knows (its own fallback list), used last.
FRANCETV_KNOWN = {
    "france-2": "006194ea-117d-4bcf-94a9-153d999c59ae",
    "france-3": "29bdf749-7082-4426-a4f3-595cc436aa0d",
    "france-4": "9a6a7670-dde9-4264-adbc-55b89558594b",
    "france-5": "45007886-f3ff-4b3e-9706-1ef1014c5a60",
    "franceinfo": "35be22fb-1569-43ff-857c-99bf81defa2e",
}

# Other names a channel may have on the France TV side.
FRANCETV_NAMES = {
    "spectacles-et-culture": ("spectacles-et-culture", "culturebox"),
}


def _patch_francetv(module):
    """Culturebox ("spectacles-et-culture") is not in the add-on's fallback list,
    and its live is no longer found under that name: "Unable to resolve URL".
    The live id is now looked up in the channel page, then in the list of all
    France TV lives; the add-on's known ids stay as a last resort."""
    import json
    from codequick import Resolver
    import urlquick
    from resources.lib import resolver_proxy

    def api(path):
        response = urlquick.get(module.URL_API_MOBILE(path), params={"platform": "apps"}, max_age=-1)
        return json.loads(response.text)

    def find_live_id(item_id):
        names = FRANCETV_NAMES.get(item_id, (item_id,))
        # 1. The channel page: its "live" collections
        try:
            lives = [item.get("channel") or {}
                     for collection in api("/apps/channels/%s" % item_id).get("collections") or []
                     if collection.get("type") == "live"
                     for item in collection.get("items") or []]
            lives = [c for c in lives if c.get("si_id")]
            for channel in lives:
                if channel.get("channel_path") in names:
                    return channel["si_id"]
            if len(lives) == 1:
                return lives[0]["si_id"]
        except Exception:
            pass
        # 2. All France TV lives, by name
        try:
            for item in api("/generic/directs").get("items") or []:
                channel = item.get("channel") or {}
                label = (channel.get("label") or "").lower().replace(" ", "")
                if channel.get("si_id") and (channel.get("channel_path") in names or
                                             any(n.replace("-", "") == label for n in names)):
                    return channel["si_id"]
        except Exception:
            pass
        return None

    def get_live_url(plugin, item_id, **kwargs):
        live_id = find_live_id(item_id) or FRANCETV_KNOWN.get(item_id)
        if not live_id:
            plugin.notify("ERROR", plugin.localize(30716))
            return False
        return resolver_proxy.get_francetv_live_stream(plugin, live_id)

    get_live_url.__module__ = module.__name__
    module.get_live_url = Resolver.register(get_live_url)


PATCHES = {
    "resources.lib.channels.fr.lmtv": _patch_lmtv,
    "resources.lib.channels.fr.francetv": _patch_francetv,
}


class _PatchFinder(importlib.abc.MetaPathFinder):
    def find_spec(self, name, path, target=None):
        patch = PATCHES.get(name)
        if patch is None:
            return None
        for finder in sys.meta_path:
            if finder is self or not hasattr(finder, "find_spec"):
                continue
            spec = finder.find_spec(name, path, target)
            if spec is None or spec.loader is None:
                continue
            original = spec.loader.exec_module

            def exec_module(module, _original=original, _patch=patch):
                _original(module)
                try:
                    _patch(module)
                except Exception as e:  # a broken fix must never break the add-on
                    from zapette_kodi import runtime
                    runtime.call_host("log", 3, "patch %s failed: %s" % (name, e))

            spec.loader.exec_module = exec_module
            return spec
        return None


def install():
    if not any(isinstance(f, _PatchFinder) for f in sys.meta_path):
        sys.meta_path.insert(0, _PatchFinder())
