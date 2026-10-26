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


PATCHES = {
    "resources.lib.channels.fr.lmtv": _patch_lmtv,
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
