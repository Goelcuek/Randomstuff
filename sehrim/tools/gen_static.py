#!/usr/bin/env python3
"""Şehrim – şehir başına sabit veri üretir: eczaneler + toplu taşıma hatları (OpenStreetMap / Overpass)."""
import json, re, sys, os, time, math, datetime, socket, urllib.request, urllib.parse

# overpass-api.de'ye IPv6 ile bağlanmak runner'larda ~30 sn takılıyor: IPv4'e zorla
_gai = socket.getaddrinfo
socket.getaddrinfo = lambda host, port, family=0, *a, **k: _gai(host, port, socket.AF_INET, *a, **k)

UA = "Sehrim-datagen/1.0 (+https://github.com/Goelcuek/Randomstuff)"
ENDPOINTS = ["https://overpass-api.de/api/interpreter"]
CITIES = {"eskisehir": "Eskişehir", "ankara": "Ankara", "istanbul": "İstanbul"}
MODES = "bus|trolleybus|minibus|share_taxi|tram|light_rail|subway|train|ferry|monorail|funicular|aerialway"
STOP_ROLES = ("stop", "stop_entry_only", "stop_exit_only")
PLATFORM_ROLES = ("platform", "platform_entry_only", "platform_exit_only")


def overpass(q):
    last = None
    for attempt in range(5):
        ep = ENDPOINTS[attempt % len(ENDPOINTS)]
        t0 = time.time()
        try:
            data = urllib.parse.urlencode({"data": q}).encode()
            req = urllib.request.Request(ep, data=data, headers={"User-Agent": UA, "Accept": "*/*"})
            with urllib.request.urlopen(req, timeout=700) as r:
                body = r.read()
            d = json.loads(body)
            if d.get("remark") and not d.get("elements"):
                raise RuntimeError(d["remark"][:200])
            print(f"  overpass ok {ep.split('/')[2]} {len(body)/1e6:.1f} MB {time.time()-t0:.0f} s", flush=True)
            return d
        except Exception as e:
            last = e
            print(f"  overpass hata {ep.split('/')[2]} {time.time()-t0:.0f} s: {e}", flush=True)
            time.sleep(30 * (attempt + 1))
    raise last


def enc_num(v):
    v = ~(v << 1) if v < 0 else (v << 1)
    out = []
    while v >= 0x20:
        out.append(chr((0x20 | (v & 0x1f)) + 63)); v >>= 5
    out.append(chr(v + 63))
    return "".join(out)


def encode(pts):
    res, plat, plon = [], 0, 0
    for lat, lon in pts:
        a, b = round(lat * 1e5), round(lon * 1e5)
        res.append(enc_num(a - plat)); res.append(enc_num(b - plon)); plat, plon = a, b
    return "".join(res)


def simplify(pts, tol_m=6.0):
    if len(pts) < 3: return pts
    k = 111320.0
    cl = math.cos(math.radians(pts[0][0]))
    xy = [(p[1] * k * cl, p[0] * k) for p in pts]
    keep = [False] * len(pts); keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        s, e = stack.pop()
        (x1, y1), (x2, y2) = xy[s], xy[e]
        dx, dy = x2 - x1, y2 - y1; L = dx * dx + dy * dy
        best, bi = 0, -1
        for i in range(s + 1, e):
            x, y = xy[i]
            if L == 0: d = math.hypot(x - x1, y - y1)
            else:
                t = max(0, min(1, ((x - x1) * dx + (y - y1) * dy) / L))
                d = math.hypot(x - (x1 + t * dx), y - (y1 + t * dy))
            if d > best: best, bi = d, i
        if best > tol_m:
            keep[bi] = True; stack += [(s, bi), (bi, e)]
    return [p for p, k_ in zip(pts, keep) if k_]


def chain(ways):
    """Ardışık yolları birleştirip mümkün olduğunca az çizgi parçası üretir."""
    lines = []
    for w in ways:
        if len(w) < 2: continue
        if lines:
            cur = lines[-1]
            if cur[-1] == w[0]: cur.extend(w[1:]); continue
            if cur[-1] == w[-1]: cur.extend(reversed(w[:-1])); continue
            # ilk parça ters yönde girilmiş olabilir
            if cur[0] == w[0] and len(lines) == 1:
                cur.reverse(); cur.extend(w[1:]); continue
            if cur[0] == w[-1] and len(lines) == 1:
                cur.reverse(); cur.extend(reversed(w[:-1])); continue
        lines.append(list(w))
    return lines


def addr(t):
    parts = [t.get("addr:street", ""), t.get("addr:housenumber", "")]
    s = " ".join(p for p in parts if p).strip()
    for k in ("addr:suburb", "addr:district", "addr:city"):
        if t.get(k): s = (s + ", " if s else "") + t[k]; break
    return s


def gen_city(slug, name, outdir):
    print(f"== {name}", flush=True)
    area = f'area["name"="{name}"]["admin_level"="4"]["boundary"="administrative"]->.a;'
    ph = overpass(f'[out:json][timeout:240];{area}nwr["amenity"="pharmacy"](area.a);out center tags;')
    pharm = []
    for e in ph["elements"]:
        t = e.get("tags", {})
        lat = e.get("lat", e.get("center", {}).get("lat")); lon = e.get("lon", e.get("center", {}).get("lon"))
        if lat is None: continue
        if re.search(r"watsons|gratis|rossmann|kozmetik|parf[üu]m|flormar|medikal|optik|aktar|herbal", t.get("name", ""), re.I): continue
        pharm.append([t.get("name", "Eczane"), round(lat, 6), round(lon, 6), t.get("opening_hours", ""),
                      addr(t), t.get("phone", t.get("contact:phone", ""))])
    print("  eczane:", len(pharm), "saatli:", sum(1 for p in pharm if p[3]), flush=True)

    hs = overpass(f'[out:json][timeout:240];{area}(nwr["amenity"~"^(hospital|clinic)$"](area.a);nwr["healthcare"="hospital"](area.a););out center tags;')
    hospitals = []
    for e in hs["elements"]:
        t = e.get("tags", {})
        lat = e.get("lat", e.get("center", {}).get("lat")); lon = e.get("lon", e.get("center", {}).get("lon"))
        nm = t.get("name", "")
        if lat is None or not nm: continue
        kind = "hospital" if (t.get("amenity") == "hospital" or t.get("healthcare") == "hospital") else "clinic"
        low = nm.lower()
        if "aile sağlığı" in low or "asm" in low.split(): kind = "asm"
        em = t.get("emergency", "")
        if not em and kind == "hospital" and t.get("operator:type") in ("government", "public"): em = "likely"
        hospitals.append([nm, round(lat, 6), round(lon, 6), kind, em, t.get("phone", t.get("contact:phone", "")),
                          addr(t), t.get("operator:type", ""), t.get("operator", "")])
    print("  sağlık:", len(hospitals), "hastane:", sum(1 for h in hospitals if h[3] == "hospital"),
          "acil:", sum(1 for h in hospitals if h[4] == "yes"), flush=True)

    p = os.path.join(outdir, f"static-{slug}.json")
    try:
        routes = gen_routes(area)
    except Exception as e:
        print("  hat verisi alınamadı, öncekiler korunuyor:", e, flush=True)
        try:
            routes = json.load(open(p, encoding="utf-8")).get("routes", [])
        except Exception:
            routes = []
    out = {"city": name, "updated": datetime.date.today().isoformat(), "pharmacies": pharm, "hospitals": hospitals, "routes": routes}
    with open(p, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    print("  boyut:", round(os.path.getsize(p) / 1e6, 2), "MB", flush=True)


def gen_routes(area):

    # Not: [maxsize] / büyük timeout istemek Overpass'ın sorguyu "yoğunluk" diye 504 ile reddetmesine yol açıyor.
    # Yollar ayrı ve tekil alınır (out geom her ilişkide ortak yolları tekrar ederdi).
    rels, ways, nodes = {}, {}, {}
    for group in ("tram|light_rail|subway|train|ferry|monorail|funicular|aerialway", "bus|trolleybus", "minibus|share_taxi"):
        q = (f'[out:json][timeout:300];{area}'
             f'rel["type"="route"]["route"~"^({group})$"](area.a)->.r;'
             f'.r out body;way(r.r);out skel geom;node(r.r);out;')
        for e in overpass(q)["elements"]:
            if e["type"] == "relation": rels[e["id"]] = e
            elif e["type"] == "way" and e.get("geometry"): ways[e["id"]] = [(round(g["lat"], 6), round(g["lon"], 6)) for g in e["geometry"]]
            elif e["type"] == "node": nodes[e["id"]] = e
        time.sleep(15)
    routes = []
    for r in rels.values():
        t = r.get("tags", {})
        stops, plats, rways = [], [], []
        for m in r.get("members", []):
            role = m.get("role", "")
            if m["type"] == "node" and m["ref"] in nodes:
                n = nodes[m["ref"]]
                rec = [n.get("tags", {}).get("name", ""), round(n["lat"], 5), round(n["lon"], 5)]
                if role in STOP_ROLES: stops.append(rec)
                elif role in PLATFORM_ROLES: plats.append(rec)
            elif m["type"] == "way" and role in ("", "forward", "backward", "main") and m["ref"] in ways:
                rways.append(ways[m["ref"]])
        st = stops if stops else plats
        # durağın adı yoksa aynı sıradaki platformun adını kullan
        if stops and plats and len(stops) == len(plats):
            for s, p in zip(stops, plats):
                if not s[0]: s[0] = p[0]
        # aynı isimli, art arda gelen durakları tekilleştir
        ded = []
        for s in st:
            if ded and s[0] and ded[-1][0] == s[0]: continue
            ded.append(s)
        geom = [encode(simplify(l)) for l in chain(rways)]
        routes.append({
            "id": r["id"], "m": t.get("route"), "ref": t.get("ref", ""), "n": t.get("name", ""),
            "f": t.get("from", ""), "t": t.get("to", ""), "o": t.get("operator", t.get("network", "")),
            "c": t.get("colour", ""), "s": ded, "g": geom,
        })
    routes.sort(key=lambda x: (x["m"], len(x["ref"]), x["ref"], x["n"]))
    print("  yol:", len(ways), "düğüm:", len(nodes))
    print("  hat:", len(routes), "duraklı:", sum(1 for r in routes if r["s"]), flush=True)
    return routes


if __name__ == "__main__":
    outdir = sys.argv[1] if len(sys.argv) > 1 else "data"
    only = sys.argv[2:] or list(CITIES)
    os.makedirs(outdir, exist_ok=True)
    for slug in only:
        try:
            gen_city(slug, CITIES[slug], outdir)
        except Exception as e:
            print("HATA", slug, e, flush=True)
        time.sleep(20)
