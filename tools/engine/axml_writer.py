"""Minimal Android binary XML (AXML) ENCODER.
Encodes a subset sufficient for LSPosed module manifests:
manifest / uses-sdk / application / activity / intent-filter / action /
category / meta-data, with android-namespaced attributes.

Framework attr resource IDs verified against the original binary
manifest of com.hihonor.appmarket 16.1.6.302.
"""
import struct

NS_PREFIX = "android"
NS_URI = "http://schemas.android.com/apk/res/android"

RES_IDS = {
    "theme": 0x01010000,
    "label": 0x01010001,
    "icon": 0x01010002,
    "name": 0x01010003,
    "permission": 0x01010006,
    "enabled": 0x0101000E,
    "debuggable": 0x0101000F,
    "exported": 0x01010010,
    "process": 0x01010011,
    "taskAffinity": 0x01010012,
    "authorities": 0x01010018,
    "grantUriPermissions": 0x0101001B,
    "priority": 0x0101001C,
    "launchMode": 0x0101001D,
    "screenOrientation": 0x0101001E,
    "configChanges": 0x0101001F,
    "value": 0x01010024,
    "mimeType": 0x01010026,
    "scheme": 0x01010027,
    "host": 0x01010028,
    "minSdkVersion": 0x0101020C,
    "versionCode": 0x0101021B,
    "versionName": 0x0101021C,
    "targetSdkVersion": 0x01010270,
    "allowBackup": 0x01010280,
    "hardwareAccelerated": 0x010102D3,
    "largeHeap": 0x0101035A,
    "supportsRtl": 0x010103AF,
    "extractNativeLibs": 0x010104EA,
    "usesCleartextTraffic": 0x010104EC,
    "networkSecurityConfig": 0x01010527,
}

TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12


class _Pool:
    def __init__(self):
        self.strings = []
        self.index = {}

    def add(self, s):
        if s not in self.index:
            self.index[s] = len(self.strings)
            self.strings.append(s)
        return self.index[s]


def _enc_len(v):
    if v < 128:
        return bytes([v])
    return bytes([0x80 | ((v >> 8) & 0xFF), v & 0xFF])


def _encode_utf8_string(s):
    b = s.encode("utf-8")
    u16_units = len(s.encode("utf-16-le")) // 2
    return _enc_len(u16_units) + _enc_len(len(b)) + b + b"\x00"


def _encode_utf16_string(s):
    b = s.encode("utf-16-le")
    return struct.pack("<H", len(b) // 2) + b + b"\x00\x00"


def _string_pool_chunk(pool, utf16=False):
    data = b""
    offsets = []
    for s in pool.strings:
        offsets.append(len(data))
        data += _encode_utf16_string(s) if utf16 else _encode_utf8_string(s)
    while len(data) % 4:
        data += b"\x00"
    header_size = 28
    strings_start = header_size + 4 * len(pool.strings)
    chunk_size = strings_start + len(data)
    flags = 0x00000000 if utf16 else 0x00000100
    out = struct.pack("<HHIIIIII", 0x0001, header_size, chunk_size,
                      len(pool.strings), 0, flags, strings_start, 0)
    out += b"".join(struct.pack("<I", o) for o in offsets)
    out += data
    return out


def _res_map_chunk(pool, attr_ids):
    if not attr_ids:
        return b""
    max_i = max(pool.index[n] for n in attr_ids)
    ids = [0xFFFFFFFF] * (max_i + 1)
    for n, rid in attr_ids.items():
        ids[pool.index[n]] = rid
    size = 8 + 4 * len(ids)
    return struct.pack("<HHI", 0x0180, 8, size) + \
        b"".join(struct.pack("<I", x) for x in ids)


def _node_head(chunk_type, hdr_size, size):
    return struct.pack("<HHIII", chunk_type, hdr_size, size, 0, 0xFFFFFFFF)


class ManifestBuilder:
    def __init__(self):
        self.pool = _Pool()
        self.attr_ids = {}
        self.body = []

    def _attr_name(self, name, namespaced):
        idx = self.pool.add(name)
        if not namespaced:
            # e.g. <manifest package="..."> has no framework resource id;
            # Android parses it by attribute NAME, so no res-map entry needed.
            return idx
        rid = RES_IDS.get(name)
        if rid is None:
            raise ValueError("unknown framework attr: " + name)
        self.attr_ids[name] = rid
        return idx

    def _encode_value(self, v):
        if isinstance(v, tuple):
            kind = v[0]
            if kind == "str":
                i = self.pool.add(v[1])
                return (i, TYPE_STRING, i)
            if kind == "int":
                return (0xFFFFFFFF, TYPE_INT_DEC, int(v[1]))
            if kind == "bool":
                return (0xFFFFFFFF, TYPE_INT_BOOLEAN,
                        0xFFFFFFFF if v[1] else 0)
            if kind == "ref":
                return (0xFFFFFFFF, 0x01, int(v[1], 0))
        raise ValueError("bad attr value: %r" % (v,))

    def ns_start(self):
        self.body.append(("ns", 0x0100,
                          self.pool.add(NS_PREFIX), self.pool.add(NS_URI)))

    def ns_end(self):
        self.body.append(("ns", 0x0101,
                          self.pool.add(NS_PREFIX), self.pool.add(NS_URI)))

    def start(self, name, attrs=None, namespaced=True, non_ns=None):
        """non_ns: 属性名集合，这些属性编码为无命名空间（如 <manifest package=..>）"""
        name_idx = self.pool.add(name)
        el_ns = self.pool.index[NS_URI] if namespaced else 0xFFFFFFFF
        non_ns = non_ns or set()
        enc_attrs = []
        for aname, aval in (attrs or {}).items():
            if namespaced and aname not in non_ns:
                attr_ns = el_ns
                an_idx = self._attr_name(aname, True)
            else:
                attr_ns = 0xFFFFFFFF
                an_idx = self._attr_name(aname, False)
            raw, dt, data = self._encode_value(aval)
            enc_attrs.append((attr_ns, an_idx, raw, dt, data))
        self.body.append(("se", name_idx, enc_attrs))

    def end(self, name):
        self.body.append(("ee", self.pool.add(name)))

    def to_bytes(self):
        spool = _string_pool_chunk(self.pool, getattr(self, "utf16", False))
        rmap = _res_map_chunk(self.pool, self.attr_ids)

        chunks = []
        for item in self.body:
            if item[0] == "ns":
                _, t, p, u = item
                chunks.append(_node_head(t, 16, 24) +
                              struct.pack("<II", p, u))
            elif item[0] == "se":
                _, name_idx, attrs = item
                el_ns = self.pool.index[NS_URI]
                n_attr = len(attrs)
                size = 16 + 8 + 12 + 20 * n_attr
                buf = _node_head(0x0102, 16, size)
                buf += struct.pack("<II", el_ns, name_idx)
                buf += struct.pack("<HHHHHH", 0x14, 0x14, n_attr, 0, 0, 0)
                for a_ns, an_idx, raw, dt, data in attrs:
                    buf += struct.pack("<IIIHBBI", a_ns, an_idx, raw,
                                       8, 0, dt, data)
                chunks.append(buf)
            elif item[0] == "ee":
                _, name_idx = item
                chunks.append(_node_head(0x0103, 16, 24) +
                              struct.pack("<II",
                                          self.pool.index[NS_URI], name_idx))

        body_bytes = b"".join(chunks)
        total = 8 + len(spool) + len(rmap) + len(body_bytes)
        head = struct.pack("<HHI", 0x0003, 8, total)
        return head + spool + rmap + body_bytes


def build_module_manifest(package, version_code, version_name,
                          min_sdk, target_sdk, app_label,
                          activities, meta_datas, allow_backup=False,
                          icon_ref=None, utf16=False, order_std=False):
    """activities: [{name,label,exported,launcher,module_settings}]
       meta_datas: [(name, value)]
       icon_ref: "@drawable/ic_launcher" 的数字引用，如 "0x7f020000"（None=不写 icon 属性）
       utf16: 字符串池用 UTF-16（aapt 传统风格）
       order_std: manifest 属性按 aapt 顺序（versionCode/versionName 在前）"""
    mb = ManifestBuilder()
    mb.utf16 = utf16
    mb.ns_start()

    if order_std:
        manifest_attrs = {
            "versionCode": ("int", version_code),
            "versionName": ("str", version_name),
            "package": ("str", package),
        }
    else:
        manifest_attrs = {
            "package": ("str", package),
            "versionCode": ("int", version_code),
            "versionName": ("str", version_name),
        }
    mb.start("manifest", manifest_attrs,
             namespaced=True, non_ns={"package"})
    mb.start("uses-sdk", {
        "minSdkVersion": ("int", min_sdk),
        "targetSdkVersion": ("int", target_sdk),
    })
    mb.end("uses-sdk")
    # application 属性按 aapt 惯例按 resid 升序：label(0x01010001) < icon(0x01010002)
    #   < allowBackup(0x01010280)
    app_attrs = {"label": ("str", app_label)}
    if icon_ref:
        app_attrs["icon"] = ("ref", icon_ref)
    app_attrs["allowBackup"] = ("bool", allow_backup)
    mb.start("application", app_attrs)
    for act in activities:
        mb.start("activity", {
            "name": ("str", act["name"]),
            "label": ("str", act.get("label", act["name"])),
            "exported": ("bool", bool(act.get("exported", False))),
        })
        if act.get("launcher") or act.get("module_settings"):
            mb.start("intent-filter")
            if act.get("launcher"):
                mb.start("action", {"name": ("str",
                                             "android.intent.action.MAIN")})
                mb.end("action")
                mb.start("category", {"name": ("str",
                        "android.intent.category.LAUNCHER")})
                mb.end("category")
            if act.get("module_settings"):
                mb.start("category", {"name": ("str",
                        "de.robv.android.xposed.category.MODULE_SETTINGS")})
                mb.end("category")
            mb.end("intent-filter")
        mb.end("activity")
    for mdname, mdvalue in meta_datas:
        if not isinstance(mdvalue, tuple):
            mdvalue = ("str", mdvalue)
        mb.start("meta-data", {
            "name": ("str", mdname),
            "value": mdvalue,
        })
        mb.end("meta-data")
    mb.end("application")
    mb.end("manifest")
    mb.ns_end()
    return mb.to_bytes()
