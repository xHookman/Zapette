# -*- coding: utf-8 -*-
"""Checks a service's login with the saved credentials, using the add-on's own login code."""
from __future__ import unicode_literals

import importlib

from zapette_kodi import runtime
from zapette_kodi.convert import clean_label
from zapette_kodi.plugin import is_network_error

# service -> (module, login function, extra arguments, "did it work?" test on the result)
_LOGINS = {
    "tf1plus": ("resources.lib.channels.fr.tf1plus", "get_token", {}, lambda r: bool(r) and bool(r[0])),
    "6play": ("resources.lib.channels.fr.6play", "get_login_token", {}, bool),
    "rmcplus": ("resources.lib.channels.fr.rmcplus", "get_login_token", {}, bool),
    "rtbf": ("resources.lib.channels.be.rtbf", "get_redbee_session_token", {}, lambda r: bool(r) and bool(r[0])),
    "rtlplaybe": ("resources.lib.channels.be.rtlplaybe", "get_login_token", {}, bool),
    "sfrtv": ("resources.lib.providers.sfrtv", "get_token", {"with_dialog": False}, bool),
}


def check(service):
    """{status: ok | invalid | network | unsupported, message}"""
    login = _LOGINS.get(service)
    if login is None:
        return {"status": "unsupported", "message": ""}
    module_name, function_name, kwargs, succeeded = login
    with runtime.lock:
        session = runtime.new_session("check:" + service)
        runtime.silent_dialogs = True       # the app reports the result itself
        try:
            codequick = importlib.import_module("codequick")
            login_function = getattr(importlib.import_module(module_name), function_name)
            ok = succeeded(login_function(codequick.Script(), **kwargs))
            return {"status": "ok" if ok else "invalid", "message": _last_message(session)}
        except Exception as e:   # wrong credentials usually end in a KeyError / HTTPError
            status_code = getattr(getattr(e, "response", None), "status_code", 0) or 0
            if is_network_error(e) or status_code >= 500:
                return {"status": "network", "message": runtime.redact(e)}
            return {"status": "invalid",
                    "message": runtime.redact(_last_message(session) or "%s: %s" % (e.__class__.__name__, e))}
        finally:
            runtime.silent_dialogs = False


def _last_message(session):
    if not session.notifications:
        return ""
    n = session.notifications[-1]
    return clean_label(n.get("message") or n.get("heading") or "")
