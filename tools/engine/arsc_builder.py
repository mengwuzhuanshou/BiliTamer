# -*- coding: utf-8 -*-
"""Build a minimal but structurally standard resources.arsc.
Layout replicated from com.hihonor.appmarket 16.1.6.302 (aapt2 output):
  table(0x0002, hs=12) + global value pool(UTF-8)
  + package(0x0200, hs=288, id=0x7f)
      + typeStrings pool (UTF-16, flags=0)
      + keyStrings pool  (UTF-8, flags=0x100)
      + typeSpec(0x0202, hs=16)
      + type(0x0201, hs=84, config.size=64)
"""
import struct


def _enc_len(v):
    if v < 128:
        return bytes([v])
    return bytes([0x80 | ((v >> 8) & 0xFF), v & 0xFF])


def utf8_pool(strings):
    """UTF-8 string pool (flags=0x100), same encoder as axml_writer."""
    data = b""
    offsets = []
    for s in strings:
        offsets.append(len(data))
        b = s.encode("utf-8")
        u16 = len(s.encode("utf-16-le")) // 2
        data += _enc_len(u16) + _enc_len(len(b)) + b + b"\x00"
    while len(data) % 4:
        data += b"\x00"
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    size = strings_start + len(data)
    out = struct.pack("<HHIIIIII", 0x0001, header_size, size,
                      len(strings), 0, 0x00000100, strings_start, 0)
    out += b"".join(struct.pack("<I", o) for o in offsets)
    return out + data


def utf16_pool(strings):
    """UTF-16 string pool (flags=0)."""
    data = b""
    offsets = []
    for s in strings:
        offsets.append(len(data))
        b = s.encode("utf-16-le")
        n = len(s)
        if n < 0x8000:
            data += struct.pack("<H", n)
        else:
            data += struct.pack("<HH", (n >> 16) | 0x8000, n & 0xFFFF)
        data += b + b"\x00\x00"
    while len(data) % 4:
        data += b"\x00"
    header_size = 28
    strings_start = header_size + 4 * len(strings)
    size = strings_start + len(data)
    out = struct.pack("<HHIIIIII", 0x0001, header_size, size,
                      len(strings), 0, 0x00000000, strings_start, 0)
    out += b"".join(struct.pack("<I", o) for o in offsets)
    return out + data


def build_minimal_arsc(package, app_name, icon_path=None):
    """icon_path: 如 "res/drawable-xxhdpi-v4/ic_launcher.png"。
       传入时额外注册 drawable/ic_launcher（typeId=2, entry=0），
       值为全局池中的文件路径字符串（Res_value STRING），config 带 density=480 + v4。
       资源 id 布局：0x7f010000=string/app_name, 0x7f020000=drawable/ic_launcher。"""
    # ---- global value pool（资源值字符串：app_name 在 0，图标路径在 1）----
    gstrings = [app_name] + ([icon_path] if icon_path else [])
    gpool = utf8_pool(gstrings)

    # ---- package body pieces ----
    tstrings = ["string"] + (["drawable"] if icon_path else [])
    tpool = utf16_pool(tstrings)            # typeId 从 1 起
    kstrings = ["app_name"] + (["ic_launcher"] if icon_path else [])
    kpool = utf8_pool(kstrings)             # key 从 0 起

    chunks = []

    # --- string/app_name（typeId=1, key=0, 默认 config, 值=全局池[0]）---
    chunks.append(_type_spec(1, 1))
    chunks.append(_type_chunk(type_id=1, key_idx=0, value_pool_idx=0,
                              config=_default_config()))

    # --- drawable/ic_launcher（typeId=2, key=1, 默认 config, 值=路径字符串）---
    if icon_path:
        chunks.append(_type_spec(2, 1))
        chunks.append(_type_chunk(type_id=2, key_idx=1, value_pool_idx=1,
                                  config=_default_config()))

    type_spec = chunks[0]
    rest = chunks[1:]

    # ---- package chunk ----
    type_strings_off = 288
    key_strings_off = type_strings_off + len(tpool)
    after_pools = key_strings_off + len(kpool)
    pad = (-after_pools) % 4
    spec_off = after_pools + pad

    name = package.encode("utf-16-le")[:254]
    name = name + b"\x00" * (256 - len(name))

    pkg_payload = (tpool + kpool + b"\x00" * pad + type_spec
                   + b"".join(rest))
    pkg_size = 288 + len(pkg_payload)
    # ResTable_package: type/hsize/size/id(12B) + name[256] + 4*u32(16B) = 284,
    # aapt2 pads the header to 288 -> keep identical shape (id sits at offset 8).
    pkg = struct.pack("<HHII", 0x0200, 288, pkg_size, 0x0000007F)
    pkg += name
    pkg += struct.pack("<IIII", type_strings_off, 0, key_strings_off, 0)
    pkg += b"\x00" * 4
    pkg += pkg_payload

    # ---- table ----
    total = 12 + len(gpool) + pkg_size
    table = struct.pack("<HHII", 0x0002, 12, total, 1)
    return table + gpool + pkg


def _type_spec(type_id, entry_count):
    spec = struct.pack("<HHIBBHI", 0x0202, 16, 16 + 4 * entry_count,
                       type_id, 0, 0, entry_count)
    return spec + b"\x00" * (4 * entry_count)   # 每条目 flags=0


def _default_config():
    return struct.pack("<I", 64) + b"\x00" * 60


def _xxhdpi_v4_config():
    # ResTable_config: size@0, density(u16)@14=480(xxhdpi), sdkVersion(u16)@24=4
    c = bytearray(64)
    c[0:4] = struct.pack("<I", 64)
    c[14:16] = struct.pack("<H", 480)
    c[24:26] = struct.pack("<H", 4)
    return bytes(c)


def _type_chunk(type_id, key_idx, value_pool_idx, config):
    entries_start = 84 + 4 * 1              # header + 1 offset slot
    entry = struct.pack("<HHI", 8, 0, key_idx)    # size, flags, keyIdx
    # Res_value: size=8, res0=0, dataType=0x03(STRING), data=全局池下标
    entry += struct.pack("<HBBI", 8, 0, 0x03, value_pool_idx)
    body = struct.pack("<I", 0)             # entry offset [0]
    # 注意：chunk 总长 = entriesStart(已含偏移表) + 条目字节，勿把 body 重复计一次，
    # 否则声明 size 比物理字节多 4，多 type 时后续 chunk 解析即错位（v1.3.1 踩坑）。
    type_chunk = struct.pack("<HHI", 0x0201, 84,
                             entries_start + len(entry))
    type_chunk += struct.pack("<BBHII", type_id, 0, 0, 1, entries_start)
    type_chunk += config
    type_chunk += body + entry
    return type_chunk
