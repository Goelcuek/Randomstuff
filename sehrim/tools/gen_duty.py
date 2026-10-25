#!/usr/bin/env python3
"""Şehrim – nöbetçi eczane verisi üretir (Fanatik + oda siteleri yedek)."""
import json, re, sys, os, html, time, datetime, urllib.request, urllib.parse

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
CITIES = {"istanbul": "İstanbul", "ankara": "Ankara", "eskisehir": "Eskişehir"}
TZ = datetime.timezone(datetime.timedelta(hours=3))

def get(url, data=None, timeout=60):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")

def clean(s):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", s))).strip()

def fanatik(slug):
    t = get(f"https://www.fanatik.com.tr/nobetci-eczaneler/{slug}/")
    info = re.search(r'ecz-module-info-text">(.*?)<', t, re.S)
    items = []
    for blk in t.split('class="ecz-module-pharmacy-item"')[1:]:
        g = lambda p: (m.group(1) if (m := re.search(p, blk, re.S)) else "")
        name = clean(g(r'pharmacy-name">(.*?)</div>'))
        sub = clean(g(r'pharmacy-subtitle">(.*?)</div>'))
        addr = clean(g(r'<b>Adres:</b>(.*?)</div>'))
        tel = clean(g(r'<b>Telefon:</b>(.*?)</div>'))
        m = re.search(r'query=(-?[0-9.]+),(-?[0-9.]+)', blk)
        lat, lon = (float(m.group(1)), float(m.group(2))) if m else (None, None)
        dist = sub.split("/")[-1].strip() if "/" in sub else sub
        if name:
            items.append({"n": name, "d": dist, "a": addr, "p": re.sub(r"[^0-9]", "", tel), "lat": lat, "lon": lon})
    return {"source": "fanatik.com.tr", "info": clean(info.group(1)) if info else "", "items": items}

def eskisehir_eo():
    t = get("https://www.eskisehireo.org.tr/eskisehir-nobetci-eczaneler")
    items, w = [], None
    for blk in t.split('class="col-md-12 nobetci"')[1:]:
        m = re.search(r'<strong>(.*?)</strong>\s*-\s*(.*?)</h4>', blk, re.S)
        if not m: continue
        a = re.search(r"fa-home'></i>(.*?)<br", blk, re.S)
        p = re.search(r'tel:([0-9]+)', blk)
        c = re.search(r'maps\?q=(-?[0-9.]+),(-?[0-9.]+)', blk)
        items.append({"n": clean(m.group(1)).title(), "d": clean(m.group(2)), "a": clean(a.group(1)) if a else "",
                      "p": p.group(1) if p else "", "lat": float(c.group(1)) if c else None, "lon": float(c.group(2)) if c else None})
        w = re.search(r'([0-9.]+ [0-9:]+) / ([0-9.]+ [0-9:]+) nöbetçidir', blk) or w
    return {"source": "eskisehireo.org.tr", "info": w.group(0) if w else "", "items": items}

MONTHS = ["ocak", "şubat", "mart", "nisan", "mayıs", "haziran", "temmuz", "ağustos", "eylül", "ekim", "kasım", "aralık"]

def duty_date(info, now, change_h, change_m):
    """Kaynağın tarih metninden nöbet gününü çıkarır; olmazsa saate göre hesaplar."""
    m = re.search(r"(\d{1,2})\.(\d{2})\.(\d{4})", info)
    if m:
        return f"{m.group(3)}-{m.group(2)}-{int(m.group(1)):02d}"
    m = re.search(r"(\d{1,2})\s+(\w+)", info)
    if m and m.group(2).lower() in MONTHS:
        d = datetime.date(now.year, MONTHS.index(m.group(2).lower()) + 1, int(m.group(1)))
        if (d - now.date()).days > 180: d = d.replace(year=d.year - 1)
        if (now.date() - d).days > 180: d = d.replace(year=d.year + 1)
        return d.isoformat()
    return (now - datetime.timedelta(hours=change_h, minutes=change_m)).date().isoformat()

CHANGE = {"istanbul": (9, 0), "ankara": (8, 30), "eskisehir": (8, 30)}

def main(outdir):
    os.makedirs(outdir, exist_ok=True)
    now = datetime.datetime.now(TZ)
    for slug, name in CITIES.items():
        res, errs = None, []
        tries = [lambda s=slug: fanatik(s)]
        if slug == "eskisehir": tries.append(eskisehir_eo)
        for fn in tries:
            try:
                r = fn()
                if len(r["items"]) >= 3:
                    res = r; break
                errs.append(f"{r['source']}: {len(r['items'])} kayıt")
            except Exception as e:
                errs.append(repr(e))
        if not res:
            print(slug, "FAILED", errs); continue
        res.update({"city": name, "updated": now.isoformat(timespec="minutes"), "dutyDate": duty_date(res["info"], now, *CHANGE[slug])})
        with open(os.path.join(outdir, f"duty-{slug}.json"), "w", encoding="utf-8") as f:
            json.dump(res, f, ensure_ascii=False, separators=(",", ":"))
        nocoord = sum(1 for i in res["items"] if i["lat"] is None)
        print(slug, res["source"], len(res["items"]), "no-coord:", nocoord, "|", res["info"], "|", errs)

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "data")
