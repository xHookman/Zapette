# -*- coding: utf-8 -*-
"""
Desktop smoke test of the Zapette Python engine against the real add-on.

    python tools/test_bridge.py <kodi_home_with_addons_folder>

The home folder must contain addons/plugin.video.catchuptvandmore and
addons/script.module.codequick (the same layout the Android app creates).
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "app", "src", "main", "python"))

from zapette_kodi import bridge  # noqa: E402

home = sys.argv[1]
print(bridge.init(home, "fr_fr", "Europe/Paris"))


def show(title, raw, limit=8):
    data = json.loads(raw)
    print("\n=== %s: kind=%s category=%r" % (title, data.get("kind"), data.get("category")))
    if data.get("error"):
        print("   ERROR:", data["error"])
    for item in data.get("items", [])[:limit]:
        print("   - [%s] %-30s art=%s params=%s" % (item["kind"], item["label"][:30],
                                                   sorted(item["art"]), item["params"]))
    print("   (%d items, %d ms)" % (len(data.get("items", [])), data.get("elapsed_ms", 0)))
    return data


root = show("root", bridge.invoke("plugin://plugin.video.catchuptvandmore/"))
live = [i for i in root["items"] if i["params"].get("item_id") == "live_tv"][0]
show("live_tv", bridge.invoke(live["url"]))

fr_live = show("fr_live (generic)", bridge.invoke(bridge.menu_url("fr_live")), 5)
fr_replay = show("fr_replay", bridge.invoke(bridge.menu_url("fr_replay")), 5)

channels = json.loads(bridge.live_channels("fr_live"))
print("\n=== live_channels fr: %d" % len(channels))
for c in channels[:30]:
    print("   %3d %-22s %-28s %s" % (c["number"], c["label"], c["xmltv_id"], c["logo"][-40:]))
print("   play url sample:", channels[0]["url"][:120])

countries = json.loads(bridge.live_countries())
print("\n=== countries:", [c["id"] for c in countries])

schema = json.loads(bridge.settings_schema())
print("\n=== settings: %d categories" % len(schema))
for cat in schema:
    print("  #", cat["label"], "->", len(cat["entries"]), "entries:",
          ", ".join("%s(%s)" % (e.get("id") or e["label"][:18], e["type"]) for e in cat["entries"][:6]))

# Try the EPG (needs raw.githubusercontent.com)
guide = json.loads(bridge.epg("fr_live", 0))
print("\n=== EPG channels:", len(guide))
tf1 = guide.get("C192.api.telerama.fr", [])
print("   TF1 programmes:", len(tf1), tf1[0] if tf1 else None)

# Resolving a live channel (network to the channel is usually blocked in CI)
res = json.loads(bridge.invoke(channels[1]["url"], json.dumps({"label": channels[1]["label"]})))
print("\n=== resolve %s -> kind=%s error=%s stream=%s" % (channels[1]["label"], res.get("kind"),
                                                          res.get("error"), res.get("stream")))
