# -*- coding: utf-8 -*-
"""
inputstreamhelper replacement: on Zapette, adaptive streams and Widevine are
handled natively by Media3/ExoPlayer, so every check succeeds.
"""
__version__ = "0.7.0"


class Helper(object):
    def __init__(self, protocol, drm=None):
        self.protocol = protocol
        self.drm = drm
        self.inputstream_addon = "inputstream.adaptive"

    def check_inputstream(self):
        return True

    def install_widevine(self):
        return True

    def remove_widevine(self):
        return True

    def info_dialog(self):
        return True

    def _has_inputstream(self):
        return True

    def _inputstream_version(self):
        return "21.4.0"
