#!/usr/bin/env python3
"""Convert Flipper-IRDB TV .ir files into a compact JSON asset for the app.

Every signal (parsed protocol or raw) is encoded to a raw on/off pattern in
microseconds plus a carrier frequency, so the app only has to call
ConsumerIrManager.transmit().

Usage: python3 build_codes.py <Flipper-IRDB/TVs dir> <out tvcodes.json>
Source database: https://github.com/Lucaslhm/Flipper-IRDB (CC0 1.0)
"""
import json
import os
import re
import sys

# ---------------------------------------------------------------- helpers

def lsb(value, nbits):
    return [(value >> i) & 1 for i in range(nbits)]


def msb(value, nbits):
    return [(value >> (nbits - 1 - i)) & 1 for i in range(nbits)]


def pulse_distance(bits, mark, zero_space, one_space):
    out = []
    for b in bits:
        out += [mark, one_space if b else zero_space]
    return out


def merge(seq):
    """Merge consecutive same-level durations. seq is list of (level, us)."""
    out = []
    for level, us in seq:
        if out and out[-1][0] == level:
            out[-1][1] += us
        else:
            out.append([level, us])
    if out and out[0][0] == 0:
        out.pop(0)
    return [us for _, us in out]


def manchester(bits, half, one_is_mark_first):
    seq = []
    for b in bits:
        first = 1 if (b == 1) == one_is_mark_first else 0
        seq += [(first, half), (1 - first, half)]
    return seq


def fix_gap(pattern, frame_us):
    """Make sure pattern ends with an off period so total ~= frame_us."""
    if len(pattern) % 2 == 1:
        pattern.append(0)
    total = sum(pattern)
    pattern[-1] += max(frame_us - total, 20000) if frame_us else 20000
    return pattern

# ---------------------------------------------------------------- encoders

def enc_nec(a, c):
    bits = lsb(a & 0xFF, 8) + lsb(~a & 0xFF, 8) + lsb(c & 0xFF, 8) + lsb(~c & 0xFF, 8)
    p = [9000, 4500] + pulse_distance(bits, 560, 560, 1690) + [560]
    return 38000, fix_gap(p, 108000)


def enc_necext(a, c):
    bits = lsb(a & 0xFFFF, 16) + lsb(c & 0xFFFF, 16)
    p = [9000, 4500] + pulse_distance(bits, 560, 560, 1690) + [560]
    return 38000, fix_gap(p, 108000)


def enc_samsung(a, c):
    bits = lsb(a & 0xFF, 8) + lsb(a & 0xFF, 8) + lsb(c & 0xFF, 8) + lsb(~c & 0xFF, 8)
    p = [4500, 4500] + pulse_distance(bits, 550, 550, 1650) + [550]
    return 38000, fix_gap(p, 108000)


def enc_pioneer(a, c):
    bits = lsb(a & 0xFF, 8) + lsb(~a & 0xFF, 8) + lsb(c & 0xFF, 8) + lsb(~c & 0xFF, 8)
    p = [8500, 4250] + pulse_distance(bits, 500, 500, 1500) + [500]
    p = fix_gap(p, 0)
    p[-1] = 26000
    return 40000, p * 2


def enc_rc5(a, c, extended=False):
    field = 0 if (c & 0x40) else 1
    bits = [1, field, 0] + msb(a & 0x1F, 5) + msb(c & 0x3F, 6)
    # RC5: logical 1 = space then mark, 0 = mark then space
    p = merge(manchester(bits, 889, one_is_mark_first=False))
    return 36000, fix_gap(p, 114000)


def enc_rc6(a, c):
    seq = [(1, 2666), (0, 889)]
    seq += manchester([1, 0, 0, 0], 444, one_is_mark_first=True)
    seq += [(0, 889), (1, 889)]  # toggle bit (0), double width
    seq += manchester(msb(a & 0xFF, 8) + msb(c & 0xFF, 8), 444, one_is_mark_first=True)
    p = merge(seq)
    return 36000, fix_gap(p, 107000)


def enc_sirc(a, c, abits):
    bits = lsb(c & 0x7F, 7) + lsb(a, abits)
    p = [2400, 600]
    for b in bits:
        p += [1200 if b else 600, 600]
    p = fix_gap(p[:-1], 45000)
    return 40000, p * 3


def enc_kaseikyo(a, c):
    vendor = a & 0xFFFF
    genre1 = (a >> 16) & 0xF
    genre2 = (a >> 20) & 0xF
    dev_id = (a >> 24) & 0x3
    vp = vendor ^ (vendor >> 8)
    vp = (vp ^ (vp >> 4)) & 0xF
    d = [vendor & 0xFF, vendor >> 8, vp | (genre1 << 4),
         genre2 | ((c & 0xF) << 4), ((c >> 4) & 0x3F) | (dev_id << 6)]
    d.append(d[2] ^ d[3] ^ d[4])
    bits = []
    for byte in d:
        bits += lsb(byte, 8)
    p = [3456, 1728] + pulse_distance(bits, 432, 432, 1296) + [432]
    return 37000, fix_gap(p, 130000)


def enc_rca(a, c):
    bits = msb(a & 0xF, 4) + msb(c & 0xFF, 8) + msb(~a & 0xF, 4) + msb(~c & 0xFF, 8)
    p = [4000, 4000] + pulse_distance(bits, 500, 1000, 2000) + [500]
    return 56000, fix_gap(p, 64000)


ENCODERS = {
    "NEC": enc_nec,
    "NEC42": None,
    "NECext": enc_necext,
    "Samsung32": enc_samsung,
    "RC5": enc_rc5,
    "RC5X": lambda a, c: enc_rc5(a, c, True),
    "RC6": enc_rc6,
    "SIRC": lambda a, c: enc_sirc(a, c, 5),
    "SIRC15": lambda a, c: enc_sirc(a, c, 8),
    "SIRC20": lambda a, c: enc_sirc(a, c, 13),
    "Kaseikyo": enc_kaseikyo,
    "RCA": enc_rca,
    "Pioneer": enc_pioneer,
}

# ---------------------------------------------------------------- buttons

BUTTON_RULES = [
    ("power", r"^(power|pwr|on_?off|power_?toggle|standby|power_on_off)$"),
    ("vol_up", r"^(vol(ume)?_?(up|\+|plus)|v\+)$"),
    ("vol_dn", r"^(vol(ume)?_?(dn|down|-|minus)|v-)$"),
    ("mute", r"^mute$"),
    ("ch_up", r"^(ch(annel)?_?(next|up|\+|plus)|p\+|prog_?up)$"),
    ("ch_dn", r"^(ch(annel)?_?(prev|down|dn|-|minus)|p-|prog_?(down|dn))$"),
    ("up", r"^(up|arrow_?up|cursor_?up|nav_?up)$"),
    ("down", r"^(down|dn|arrow_?down|cursor_?down|nav_?down)$"),
    ("left", r"^(left|arrow_?left|cursor_?left|nav_?left)$"),
    ("right", r"^(right|arrow_?right|cursor_?right|nav_?right)$"),
    ("ok", r"^(ok|enter|select|center|confirm)$"),
    ("back", r"^(back|return|ret)$"),
    ("home", r"^(home|smart_?hub|smart)$"),
    ("menu", r"^(menu|settings|setup|tools|options?)$"),
    ("input", r"^(source|input|av|tv_?av|hdmi|input_?source)$"),
    ("info", r"^(info|display|disp)$"),
    ("exit", r"^exit$"),
    ("guide", r"^(guide|epg)$"),
    ("play", r"^(play|play_?pause|play/pause)$"),
    ("pause", r"^pause$"),
    ("stop", r"^stop$"),
    ("rew", r"^(rew|rewind|fast_?ba(ck)?|rwd)$"),
    ("ff", r"^(ff|fwd|forward|fast_?fo(rward)?|fast_?fwd|fastforward)$"),
    ("red", r"^red$"), ("green", r"^green$"),
    ("yellow", r"^yellow$"), ("blue", r"^blue$"),
    ("netflix", r"^netflix$"), ("youtube", r"^you_?tube$"),
    ("text", r"^(text|txt|teletext)$"),
    ("subtitle", r"^(subtitle|subt?|cc)$"),
    ("sleep", r"^sleep$"),
]
for d in range(10):
    BUTTON_RULES.append((str(d), r"^(key_?|num_?|btn_?)?%d$" % d))
BUTTON_RULES = [(k, re.compile(r, re.I)) for k, r in BUTTON_RULES]


def canon(name):
    n = name.strip().replace(" ", "_")
    for key, rx in BUTTON_RULES:
        if rx.match(n):
            return key
    return None

# ---------------------------------------------------------------- parsing

def hexbytes_le(s):
    b = [int(x, 16) for x in s.split()]
    return sum(v << (8 * i) for i, v in enumerate(b))


def parse_file(path):
    blocks, cur = [], {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if line.startswith("#") or not line:
                if line.startswith("#") and cur:
                    blocks.append(cur)
                    cur = {}
                continue
            if ":" not in line:
                continue
            k, v = line.split(":", 1)
            k, v = k.strip(), v.strip()
            if k == "name" and "name" in cur:
                blocks.append(cur)
                cur = {}
            cur[k] = v
    if cur:
        blocks.append(cur)
    return [b for b in blocks if "name" in b and "type" in b]


def encode(block):
    if block["type"] == "raw":
        freq = int(block.get("frequency", 38000))
        data = [int(x) for x in block.get("data", "").split()]
        data = [max(1, min(x, 200000)) for x in data]
        if len(data) < 4:
            return None
        # Android rejects patterns longer than 2 s; keep whole frames < 1.9 s.
        total, cut = 0, len(data)
        for i, x in enumerate(data):
            total += x
            if total > 1_900_000:
                cut = i
                break
        data = data[:cut]
        if len(data) % 2 == 1:
            data.append(30000)
        return freq, data
    proto = block.get("protocol")
    enc = ENCODERS.get(proto)
    if not enc:
        return None
    a = hexbytes_le(block.get("address", "00"))
    c = hexbytes_le(block.get("command", "00"))
    return enc(a, c)


def main(src, out):
    brands = {}
    for brand in sorted(os.listdir(src), key=str.lower):
        bdir = os.path.join(src, brand)
        if not os.path.isdir(bdir):
            continue
        sets, seen = [], set()
        for root, _, files in os.walk(bdir):
            for fn in sorted(files):
                if not fn.lower().endswith(".ir"):
                    continue
                keys = {}
                for blk in parse_file(os.path.join(root, fn)):
                    key = canon(blk["name"])
                    if not key or key in keys:
                        continue
                    sig = encode(blk)
                    if sig:
                        keys[key] = [sig[0]] + sig[1]
                if "power" not in keys and len(keys) < 4:
                    continue
                fp = json.dumps(sorted(keys.items()))
                if fp in seen:
                    continue
                seen.add(fp)
                model = os.path.splitext(fn)[0].replace("_", " ")
                sets.append({"model": model, "keys": keys})
        if sets:
            # Most complete sets first; they're the best bet for "try next".
            sets.sort(key=lambda s: (-("power" in s["keys"]), -len(s["keys"])))
            brands[brand.replace("_", " ")] = sets
    with open(out, "w") as f:
        json.dump({"source": "Flipper-IRDB (CC0)", "brands": brands}, f,
                  separators=(",", ":"))
    n = sum(len(v) for v in brands.values())
    print(f"{len(brands)} brands, {n} code sets -> {out} "
          f"({os.path.getsize(out) // 1024} KB)")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
