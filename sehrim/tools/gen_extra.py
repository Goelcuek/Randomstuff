#!/usr/bin/env python3
"""Şehrim – tarayıcının doğrudan okuyamadığı (CORS'suz) kaynaklar:
AFAD depremleri (son 7 gün) ve ASKİ (Ankara) su kesintileri."""
import json, re, os, sys, html, datetime, urllib.request

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
TZ = datetime.timezone(datetime.timedelta(hours=3))


def get(url, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def clean(s):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", s))).strip()


def afad(outdir):
    end = datetime.datetime.utcnow() + datetime.timedelta(minutes=5)
    start = end - datetime.timedelta(days=7)
    f = "%Y-%m-%dT%H:%M:%S"
    url = ("https://servisnet.afad.gov.tr/apigateway/deprem/apiv2/event/filter"
           f"?start={start.strftime(f)}&end={end.strftime(f)}&minmag=1&format=json")
    items = []
    for o in json.loads(get(url)):
        try:
            utc = datetime.datetime.fromisoformat(o["date"][:19]).replace(tzinfo=datetime.timezone.utc)
            items.append({"id": o.get("eventID"), "m": float(o["magnitude"]), "d": float(o.get("depth") or 0),
                          "p": o.get("location", ""), "lat": float(o["latitude"]), "lon": float(o["longitude"]),
                          "t": utc.astimezone(TZ).isoformat(timespec="seconds")})
        except Exception:
            pass
    items.sort(key=lambda x: x["t"], reverse=True)
    out = {"source": "AFAD", "updated": datetime.datetime.now(TZ).isoformat(timespec="minutes"), "items": items}
    json.dump(out, open(os.path.join(outdir, "quakes-afad.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print("afad", len(items))


def aski(outdir):
    t = get("https://www.aski.gov.tr/tr/Kesinti.aspx")
    items = []
    for blk in t.split('class="appear-animation"')[1:]:
        strongs = [clean(s) for s in re.findall(r"<strong>(.*?)</strong>", blk, re.S)]
        if not strongs:
            continue

        def field(label):
            m = re.search(r"<b>\s*" + label + r"\s*:?\s*</b>(.*?)(?:<br|</p>)", blk, re.S)
            return clean(m.group(1)) if m else ""

        def dt(s):
            try:
                return datetime.datetime.strptime(s.strip(), "%d.%m.%Y %H:%M:%S").isoformat(timespec="minutes")
            except Exception:
                return None

        rec = {"district": strongs[0], "kind": strongs[1] if len(strongs) > 1 else "",
               "start": dt(field("Arıza Tarihi")), "end": dt(field("Tamir Tarihi")),
               "detail": field("Detay"), "places": field("Etkilenen Yerler"), "utility": "Su"}
        if rec not in items:
            items.append(rec)
    out = {"source": "ASKİ", "updated": datetime.datetime.now(TZ).isoformat(timespec="minutes"), "items": items}
    json.dump(out, open(os.path.join(outdir, "outages-ankara.json"), "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print("aski", len(items))


if __name__ == "__main__":
    outdir = sys.argv[1] if len(sys.argv) > 1 else "data"
    os.makedirs(outdir, exist_ok=True)
    for fn in (afad, aski):
        try:
            fn(outdir)
        except Exception as e:
            print("HATA", fn.__name__, e)
