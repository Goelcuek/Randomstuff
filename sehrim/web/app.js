"use strict";
/* Şehrim web — İstanbul, Ankara, Eskişehir: açık/nöbetçi eczaneler, acil servisler, ulaşım, kesintiler, depremler. */

const RAW = "https://raw.githubusercontent.com/Goelcuek/Randomstuff/sehrim-data/";
const CITIES = {
  istanbul:  { title: "İstanbul",  lat: 41.0082, lon: 28.9784, change: [9, 0] },
  ankara:    { title: "Ankara",    lat: 39.9334, lon: 32.8597, change: [8, 30] },
  eskisehir: { title: "Eskişehir", lat: 39.7767, lon: 30.5206, change: [8, 30] },
};
const HOLIDAYS = new Set([
  "2026-01-01", "2026-03-20", "2026-03-21", "2026-03-22", "2026-04-23", "2026-05-01", "2026-05-19",
  "2026-05-27", "2026-05-28", "2026-05-29", "2026-05-30", "2026-07-15", "2026-08-30", "2026-10-29",
  "2027-01-01", "2027-03-09", "2027-03-10", "2027-03-11", "2027-04-23", "2027-05-01", "2027-05-16",
  "2027-05-17", "2027-05-18", "2027-05-19", "2027-07-15", "2027-08-30", "2027-10-29",
  "2028-01-01", "2028-04-23", "2028-05-01", "2028-05-19", "2028-07-15", "2028-08-30", "2028-10-29",
]);
const DAYS = ["Pazar", "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi"];
const MONTHS = ["ocak", "şubat", "mart", "nisan", "mayıs", "haziran", "temmuz", "ağustos", "eylül", "ekim", "kasım", "aralık"];
const MODES = {
  subway: ["Metro", "#E30A17"], light_rail: ["Hafif raylı", "#8E44AD"], tram: ["Tramvay", "#0072BC"],
  train: ["Tren", "#2E7D32"], ferry: ["Vapur", "#00838F"], bus: ["Otobüs", "#E07000"], trolleybus: ["Troleybüs", "#E07000"],
  minibus: ["Minibüs", "#6D4C41"], share_taxi: ["Dolmuş", "#6D4C41"], funicular: ["Füniküler", "#455A64"],
  monorail: ["Monoray", "#455A64"], aerialway: ["Teleferik", "#455A64"],
};
const MODE_ORDER = Object.keys(MODES);
const OUTAGE_LINKS = {
  istanbul: [
    ["Su", "İSKİ arıza ve kesintiler", "İstanbul su kesintileri", "https://iski.istanbul/"],
    ["Elektrik", "BEDAŞ kesinti haritası", "Avrupa Yakası", "https://kesinti.bedas.com.tr/"],
    ["Elektrik", "AYEDAŞ kesinti sorgulama", "Anadolu Yakası", "https://www.ayedas.com.tr/"],
  ],
  ankara: [
    ["Su", "ASKİ su arızaları", "Resmi sayfa", "https://www.aski.gov.tr/tr/Kesinti.aspx"],
    ["Elektrik", "Başkent EDAŞ kesinti sorgulama", "Ankara elektrik", "https://www.baskentedas.com.tr/"],
  ],
  eskisehir: [
    ["Su", "Eskişehir su kesintileri", "Güncel duyurular için web araması", "https://www.google.com/search?q=Eski%C5%9Fehir+su+kesintisi+bug%C3%BCn"],
    ["Elektrik", "OEDAŞ planlı kesintiler", "Eskişehir elektrik", "https://www.oedas.com.tr/"],
  ],
};

const S = {
  city: null, tab: "eczane", loc: null, locating: false,
  stat: {}, statErr: {}, duty: {}, dutyErr: {}, lines: {},
  quakes: null, quakeErr: null, outages: null, outageErr: null,
  ui: { dutyOnly: false, phQ: "", phN: 40, erOnly: true, hoQ: "", hoN: 40, trQ: "", trMode: "", trN: 60, qkR: 200, qkM: 2, ouQ: "" },
};

/* ---------------- helpers ---------------- */
const $ = (s, el = document) => el.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const store = {
  get(k) { try { return localStorage.getItem(k); } catch { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch {} },
};
const norm = (s) => String(s || "").replace(/İ/g, "i").replace(/I/g, "ı").toLocaleLowerCase("tr")
  .replace(/ı/g, "i").normalize("NFD").replace(/\p{M}+/gu, "");
const pad = (n) => String(n).padStart(2, "0");

/** Türkiye sabit UTC+3: "duvar saati" bileşenleri */
function nowTR(ms = Date.now()) {
  const d = new Date(ms + 3 * 3600e3);
  return { y: d.getUTCFullYear(), mo: d.getUTCMonth() + 1, d: d.getUTCDate(), h: d.getUTCHours(), mi: d.getUTCMinutes(),
    dow: d.getUTCDay(), date: `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`, ms };
}
function dutyDate(city, t = nowTR()) {
  const [ch, cm] = CITIES[city].change;
  return t.h * 60 + t.mi < ch * 60 + cm ? nowTR(t.ms - 86400e3).date : t.date;
}
function dist(a, b, c, d) {
  const R = 6371000, r = Math.PI / 180;
  const x = Math.sin((c - a) * r / 2) ** 2 + Math.cos(a * r) * Math.cos(c * r) * Math.sin((d - b) * r / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(x));
}
function fmtDist(m) {
  if (m == null) return "";
  if (m < 1000) return `${Math.round(m / 10) * 10} m`;
  if (m < 10000) return `${(m / 1000).toLocaleString("tr", { maximumFractionDigits: 1 })} km`;
  return `${Math.round(m / 1000)} km`;
}
const myDist = (lat, lon) => (S.loc && lat != null ? dist(S.loc.lat, S.loc.lon, lat, lon) : null);
const mapsUrl = (lat, lon) => `https://www.google.com/maps/search/?api=1&query=${lat},${lon}`;
const telUrl = (p) => "tel:" + String(p).replace(/[^\d+]/g, "");

async function getText(url, timeout = 20000) {
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), timeout);
  try {
    const r = await fetch(url, { signal: ctl.signal, cache: "no-cache" });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return await r.text();
  } finally { clearTimeout(t); }
}
const getJSON = async (url, timeout) => JSON.parse(await getText(url, timeout));

function decodePolyline(s) {
  const out = []; let i = 0, lat = 0, lon = 0;
  while (i < s.length) {
    for (const k of [0, 1]) {
      let shift = 0, res = 0, b;
      do { b = s.charCodeAt(i++) - 63; res |= (b & 31) << shift; shift += 5; } while (b >= 32 && i < s.length);
      const v = res & 1 ? ~(res >> 1) : res >> 1;
      if (k === 0) lat += v; else lon += v;
    }
    out.push([lat / 1e5, lon / 1e5]);
  }
  return out;
}

/* ---------------- opening hours ---------------- */
const DAYC = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"];
function defaultOpen(t) {
  if (t.dow === 0 || HOLIDAYS.has(t.date)) return false;
  const m = t.h * 60 + t.mi; return m >= 510 && m < 1140; // 08:30–19:00
}
function isOpen(oh, t = nowTR()) {
  oh = (oh || "").trim();
  if (!oh) return defaultOpen(t);
  if (oh === "24/7") return true;
  try { const r = parseOH(oh, t); return r == null ? defaultOpen(t) : r; } catch { return defaultOpen(t); }
}
function parseOH(oh, t) {
  const idx = (t.dow + 6) % 7, hol = HOLIDAYS.has(t.date), now = t.h * 60 + t.mi;
  let result = null, matched = false;
  for (let rule of oh.split(";")) {
    rule = rule.trim(); if (!rule) continue;
    const m = rule.match(/^((?:(?:Mo|Tu|We|Th|Fr|Sa|Su|PH)(?:\s*-\s*(?:Mo|Tu|We|Th|Fr|Sa|Su))?\s*,?\s*)+)?\s*(.*)$/);
    if (!m) return null;
    const days = (m[1] || "").trim(), times = m[2].trim();
    if (days) {
      let hit = false, hasPH = false;
      for (const tok of days.split(",").map((x) => x.trim()).filter(Boolean)) {
        if (tok === "PH") { hasPH = true; if (hol) hit = true; continue; }
        const [a0, b0] = tok.split("-").map((x) => x.trim());
        const a = DAYC.indexOf(a0), b = b0 ? DAYC.indexOf(b0) : a;
        if (a < 0 || b < 0) return null;
        if (a <= b ? idx >= a && idx <= b : idx >= a || idx <= b) hit = true;
      }
      if (hol && !hasPH) hit = false;
      if (!hit) continue;
    }
    matched = true;
    if (/^(off|closed)$/i.test(times)) { result = false; continue; }
    if (!times || times === "24/7") { result = true; continue; }
    let open = false;
    for (const r of times.split(",")) {
      const tm = r.trim().match(/^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\+?$/);
      if (!tm) return null;
      const s = (+tm[1] % 24) * 60 + +tm[2], e = +tm[3] * 60 + +tm[4];
      open = open || (e > s ? now >= s && now < e : now >= s || now < e);
    }
    result = open;
  }
  return matched ? result : false;
}

/* ---------------- data ---------------- */
async function loadStatic(city) {
  if (S.stat[city] || S.statErr[city] === "loading") return;
  S.statErr[city] = "loading"; render();
  try {
    const d = await getJSON(RAW + `static-${city}.json`, 60000);
    S.stat[city] = d; S.statErr[city] = null;
    S.lines[city] = groupLines(d.routes || []);
  } catch (e) { S.statErr[city] = "Şehir verisi indirilemedi. Bağlantınızı kontrol edip sayfayı yenileyin."; }
  render();
}

function parseTrDate(info) {
  const m = info.match(/(\d{1,2})\s+(\p{L}+)/u); if (!m) return null;
  const mon = MONTHS.indexOf(m[2].toLocaleLowerCase("tr")) + 1; if (!mon) return null;
  const t = nowTR(); let y = t.y;
  const cand = (yy) => `${yy}-${pad(mon)}-${pad(+m[1])}`;
  let d = cand(y);
  if (d > nowTR(t.ms + 180 * 86400e3).date) d = cand(y - 1);
  if (d < nowTR(t.ms - 180 * 86400e3).date) d = cand(y + 1);
  return d;
}

async function fanatik(city) {
  const html = await getText(`https://www.fanatik.com.tr/nobetci-eczaneler/${city}/`, 20000);
  const doc = new DOMParser().parseFromString(html, "text/html");
  const info = (doc.querySelector(".ecz-module-info-text")?.textContent || "").replace(/\s+/g, " ").trim();
  const txt = (el, sel) => (el.querySelector(sel)?.textContent || "").replace(/\s+/g, " ").trim();
  const items = [...doc.querySelectorAll(".ecz-module-pharmacy-item")].map((el) => {
    const q = (el.querySelector('a[href*="query="]')?.getAttribute("href") || "").match(/query=(-?[\d.]+),(-?[\d.]+)/);
    return {
      n: txt(el, ".ecz-module-pharmacy-name"),
      d: txt(el, ".ecz-module-pharmacy-subtitle").split("/").pop().trim(),
      a: txt(el, ".ecz-module-pharmacy-location").replace(/^Adres:\s*/, ""),
      p: txt(el, ".ecz-module-pharmacy-contact").replace(/\D/g, ""),
      lat: q ? +q[1] : null, lon: q ? +q[2] : null,
    };
  }).filter((x) => x.n);
  return { source: "fanatik.com.tr", info, dutyDate: parseTrDate(info) || dutyDate(city), items };
}

async function loadDuty(city, force) {
  const want = dutyDate(city);
  const cur = S.duty[city];
  if (!force && cur && cur.dutyDate === want && Date.now() - cur.at < 20 * 60e3) return;
  if (S.dutyErr[city] === "loading") return;
  S.dutyErr[city] = "loading"; render();
  let best = null; const errs = [];
  for (const fn of [() => fanatik(city), async () => ({ ...(await getJSON(RAW + `duty-${city}.json`)), backup: true })]) {
    try {
      const d = await fn();
      if (!d.items || d.items.length < 3) { errs.push("boş liste"); continue; }
      if (d.dutyDate === want) { best = d; break; }
      best = best || d;
    } catch (e) { errs.push(e.message); }
  }
  if (best) { S.duty[city] = { ...best, at: Date.now() }; S.dutyErr[city] = null; }
  else S.dutyErr[city] = "Nöbetçi eczane listesi alınamadı. Biraz sonra tekrar deneyin.";
  render();
}

async function loadQuakes(force) {
  if (!force && S.quakes && Date.now() - S.quakes.at < 3 * 60e3) return;
  if (S.quakeErr === "loading") return;
  S.quakeErr = "loading"; render();
  let items = [], sources = [], days = 1;
  try {
    const a = await getJSON(RAW + "quakes-afad.json");
    if (Date.now() - Date.parse(a.updated) < 6 * 3600e3) {
      items = a.items.map((q) => ({ ...q, ms: Date.parse(q.t), src: "AFAD" })); sources.push("AFAD"); days = 7;
    }
  } catch {}
  try {
    const k = await getJSON("https://api.orhanaydogdu.com.tr/deprem/kandilli/live?limit=500", 15000);
    const latest = items.length ? Math.max(...items.map((q) => q.ms)) : 0;
    const kan = (k.result || []).map((q) => ({
      id: q.earthquake_id, m: q.mag, d: q.depth, p: q.title, lat: q.geojson.coordinates[1], lon: q.geojson.coordinates[0],
      ms: Date.parse(q.date_time.replace(" ", "T") + "+03:00"), src: "Kandilli",
    })).filter((q) => q.ms > latest + 60e3);
    if (kan.length || !items.length) sources.push("Kandilli");
    items = items.concat(kan);
  } catch {}
  items.sort((a, b) => b.ms - a.ms);
  if (items.length) { S.quakes = { items, sources, days, at: Date.now() }; S.quakeErr = null; }
  else S.quakeErr = "Deprem verisi alınamadı. Biraz sonra tekrar deneyin.";
  render();
}

async function loadOutages() {
  if (S.outages || S.outageErr === "loading") return;
  S.outageErr = "loading"; render();
  try { S.outages = await getJSON(RAW + "outages-ankara.json"); S.outageErr = null; }
  catch { S.outageErr = "ASKİ listesi alınamadı."; }
  render();
}

function lineColor(c, mode) {
  const fallback = (MODES[mode] || ["", "#455A64"])[1];
  if (!c) return fallback;
  c = c.trim();
  if (/^[0-9a-f]{3}([0-9a-f]{3})?$/i.test(c)) c = "#" + c;
  if (!window.CSS || !CSS.supports("color", c)) return fallback;
  // beyaz/çok açık renkler beyaz yazıyla okunmaz
  const el = document.createElement("i"); el.style.color = c; document.body.appendChild(el);
  const m = getComputedStyle(el).color.match(/\d+/g); el.remove();
  if (m && 0.299 * m[0] + 0.587 * m[1] + 0.114 * m[2] > 190) return fallback;
  return c;
}

function groupLines(routes) {
  const g = new Map();
  for (const r of routes) {
    const key = r.ref ? `${r.m}|${r.ref}` : `${r.m}|#${r.id}`;
    if (!g.has(key)) g.set(key, []);
    g.get(key).push(r);
  }
  const sortKey = (ref) => { const m = ref.match(/^(\D*)(\d+)(.*)$/); return m ? m[1] + m[2].padStart(5, "0") + m[3] : "~" + ref; };
  return [...g.values()].map((v) => {
    v.sort((a, b) => b.s.length - a.s.length);
    const f = v[0];
    return {
      key: `${f.m}|${f.ref}|${f.id}`, mode: f.m, ref: f.ref, variants: v,
      title: f.ref || (MODES[f.m] || ["Hat"])[0],
      sub: f.f && f.t ? `${f.f} – ${f.t}` : (f.n && f.n !== f.ref ? f.n : f.o),
      color: lineColor((v.find((r) => r.c) || {}).c, f.m),
      search: norm(v.map((r) => `${r.n} ${r.f} ${r.t} ${r.s.map((s) => s[0]).join(" ")}`).join(" ")),
    };
  }).sort((a, b) => (MODE_ORDER.indexOf(a.mode) - MODE_ORDER.indexOf(b.mode)) || sortKey(a.ref).localeCompare(sortKey(b.ref), "tr"));
}

/* ---------------- derived lists ---------------- */
// OSM'de amenity=pharmacy olarak etiketlenmiş kozmetik/medikal mağazalar
const NOT_PHARMACY = /watsons|gratis|rossmann|kozmetik|parf[üu]m|flormar|medikal|optik|aktar|herbal|sağlık ürünleri/i;
function openPharmacies(includeRegular) {
  const c = S.city, t = nowTR(), out = [];
  const d = S.duty[c];
  const valid = d && d.dutyDate === dutyDate(c, t);
  const until = CITIES[c].change.map(pad).join(":");
  if (d) for (const p of d.items) out.push({
    name: p.n, addr: [p.a, p.d].filter(Boolean).join(", "), phone: p.p, lat: p.lat, lon: p.lon, duty: true,
    note: valid ? `Nöbetçi, sabah ${until}'a kadar açık` : "Nöbet listesi güncel olmayabilir", dist: myDist(p.lat, p.lon),
  });
  if (includeRegular && S.stat[c]) {
    const dutyPts = out.filter((x) => x.lat != null);
    for (const [name, lat, lon, oh, addr, phone] of S.stat[c].pharmacies) {
      if (NOT_PHARMACY.test(name) || !isOpen(oh, t)) continue;
      if (dutyPts.some((x) => dist(x.lat, x.lon, lat, lon) < 60)) continue;
      out.push({ name, addr, phone, lat, lon, duty: false,
        note: oh ? `Çalışma saati: ${oh}` : "Olağan saatlerde açık (19:00'a kadar, tahmini)", dist: myDist(lat, lon) });
    }
  }
  return S.loc ? out.sort((a, b) => (a.dist ?? 1e12) - (b.dist ?? 1e12))
    : out.sort((a, b) => (b.duty - a.duty) || a.addr.localeCompare(b.addr, "tr"));
}

function hospitals(erOnly) {
  const h = (S.stat[S.city]?.hospitals || []).map(([name, lat, lon, kind, em, phone, addr, opType]) => ({
    name, lat, lon, kind, em, phone, addr, opType, er: em === "yes" || em === "likely", dist: myDist(lat, lon),
  })).filter((x) => !erOnly || x.er);
  return h.sort((a, b) => ((a.dist ?? 1e12) - (b.dist ?? 1e12)) || ((a.kind === "hospital" ? 0 : 1) - (b.kind === "hospital" ? 0 : 1)));
}
const kindLabel = (h) => h.kind === "hospital"
  ? ({ private: "Özel hastane", government: "Devlet hastanesi", public: "Devlet hastanesi" }[h.opType] || "Hastane")
  : h.kind === "asm" ? "Aile sağlığı merkezi" : "Poliklinik, sağlık merkezi";

function nearbyStops(radius = 600) {
  if (!S.loc) return [];
  const m = new Map();
  for (const l of S.lines[S.city] || []) for (const r of l.variants) for (const [name, lat, lon] of r.s) {
    const d = dist(S.loc.lat, S.loc.lon, lat, lon); if (d > radius) continue;
    const k = name || `${lat.toFixed(4)},${lon.toFixed(4)}`;
    const cur = m.get(k);
    if (!cur) m.set(k, { name, lat, lon, d, lines: new Set([l]) });
    else { cur.lines.add(l); if (d < cur.d) Object.assign(cur, { lat, lon, d }); }
  }
  return [...m.values()].sort((a, b) => a.d - b.d).slice(0, 12);
}

/* ---------------- icons & small pieces ---------------- */
const IC = {
  call: '<svg viewBox="0 0 24 24"><path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"/></svg>',
  pin: '<svg viewBox="0 0 24 24"><path d="M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z"/><circle cx="12" cy="9.5" r="2.5"/></svg>',
  loc: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3.2"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3"/><circle cx="12" cy="12" r="7"/></svg>',
  out: '<svg viewBox="0 0 24 24"><path d="M14 4h6v6M20 4l-9 9M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/></svg>',
};
const callBtn = (p) => (p ? `<a class="btn" href="${telUrl(p)}">${IC.call}Ara</a>` : "");
const mapBtn = (lat, lon, solid = true) => (lat != null ? `<a class="btn${solid ? " solid" : ""}" href="${mapsUrl(lat, lon)}" target="_blank" rel="noopener">${IC.pin}Haritada aç</a>` : "");
const loading = (t) => `<p class="loading">${esc(t)}</p>`;
const errBox = (t) => `<p class="err">${esc(t)}</p>`;
function locHint(what) {
  if (S.loc) return "";
  return `<button class="hint" data-act="locate">${IC.loc}<span>${S.locating ? "Konum alınıyor…" : `Konumunu paylaş, ${esc(what)}`}</span></button>`;
}
const chip = (label, pressed, act, val) => `<button class="chip" aria-pressed="${pressed}" data-act="${act}" data-val="${esc(val)}">${esc(label)}</button>`;
const search = (id, ph, val) => `<input class="search" type="search" id="${id}" placeholder="${esc(ph)}" value="${esc(val)}" autocomplete="off">`;
const moreBtn = (act, left) => (left > 0 ? `<button class="btn more" data-act="${act}">${left} tane daha göster</button>` : "");

/* ---------------- views ---------------- */
function viewChooser() {
  return `<div class="status"><h2 class="clock">Hangi şehirdesin?</h2></div>
  <p class="muted">Şehrim şimdilik İstanbul, Ankara ve Eskişehir'de çalışıyor. Seçimini bu tarayıcı hatırlar.</p>
  <div class="controls">${Object.entries(CITIES).map(([k, c]) => `<button class="btn solid" data-act="city" data-val="${k}" style="font-size:20px;padding:12px 20px">${c.title}</button>`).join("")}
  <button class="btn" data-act="locate-city" style="font-size:18px;padding:12px 18px">${IC.loc}Konumumdan bul</button></div>`;
}

function viewEczane() {
  const c = S.city, t = nowTR(), ui = S.ui;
  const regular = defaultOpen(t), hol = HOLIDAYS.has(t.date);
  const until = CITIES[c].change.map(pad).join(":");
  const d = S.duty[c];
  const all = openPharmacies(!ui.dutyOnly);
  const q = norm(ui.phQ.trim());
  const list = q ? all.filter((p) => norm(p.name + " " + p.addr).includes(q)) : all;
  const nearest = S.loc && all.find((p) => p.dist != null);
  let h = `<div class="status"><span class="clock">${pad(t.h)}:${pad(t.mi)}</span>
    <p><b>${DAYS[t.dow]}${hol ? ", resmi tatil" : ""}.</b> ${regular
      ? "Eczanelerin çoğu açık; nöbetçiler ayrıca işaretli."
      : `Normal eczaneler kapalı. Açık olanlar nöbetçi eczaneler, sabah ${until}'a kadar.`}</p></div>`;
  if (nearest) h += `<article class="board"><div class="glyph" aria-hidden="true">E</div><div class="txt">
      <div class="kicker">${nearest.duty ? "En yakın nöbetçi eczane" : "En yakın açık eczane"}</div>
      <h2>${esc(nearest.name)}</h2><p class="addr">${esc(nearest.addr)}</p>
      <div class="dist">${fmtDist(nearest.dist)} uzakta</div>
      <div class="controls actions">${mapBtn(nearest.lat, nearest.lon)}${callBtn(nearest.phone)}</div></div></article>`;
  else h += locHint("en yakın açık eczaneyi göstereyim");
  h += `<div class="controls">${chip("Şu an açık", !ui.dutyOnly, "dutyOnly", "0")}${chip("Yalnız nöbetçiler", ui.dutyOnly, "dutyOnly", "1")}</div>`;
  h += search("phQ", "Eczane, mahalle veya ilçe ara", ui.phQ);
  if (S.dutyErr[c] === "loading" && !d) h += loading("Nöbetçi eczaneler yükleniyor…");
  else if (S.dutyErr[c] && S.dutyErr[c] !== "loading") h += errBox(S.dutyErr[c]);
  if (d && d.dutyDate !== dutyDate(c, t)) h += errBox(`Kaynaktaki nöbet listesi ${d.dutyDate} tarihli; bugünün listesi henüz yayımlanmamış olabilir.`);
  if (!ui.dutyOnly && S.statErr[c] === "loading") h += loading("Eczane konumları yükleniyor…");
  h += `<p class="muted small">${list.length} eczane${S.loc ? ", yakından uzağa" : ""}</p>`;
  if (list.length) h += `<div class="list">${list.slice(0, ui.phN).map((p) => `<div class="row">
      <span class="mark ${p.duty ? "ecz" : "open"}" aria-hidden="true">E</span>
      <div><h3>${esc(p.name)}<span class="tag ${p.duty ? "ecz" : "dim"}">${p.duty ? "Nöbetçi" : "Açık"}</span></h3>
        ${p.addr ? `<p class="addr">${esc(p.addr)}</p>` : ""}<p class="meta">${esc(p.note)}</p></div>
      <div class="right">${fmtDist(p.dist)}</div>
      <div class="actions">${mapBtn(p.lat, p.lon, false)}${callBtn(p.phone)}</div></div>`).join("")}</div>`;
  h += moreBtn("phMore", list.length - ui.phN);
  h += `<p class="note">Nöbet listesi: ${d ? esc(d.source) + (d.backup ? " (yedek kopya)" : "") + (d.info ? `, ${esc(d.info)}` : "") : "fanatik.com.tr"}.
    Eczane konumları ve saatleri © OpenStreetMap katkıcıları. Saati kayıtlı olmayan eczaneler için Pazartesi–Cumartesi 08:30–19:00 varsayılır; gitmeden önce aramanız iyi olur.</p>`;
  return h;
}

function viewHastane() {
  const c = S.city, ui = S.ui;
  const all = hospitals(ui.erOnly);
  const q = norm(ui.hoQ.trim());
  const list = q ? all.filter((x) => norm(x.name + " " + x.addr).includes(q)) : all;
  const nearestEr = S.loc && hospitals(true)[0];
  let h = `<a class="btn call112" href="tel:112">${IC.call}Acil durumda 112'yi ara</a>`;
  if (nearestEr) h += `<article class="board hos"><div class="glyph" aria-hidden="true">H</div><div class="txt">
      <div class="kicker">En yakın acil servis</div><h2>${esc(nearestEr.name)}</h2>
      <p class="addr">${esc(kindLabel(nearestEr))}${nearestEr.addr ? ", " + esc(nearestEr.addr) : ""}</p>
      <div class="dist">${fmtDist(nearestEr.dist)} uzakta</div>
      <div class="controls actions">${mapBtn(nearestEr.lat, nearestEr.lon)}${callBtn(nearestEr.phone)}</div></div></article>`;
  else h += locHint("en yakın acil servisi göstereyim");
  h += `<div class="controls">${chip("Acil servisi olanlar", ui.erOnly, "erOnly", "1")}${chip("Tüm sağlık kuruluşları", !ui.erOnly, "erOnly", "0")}</div>`;
  h += search("hoQ", "Hastane veya semt ara", ui.hoQ);
  if (S.statErr[c] === "loading") h += loading("Sağlık kuruluşları yükleniyor…");
  else if (S.statErr[c]) h += errBox(S.statErr[c]);
  h += `<p class="muted small">${list.length} kuruluş${S.loc ? ", yakından uzağa" : ""}</p>`;
  if (list.length) h += `<div class="list">${list.slice(0, ui.hoN).map((x) => `<div class="row">
      <span class="mark ${x.kind === "hospital" ? "hos" : "cli"}" aria-hidden="true">H</span>
      <div><h3>${esc(x.name)}${x.em === "yes" ? '<span class="tag hos">Acil</span>' : x.em === "likely" ? '<span class="tag dim">Acil olabilir</span>' : ""}</h3>
        <p class="meta">${esc(kindLabel(x))}</p>${x.addr ? `<p class="addr">${esc(x.addr)}</p>` : ""}</div>
      <div class="right">${fmtDist(x.dist)}</div>
      <div class="actions">${mapBtn(x.lat, x.lon, false)}${callBtn(x.phone)}</div></div>`).join("")}</div>`;
  h += moreBtn("hoMore", list.length - ui.hoN);
  h += `<p class="note">Kaynak: © OpenStreetMap katkıcıları. "Acil olabilir": devlet hastanesi, ama acil servisi haritada kayıtlı değil. Hayati bir durumda beklemeden 112'yi arayın.</p>`;
  return h;
}

const badge = (l) => `<span class="line-badge" style="background:${esc(l.color)}">${esc(l.title.slice(0, 14))}</span>`;

function viewUlasim() {
  const c = S.city, ui = S.ui, lines = S.lines[c] || [];
  const q = norm(ui.trQ.trim());
  const list = lines.filter((l) => (!ui.trMode || l.mode === ui.trMode) && (!q || norm(l.ref).startsWith(q) || l.search.includes(q)));
  const modes = [...new Set(lines.map((l) => l.mode))];
  let h = search("trQ", "Hat numarası, güzergâh veya durak ara", ui.trQ);
  h += `<div class="controls">${chip(`Tümü (${lines.length})`, !ui.trMode, "trMode", "")}${modes.map((m) =>
    chip(`${(MODES[m] || [m])[0]} (${lines.filter((l) => l.mode === m).length})`, ui.trMode === m, "trMode", m)).join("")}</div>`;
  if (S.statErr[c] === "loading") h += loading("Hatlar yükleniyor… İstanbul verisi birkaç MB, ilk açılış biraz sürebilir.");
  else if (S.statErr[c]) h += errBox(S.statErr[c]);
  if (!q && !ui.trMode && lines.length) {
    h += `<h3 class="sec">Yakınındaki duraklar</h3>`;
    if (!S.loc) h += locHint("yakınındaki durakları ve geçen hatları göstereyim");
    else {
      const ns = nearbyStops();
      h += ns.length ? `<div class="list">${ns.map((s) => `<div class="row"><span class="mark open" aria-hidden="true">D</span>
        <div><h3>${esc(s.name || "İsimsiz durak")}</h3></div><div class="right">${fmtDist(s.d)}</div>
        <div class="badges">${[...s.lines].slice(0, 24).map((l) => `<button class="line-badge" data-act="line" data-val="${esc(l.key)}" style="background:${esc(l.color)}">${esc(l.title.slice(0, 14))}</button>`).join("")}</div></div>`).join("")}</div>`
        : `<p class="muted">600 m içinde kayıtlı durak bulunamadı.</p>`;
    }
    h += `<h3 class="sec">Tüm hatlar</h3>`;
  }
  if (list.length) h += `<div class="list">${list.slice(0, ui.trN).map((l) => `<div class="row link" data-act="line" data-val="${esc(l.key)}" tabindex="0" role="button">
      ${badge(l)}<div><h3 style="font-size:17px">${esc(l.sub || l.variants[0].n || "")}</h3>
      <p class="meta">${esc((MODES[l.mode] || [l.mode])[0])}${l.variants[0].s.length ? `, ${l.variants[0].s.length} durak` : ""}${l.variants[0].o ? `, ${esc(l.variants[0].o)}` : ""}</p></div><span></span></div>`).join("")}</div>`;
  else if (lines.length) h += `<p class="muted">Aramanla eşleşen hat yok.</p>`;
  h += moreBtn("trMore", list.length - ui.trN);
  if (S.stat[c]) h += `<p class="note">Hat ve durak verisi © OpenStreetMap katkıcıları (${esc(S.stat[c].updated)}). Topluluk verisidir; bazı hatlar eksik olabilir. Sefer saatleri için İETT, EGO veya Eskişehir Büyükşehir'in kendi uygulamalarına bakın.</p>`;
  return h;
}

function viewKesinti() {
  const c = S.city, ui = S.ui, t = nowTR();
  const nowIso = `${t.date}T${pad(t.h)}:${pad(t.mi)}`;
  let h = "";
  if (c === "ankara") {
    h += `<h3 class="sec" style="margin-top:0!important">Su kesintileri, ASKİ</h3>`;
    h += search("ouQ", "İlçe veya mahalle ara", ui.ouQ);
    if (S.outageErr === "loading") h += loading("Kesintiler yükleniyor…");
    else if (S.outageErr) h += errBox(S.outageErr);
    const q = norm(ui.ouQ.trim());
    const items = (S.outages?.items || []).filter((o) => !q || norm(`${o.district} ${o.places} ${o.detail}`).includes(q))
      .map((o) => ({ ...o, past: o.end && o.end < nowIso, active: (!o.start || o.start <= nowIso) && (!o.end || o.end >= nowIso) }))
      .sort((a, b) => (a.past - b.past) || String(a.start).localeCompare(String(b.start)));
    const f = (s) => (s ? `${s.slice(8, 10)}.${s.slice(5, 7)} ${s.slice(11, 16)}` : "?");
    if (S.outages && !items.length) h += `<p class="muted">Şu an listelenmiş su kesintisi yok.</p>`;
    if (items.length) h += `<div class="list">${items.map((o) => `<div class="row"><span class="mark" style="background:var(--out)" aria-hidden="true">S</span>
      <div><h3>${esc(o.district)}<span class="tag ${o.active ? "now" : o.past ? "dim" : "soon"}">${o.active ? "Şu an" : o.past ? "Bitti" : "Yaklaşan"}</span></h3>
      <p class="meta">${esc(o.kind)}, ${f(o.start)} ile ${f(o.end)} arası</p>
      ${o.places ? `<p class="addr"><b>Etkilenen yerler:</b> ${esc(o.places)}</p>` : ""}
      ${o.detail ? `<details class="small"><summary>Ayrıntı</summary>${esc(o.detail)}</details>` : ""}</div><span></span></div>`).join("")}</div>`;
    if (S.outages) h += `<p class="note">Kaynak: ASKİ, saatlik kopyalanır (son: ${esc(S.outages.updated.replace("T", " "))}).</p>`;
    h += `<h3 class="sec">Diğer kaynaklar</h3>`;
  } else {
    h += `<p class="muted" style="max-width:62ch">${esc(CITIES[c].title)} için su ve elektrik dağıtım şirketleri kesintileri adres sorgusu veya doğrulama ile gösteriyor, bu yüzden burada listelenemiyor. Resmi sayfalardan bakabilirsin:</p>`;
  }
  h += `<div class="list">${OUTAGE_LINKS[c].map(([u, title, sub, url]) => `<a class="row link" href="${url}" target="_blank" rel="noopener" style="text-decoration:none">
    <span class="mark" style="background:${u === "Su" ? "var(--out)" : "var(--qk)"}" aria-hidden="true">${u === "Su" ? "S" : "E"}</span>
    <div><h3 style="font-size:18px">${esc(title)}</h3><p class="meta">${esc(u)}, ${esc(sub)}</p></div><span class="right">${IC.out}</span></a>`).join("")}</div>`;
  h += `<p class="note">Elektrik arızalarını 186'dan bildirebilirsin.</p>`;
  return h;
}

const qColor = (m) => (m >= 5 ? "#B71C1C" : m >= 4 ? "#E53935" : m >= 3 ? "#E07000" : m >= 2 ? "#C9A100" : "#8A94A3");
function ago(ms) {
  const m = Math.floor((Date.now() - ms) / 60e3);
  return m < 1 ? "az önce" : m < 60 ? `${m} dk önce` : m < 1440 ? `${Math.floor(m / 60)} sa önce` : `${Math.floor(m / 1440)} gün önce`;
}
function viewDeprem() {
  const c = S.city, ui = S.ui;
  const ref = S.loc || CITIES[c];
  const items = (S.quakes?.items || []).map((q) => ({ ...q, dist: dist(ref.lat, ref.lon, q.lat, q.lon) }))
    .filter((q) => q.m >= ui.qkM && (!ui.qkR || q.dist <= ui.qkR * 1000));
  let h = `<p class="muted">Son ${S.quakes?.days === 7 ? "7 gün" : "24 saat"}, ${S.loc ? "konumuna" : esc(CITIES[c].title) + " merkezine"} uzaklık.</p>`;
  h += `<div class="controls">${[100, 200, 500, 0].map((r) => chip(r ? `${r} km` : "Tüm Türkiye", ui.qkR === r, "qkR", r)).join("")}</div>`;
  h += `<div class="controls">${[1, 2, 3, 4].map((m) => chip(`${m}.0 ve üstü`, ui.qkM === m, "qkM", m)).join("")}</div>`;
  if (S.quakeErr === "loading" && !S.quakes) h += loading("Depremler yükleniyor…");
  else if (S.quakeErr && S.quakeErr !== "loading") h += errBox(S.quakeErr);
  if (S.quakes && !items.length) h += `<p class="muted">Bu filtrelere uyan deprem yok.</p>`;
  if (items.length) h += `<div class="list">${items.slice(0, 200).map((q) => {
    const tt = nowTR(q.ms);
    return `<div class="row"><span class="mag" style="background:${qColor(q.m)}">${q.m.toFixed(1)}</span>
      <div><h3 style="font-size:17px">${esc(q.p)}</h3>
      <p class="meta">${ago(q.ms)}, ${pad(tt.d)}.${pad(tt.mo)} ${pad(tt.h)}:${pad(tt.mi)}, derinlik ${(+q.d).toFixed(1)} km</p></div>
      <div class="right">${fmtDist(q.dist)}</div></div>`;
  }).join("")}</div>`;
  h += `<p class="note">Kaynak: ${esc((S.quakes?.sources || ["AFAD", "Kandilli"]).join(" ve "))}. AFAD verisi saatlik kopyalanır, en yeni depremler Kandilli Rasathanesi'nden anlık gelir. Değerler ön değerlendirmedir.</p>`;
  return h;
}

/* ---------------- maps ---------------- */
let bigMap = null, bigLayer = null, sheetMap = null;
function tiles(m) {
  L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
  }).addTo(m);
}
function meMarker(layer) {
  if (S.loc) L.circleMarker([S.loc.lat, S.loc.lon], { radius: 8, color: "#fff", weight: 3, fillColor: "#1E88E5", fillOpacity: 1 })
    .bindPopup("Buradasın").addTo(layer);
}
function popup(title, lines, lat, lon, phone) {
  return `<b>${esc(title)}</b><br>${lines.filter(Boolean).map(esc).join("<br>")}<br>
    <a href="${mapsUrl(lat, lon)}" target="_blank" rel="noopener">Haritada aç</a>${phone ? ` &nbsp; <a href="${telUrl(phone)}">Ara</a>` : ""}`;
}
let bigMapCity = null, bigMapHadLoc = false;
function drawBigMap() {
  if (!window.L) return;
  const c = S.city, cc = CITIES[c];
  if (!bigMap) {
    bigMap = L.map("bigmap", { preferCanvas: true, zoomControl: true }).setView([cc.lat, cc.lon], 12);
    tiles(bigMap); bigLayer = L.layerGroup().addTo(bigMap);
  }
  setTimeout(() => bigMap.invalidateSize(), 0);
  if (bigMapCity !== c || (S.loc && !bigMapHadLoc)) {
    const here = S.loc && dist(S.loc.lat, S.loc.lon, cc.lat, cc.lon) < 80000;
    bigMap.setView(here ? [S.loc.lat, S.loc.lon] : [cc.lat, cc.lon], here ? 14 : 11);
    bigMapCity = c; bigMapHadLoc = !!S.loc;
  }
  bigLayer.clearLayers();
  if ($("#m-ho").checked) for (const h of hospitals(true)) {
    L.circleMarker([h.lat, h.lon], { radius: 8, color: "#fff", weight: 2, fillColor: "#1F5FAD", fillOpacity: 1 })
      .bindPopup(popup(h.name, [kindLabel(h), fmtDist(h.dist)], h.lat, h.lon, h.phone)).addTo(bigLayer);
  }
  if ($("#m-ph").checked) for (const p of openPharmacies(true)) {
    if (p.lat == null) continue;
    L.circleMarker([p.lat, p.lon], { radius: p.duty ? 8 : 5, color: "#fff", weight: p.duty ? 2 : 1, fillColor: p.duty ? "#D7182A" : "#2B7A55", fillOpacity: 1 })
      .bindPopup(popup(p.name, [p.duty ? "Nöbetçi" : "Açık", p.addr, fmtDist(p.dist)], p.lat, p.lon, p.phone)).addTo(bigLayer);
  }
  meMarker(bigLayer);
}

function openLine(key) {
  const l = (S.lines[S.city] || []).find((x) => x.key === key); if (!l) return;
  let vi = 0;
  const sheet = $("#sheet");
  $("#sheet-title").innerHTML = `${badge(l)}<span>${esc((MODES[l.mode] || [l.mode])[0])}</span>`;
  const draw = () => {
    const r = l.variants[vi], col = l.color;
    let near = -1;
    if (S.loc && r.s.length) { let best = 1e12; r.s.forEach((s, i) => { const d = dist(S.loc.lat, S.loc.lon, s[1], s[2]); if (d < best) { best = d; near = i; } }); }
    $("#sheet-body").innerHTML = `<div id="linemap" class="map"></div>
      <h3 style="font-size:20px">${esc(r.n || l.sub)}</h3>${r.o ? `<p class="muted small" style="margin:0">${esc(r.o)}</p>` : ""}
      ${l.variants.length > 1 ? `<div class="controls">${l.variants.map((v, i) => chip(v.t ? `Yön: ${v.t}` : v.n || `Yön ${i + 1}`, i === vi, "variant", i)).join("")}</div>` : ""}
      ${near >= 0 ? `<p class="hint" style="cursor:default">${IC.loc}<span>Sana en yakın durak: <b>${esc(r.s[near][0] || `${near + 1}. durak`)}</b>, ${fmtDist(dist(S.loc.lat, S.loc.lon, r.s[near][1], r.s[near][2]))}</span></p>` : ""}
      ${r.s.length ? `<ol class="stops" style="--c:${esc(col)}">${r.s.map((s, i) => `<li class="${i === 0 || i === r.s.length - 1 ? "end" : ""}${i === near ? " near" : ""}">${esc(s[0] || `Durak ${i + 1}`)}</li>`).join("")}</ol>`
        : `<p class="muted">Bu hattın durak listesi OpenStreetMap'te yok; güzergâh haritada.</p>`}`;
    if (sheetMap) { sheetMap.remove(); sheetMap = null; }
    if (!window.L) return;
    sheetMap = L.map("linemap", { preferCanvas: true });
    tiles(sheetMap);
    // görünüm katmanlardan ÖNCE ayarlanmalı (Leaflet canvas, görünümsüz haritada hata veriyor)
    const paths = r.g.map(decodePolyline);
    const b = L.latLngBounds([...paths.flat(), ...r.s.map((s) => [s[1], s[2]])]);
    if (b.isValid()) sheetMap.fitBounds(b, { padding: [20, 20] }); else sheetMap.setView([CITIES[S.city].lat, CITIES[S.city].lon], 12);
    for (const pts of paths) L.polyline(pts, { color: col, weight: 5, opacity: 0.85 }).addTo(sheetMap);
    r.s.forEach((s, i) => L.circleMarker([s[1], s[2]], { radius: i === 0 || i === r.s.length - 1 ? 7 : 5, color: "#fff", weight: 2,
      fillColor: i === near ? "#1E88E5" : col, fillOpacity: 1 }).bindPopup(esc(s[0] || `Durak ${i + 1}`)).addTo(sheetMap));
    meMarker(sheetMap);
  };
  sheet.hidden = false; document.body.style.overflow = "hidden";
  sheet.onclick = (e) => { const b = e.target.closest('[data-act="variant"]'); if (b) { vi = +b.dataset.val; draw(); } };
  draw();
  $("#sheet-close").focus();
}
function closeSheet() {
  $("#sheet").hidden = true; document.body.style.overflow = "";
  if (sheetMap) { sheetMap.remove(); sheetMap = null; }
}

/* ---------------- render ---------------- */
const VIEWS = { eczane: viewEczane, hastane: viewHastane, ulasim: viewUlasim, kesinti: viewKesinti, deprem: viewDeprem };
let renderQueued = false;
function render() {
  if (renderQueued) return; renderQueued = true;
  requestAnimationFrame(() => { renderQueued = false; doRender(); });
}
function doRender() {
  document.querySelectorAll(".cities button").forEach((b) => b.setAttribute("aria-checked", String(b.dataset.city === S.city)));
  document.querySelectorAll(".tab").forEach((a) => { if (a.dataset.tab === S.tab) a.setAttribute("aria-current", "page"); else a.removeAttribute("aria-current"); });
  const lb = $("#locate"); lb.classList.toggle("on", !!S.loc); lb.classList.toggle("busy", S.locating);
  document.querySelectorAll(".view").forEach((v) => (v.hidden = v.dataset.view !== S.tab));
  if (!S.city) {
    $("#view-harita").hidden = true;
    const v = $("#view-eczane"); v.hidden = false; v.innerHTML = viewChooser(); return;
  }
  document.title = `Şehrim, ${CITIES[S.city].title}`;
  if (S.tab === "harita") { drawBigMap(); return; }
  const v = $(`#view-${S.tab}`);
  const active = document.activeElement, focusId = active && active.id, caret = active && active.selectionStart;
  v.innerHTML = VIEWS[S.tab]();
  if (focusId && v.querySelector("#" + focusId)) { const el = v.querySelector("#" + focusId); el.focus(); try { el.setSelectionRange(caret, caret); } catch {} }
}

function ensureData() {
  const c = S.city; if (!c) return;
  loadStatic(c);
  if (S.tab === "eczane" || S.tab === "harita") loadDuty(c);
  if (S.tab === "deprem") loadQuakes();
  if (S.tab === "kesinti" && c === "ankara") loadOutages();
}

function setCity(c) {
  if (!CITIES[c]) return;
  S.city = c; store.set("sehrim.city", c);
  Object.assign(S.ui, { phN: 40, hoN: 40, trN: 60, trMode: "" });
  ensureData(); render();
}
function setTab(t) {
  if (!VIEWS[t] && t !== "harita") t = "eczane";
  S.tab = t; ensureData(); render();
  window.scrollTo(0, 0);
}

function locate(pickCity) {
  if (!navigator.geolocation) { alert("Tarayıcın konum paylaşmayı desteklemiyor."); return; }
  S.locating = true; render();
  navigator.geolocation.getCurrentPosition((p) => {
    S.loc = { lat: p.coords.latitude, lon: p.coords.longitude }; S.locating = false;
    if (pickCity || !S.city) {
      const c = Object.entries(CITIES).sort((a, b) => dist(S.loc.lat, S.loc.lon, a[1].lat, a[1].lon) - dist(S.loc.lat, S.loc.lon, b[1].lat, b[1].lon))[0][0];
      setCity(c);
    }
    render();
  }, (e) => {
    S.locating = false; render();
    alert(e.code === 1 ? "Konum izni verilmedi. Tarayıcı ayarlarından bu site için konuma izin verebilirsin." : "Konum alınamadı. Biraz sonra tekrar dene.");
  }, { enableHighAccuracy: true, timeout: 12000, maximumAge: 60000 });
}

/* ---------------- events ---------------- */
document.addEventListener("click", (e) => {
  const cb = e.target.closest(".cities button"); if (cb) { setCity(cb.dataset.city); return; }
  const a = e.target.closest("[data-act]"); if (!a) return;
  const v = a.dataset.val, ui = S.ui;
  switch (a.dataset.act) {
    case "city": setCity(v); break;
    case "locate": locate(false); break;
    case "locate-city": locate(true); break;
    case "dutyOnly": ui.dutyOnly = v === "1"; ui.phN = 40; break;
    case "erOnly": ui.erOnly = v === "1"; ui.hoN = 40; break;
    case "trMode": ui.trMode = v; ui.trN = 60; break;
    case "qkR": ui.qkR = +v; break;
    case "qkM": ui.qkM = +v; break;
    case "phMore": ui.phN += 60; break;
    case "hoMore": ui.hoN += 60; break;
    case "trMore": ui.trN += 100; break;
    case "line": openLine(v); return;
    default: return;
  }
  render();
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !$("#sheet").hidden) closeSheet();
  if ((e.key === "Enter" || e.key === " ") && e.target.matches('.row.link[data-act="line"]')) { e.preventDefault(); openLine(e.target.dataset.val); }
});
document.addEventListener("input", (e) => {
  const id = e.target.id; const map = { phQ: "phN", hoQ: "hoN", trQ: "trN" };
  if (id in S.ui) { S.ui[id] = e.target.value; if (map[id]) S.ui[map[id]] = map[id] === "trN" ? 60 : 40; render(); }
});
$("#locate").addEventListener("click", () => locate(false));
$("#sheet-close").addEventListener("click", closeSheet);
$("#m-ph").addEventListener("change", drawBigMap);
$("#m-ho").addEventListener("change", drawBigMap);
window.addEventListener("hashchange", () => setTab(location.hash.slice(1)));
document.addEventListener("visibilitychange", () => { if (!document.hidden) { ensureData(); render(); } });
setInterval(() => { if (!document.hidden) { if (S.city && S.tab === "eczane") loadDuty(S.city); render(); } }, 60e3);

/* ---------------- start ---------------- */
S.city = CITIES[store.get("sehrim.city")] ? store.get("sehrim.city") : null;
S.tab = location.hash.slice(1) || "eczane";
if (!VIEWS[S.tab] && S.tab !== "harita") S.tab = "eczane";
if (navigator.permissions?.query) navigator.permissions.query({ name: "geolocation" }).then((p) => { if (p.state === "granted") locate(false); }).catch(() => {});
ensureData(); render();
