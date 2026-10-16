# -*- coding: utf-8 -*-
"""Emulation of Kodi's ``xbmcvfs`` module (local filesystem only)."""
from __future__ import unicode_literals

import io
import os
import shutil

from zapette_kodi import runtime


def translatePath(path):
    path = "%s" % path
    if not path.startswith("special://"):
        return path
    rest = path[len("special://"):]
    name, _, tail = rest.partition("/")
    roots = {
        "home": runtime.HOME_DIR,
        "xbmc": runtime.HOME_DIR,
        "xbmcbin": runtime.HOME_DIR,
        "masterprofile": runtime.userdata_dir(),
        "profile": runtime.userdata_dir(),
        "userdata": runtime.userdata_dir(),
        "database": os.path.join(runtime.userdata_dir(), "Database"),
        "thumbnails": os.path.join(runtime.userdata_dir(), "Thumbnails"),
        "temp": runtime.temp_dir(),
        "logpath": runtime.temp_dir(),
        "subtitles": runtime.temp_dir(),
        "recordings": runtime.temp_dir(),
    }
    base = roots.get(name, runtime.HOME_DIR)
    return os.path.join(base, *[p for p in tail.split("/") if p]) + ("/" if path.endswith("/") and tail else "")


def makeLegalFilename(path):
    return path


def validatePath(path):
    return path


def exists(path):
    return os.path.exists(translatePath(path))


def delete(path):
    try:
        os.remove(translatePath(path))
        return True
    except OSError:
        return False


def mkdir(path):
    try:
        os.mkdir(translatePath(path))
        return True
    except OSError:
        return False


def mkdirs(path):
    try:
        os.makedirs(translatePath(path))
        return True
    except OSError:
        return os.path.isdir(translatePath(path))


def rmdir(path, force=False):
    try:
        if force:
            shutil.rmtree(translatePath(path))
        else:
            os.rmdir(translatePath(path))
        return True
    except OSError:
        return False


def copy(source, destination):
    try:
        shutil.copyfile(translatePath(source), translatePath(destination))
        return True
    except (IOError, OSError):
        return False


def rename(source, destination):
    try:
        os.rename(translatePath(source), translatePath(destination))
        return True
    except OSError:
        return False


def listdir(path):
    path = translatePath(path)
    dirs, files = [], []
    try:
        for name in os.listdir(path):
            (dirs if os.path.isdir(os.path.join(path, name)) else files).append(name)
    except OSError:
        pass
    return dirs, files


class File(object):
    def __init__(self, path, mode="r"):
        path = translatePath(path)
        if "w" in mode or "a" in mode:
            folder = os.path.dirname(path)
            if folder and not os.path.isdir(folder):
                os.makedirs(folder)
        try:
            self._f = io.open(path, mode.replace("t", "") + ("b" if "b" not in mode else ""))
        except (IOError, OSError):
            self._f = None
        self._path = path

    def read(self, num_bytes=-1):
        if not self._f:
            return ""
        data = self._f.read(num_bytes)
        return data.decode("utf-8", "replace")

    def readBytes(self, num_bytes=-1):
        if not self._f:
            return bytearray()
        return bytearray(self._f.read(num_bytes))

    def write(self, data):
        if not self._f:
            return False
        if not isinstance(data, (bytes, bytearray)):
            data = ("%s" % data).encode("utf-8")
        self._f.write(data)
        return True

    def size(self):
        try:
            return os.path.getsize(self._path)
        except OSError:
            return 0

    def seek(self, offset, whence=0):
        return self._f.seek(offset, whence) if self._f else 0

    def tell(self):
        return self._f.tell() if self._f else 0

    def close(self):
        if self._f:
            self._f.close()
            self._f = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


class Stat(object):
    def __init__(self, path):
        try:
            self._st = os.stat(translatePath(path))
        except OSError:
            self._st = None

    def st_size(self):
        return self._st.st_size if self._st else 0

    def st_mtime(self):
        return int(self._st.st_mtime) if self._st else 0

    def st_ctime(self):
        return int(self._st.st_ctime) if self._st else 0

    def st_atime(self):
        return int(self._st.st_atime) if self._st else 0

    def st_mode(self):
        return self._st.st_mode if self._st else 0
