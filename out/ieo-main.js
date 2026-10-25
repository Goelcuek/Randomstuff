// Görünümün giriş noktası: önyükleme verisi → durum → çizim + harita. İş mantığı yok (PLAN §8.3): veri kapıdan,
// gün kapının `dutyDate`'i, mesafe servisin `mesafe`'si. Olaylar `data-action` ile tek tabloda (ACTIONS).
import { createApi } from './api.js';
import { createStore } from './store.js';
import { createMap } from './map.js';
import { locate, permissionDenied, watchPermission } from './geo.js';
import { parseLocation, urlFor } from './router.js';
import { dutyDayText, esc } from './format.js';
import { icon } from './icons.js';
import { nextRadius, PROVINCES } from './policy.js';
import { forgetPharmacy, lastPharmacy, recentDistricts, rememberDistrict, rememberPharmacy } from './history.js';
import { msUntilFirstEnd, syncServerTime } from './dutyClock.js';
import { renderHome } from './ui/home.js';
import { renderDistricts } from './ui/districts.js';
import { renderResults } from './ui/results.js';
import { renderAsk } from './ui/ask.js';
import { renderReport, reportPayload, reportScreen, validateReport } from './ui/report.js';
import { pickerList, renderDesktop } from './ui/desktop.js';
import { fullText, listShare, pharmacyShare, renderQrModal, renderShareMenu, renderToast } from './ui/share.js';
import { appIntentUrl, renderAppModal, renderBand } from './ui/app-promo.js';
import { renderPhoneMenu } from './ui/phone.js';
import { bandDismissed, dismissBand } from './history.js';
import { createSheetDrag } from './sheet.js';

const boot = JSON.parse(document.getElementById('boot').textContent);
const APP = boot.app || {};
const desktop = window.matchMedia('(min-width: 1024px)');
const app = document.getElementById('app');
const live = document.getElementById('live');
const store = createStore({ districts: boot.districts || [], dutyDate: boot.dutyDate || null, appBandHidden: bandDismissed('desktop') });
const api = createApi(boot.gateUrl, boot.token);
const map = createMap(document.getElementById('map'), {
  apiKey: boot.mapKey || '',
  onSelect: (index) => select(index),
  onDeselect: () => { if (store.get().selected >= 0) store.update({ selected: -1 }); },   // boş haritaya dokunuş (mobil gibi)
  onFail: () => { document.getElementById('map-fallback').hidden = false; },
});
const sheetDrag = createSheetDrag({
  enabled: () => !desktop.matches,
  onSettle: () => syncMap(store.get(), null, true),   // kart küçülünce/büyüyünce seçili eczane görünen alana göre yeniden ortalanır
});

let depth = 0;
let loadSeq = 0;
let askSeq = 0;

// ── Gezinme ──────────────────────────────────────────────────────────────────

function entryOf(s) {
  return { screen: s.screen, source: s.source, view: s.view, notice: s.notice, depth };
}

function navigate(next, { replace = false } = {}) {
  const prevSource = store.get().source;
  store.update({ notice: null, radiusOpen: false, search: '', pickerOpen: false, pickerActive: -1, selected: -1, ...next });
  const s = store.get();
  if (!replace) depth += 1;
  history[replace ? 'replaceState' : 'pushState'](entryOf(s), '', urlFor(s.screen, s.source));
  syncAppArgument();
  if (s.screen === 'results' && (next.source !== prevSource || s.status !== 'ready')) loadResults();
  if (s.screen === 'districts') ensureDistricts();
}

function restore(entry) {
  depth = entry.depth || 0;
  const prevSource = store.get().source;
  store.update({ asking: false, radiusOpen: false, selected: -1, screen: entry.screen, source: entry.source, view: entry.view || 'list', notice: entry.notice || null });
  syncAppArgument();
  if (entry.screen === 'results' && JSON.stringify(entry.source) !== JSON.stringify(prevSource)) loadResults();
}

/** iOS Safari bandı (`apple-itunes-app` metası) uygulamaya geçerli adresi verir; adres değişince güncellenir. */
function syncAppArgument() {
  const meta = document.querySelector('meta[name="apple-itunes-app"]');
  if (meta && APP.iosId) meta.setAttribute('content', `app-id=${APP.iosId}, app-argument=${window.location.href}`);
}

function goDistricts(notice, options) {
  const city = store.get().source?.type === 'district' ? districtById(store.get().source.id)?.ilId ?? 34 : store.get().city;
  navigate({ screen: 'districts', notice, city }, options);
}

// ── Veri ─────────────────────────────────────────────────────────────────────

const districtById = (id) => store.get().districts.find((d) => d.ilceId === id);

async function ensureDistricts() {
  if (store.get().districts.length) return;
  try {
    const r = await api.districts();
    syncServerTime(r.meta?.serverTime);
    store.update({ districts: r.data, dutyDate: r.meta?.dutyDate || store.get().dutyDate });
  } catch {
    /* ilçe listesi yok: ekran "İlçe bulunamadı" der; sonraki girişte yeniden denenir */
  }
}

function request(source, radiusKm) {
  if (source.type === 'district') return api.district(source.id);
  if (source.type === 'region') return api.region(source.id);
  return api.nearby(source.lat, source.lng, radiusKm);
}

async function loadResults() {
  const { source, radiusKm } = store.get();
  if (!source) return;
  if (source.locate) {
    // Mobil Yakınımdakiler: konum alınamadı → ekranda kalır, "Tekrar dene" yeniden ister (#16, A-7 yeni hâli).
    loadSeq += 1;
    store.update({ status: 'error', items: [], selected: -1, error: { code: 'location', reason: source.locate } });
    return;
  }
  const seq = ++loadSeq;
  const started = performance.now();
  store.update({ status: 'loading', items: [], selected: -1, error: null });
  try {
    const r = await request(source, radiusKm);
    if (seq !== loadSeq) return;
    syncServerTime(r.meta?.serverTime);
    const items = Array.isArray(r.data) ? r.data : [];
    const { pendingSicil: pending, view } = store.get();
    // Kendiliğinden seçim yok (#16): yalnız paylaşılan / son eczane bağlantısındaki eczane seçili gelir.
    const selected = pending ? items.findIndex((p) => p.sicil === pending) : -1;
    store.update({ status: 'ready', items, selected, pendingSicil: null, dutyDate: r.meta?.dutyDate || store.get().dutyDate });
    announce(items.length ? `${items.length} nöbetçi eczane listelendi.` : 'Nöbetçi eczane bulunamadı.');
    if (selected >= 0) rememberPharmacy(items[selected]);
    if (desktop.matches && items.length) ensureAppQr();   // liste sonu uygulama kartı (oturumda bir kez)
    window.__nobetTiming = { action: source.type, ms: Math.round(performance.now() - started) };
  } catch (error) {
    if (seq !== loadSeq) return;
    if (error.code === 'out_of_area') {
      locationFailed('out_of_area', { replace: true });
      return;
    }
    store.update({ status: 'error', error: { code: error.code, message: error.message } });
  }
}

// ── Konum (A-7, #16) ──────────────────────────────────────────────────────────
// Masaüstü: açılışta kendiliğinden istenir; ret / zaman aşımı / kutu dışı → ilçe bölmesi + uyarı kartı (sayfa açık).
// Mobil (< 1024): açılış anasayfa, istek yalnız "Yakınımdakiler"e dokununca; alınamazsa Yakınımdakiler ekranında
// "konum izni gerekiyor" durumu + "Tekrar dene" (her denemede yeniden istenir) — ilçe ekranına kendiliğinden gidilmez.

/** Şu an mobilde konumu bekleyen Yakınımdakiler ekranı mı (yeniden deneme aynı geçmiş kaydının yerine geçer). */
const onLocateScreen = () => Boolean(store.get().screen === 'results' && store.get().source?.locate);

function locationFailed(reason, options = {}) {
  if (desktop.matches) {
    goDistricts(reason, options);
    return;
  }
  navigate({ screen: 'results', view: 'list', source: { type: 'nearby', locate: reason } }, { replace: options.replace || onLocateScreen() });
}

/** Konum reddi ekranda mı (masaüstü: ilçe bölmesindeki not; mobil: Yakınımdakiler'deki durum bloğu). */
const showingDenied = (s) => (s.screen === 'districts' && s.notice === 'denied') || (s.screen === 'results' && s.source?.locate === 'denied');

const DENIED_HINT = 'Konum izni kapalı — adres çubuğundaki konum simgesinden izin verin';

async function startNearby() {
  const ticket = ++askSeq;
  if (await permissionDenied()) {
    // Tarayıcı reddedilmiş izni bir daha sormaz; "Tekrar dene" aynı ekrana dönmek yerine ne yapılacağını söyler.
    if (showingDenied(store.get())) toast(DENIED_HINT);
    else locationFailed('denied');
    return;
  }
  store.update({ asking: true });
  try {
    const pos = await locate();
    if (ticket !== askSeq || !store.get().asking) return;
    store.update({ asking: false });
    navigate({ screen: 'results', view: 'list', source: { type: 'nearby', lat: pos.lat, lng: pos.lng, fixed: false } }, { replace: onLocateScreen() });
  } catch (failure) {
    if (ticket !== askSeq || !store.get().asking) return;
    store.update({ asking: false });
    if (failure.reason === 'denied' && showingDenied(store.get())) toast(DENIED_HINT);
    else locationFailed(failure.reason || 'unavailable');
  }
}

// ── Hata bildir (#9) ─────────────────────────────────────────────────────────

/** Kapının en erken gönderim süresi (bot süzgeci); kullanıcı daha hızlıysa istek o ana kadar bekletilir. */
const REPORT_MIN_MS = 3000;

/** Bağlam: `pharmacy` = seçili eczane + arama + gün; `list` = arama + gün; `general` = yok. */
function reportContext(scope) {
  const s = store.get();
  if (scope === 'general' || s.screen !== 'results' || !s.source) return null;
  const src = s.source;
  const place = src.type === 'nearby' ? { kind: 'nearby' }
    : src.type === 'region' ? { kind: 'region', id: src.id }
    : { kind: 'district', id: src.id, name: districtById(src.id)?.ilceAd || '' };
  const p = scope === 'pharmacy' ? s.items[s.selected] : null;
  const dutyDate = p?.nobetTarih || s.dutyDate || null;
  return { ...(p ? { pharmacy: { sicil: p.sicil, name: String(p.ad || '').trim() } } : {}), place, ...(dutyDate ? { dutyDate } : {}) };
}

const currentScreen = () => reportScreen(store.get(), {
  desktop: desktop.matches, width: window.innerWidth, height: window.innerHeight, dpr: window.devicePixelRatio || 1, path: location.pathname + location.search,
});

function openReport(scope) {
  store.update({
    radiusOpen: false,
    report: { context: reportContext(scope), screen: currentScreen(), type: null, fields: { message: '', name: '', email: '', phone: '', website: '' }, errors: {}, status: 'editing', message: '', openedAt: performance.now() },
  });
}

const patchReport = (patch) => store.update({ report: { ...store.get().report, ...patch } });

/** Kapı kodu → kullanıcı metni (kapının metni zaten Türkçe; ağda kendi metnimiz). */
const REPORT_MESSAGES = {
  already_reported: 'Bu eczane için bugün bildiriminizi aldık. Teşekkür ederiz.',
  network: 'Bildirim gönderilemedi. Lütfen internet bağlantınızı kontrol edip tekrar deneyin.',
};

async function submitReport() {
  const r = store.get().report;
  if (!r || r.status === 'sending') return;
  const errors = validateReport(r);
  if (Object.keys(errors).length) {
    patchReport({ errors, message: '' });
    document.querySelector('#report-form [aria-invalid="true"], #report-form .rf__err')?.closest('label, fieldset')?.querySelector('input, textarea, button')?.focus();
    return;
  }
  patchReport({ errors: {}, message: '', status: 'sending' });
  const wait = REPORT_MIN_MS - (performance.now() - r.openedAt);
  if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait));
  try {
    await api.report(reportPayload(r, performance.now() - r.openedAt));
    patchReport({ status: 'sent', message: 'Bildiriminiz bize ulaştı. Teşekkür ederiz.' });
    announce('Bildiriminiz gönderildi.');
  } catch (error) {
    if (error.code === 'already_reported') {
      patchReport({ status: 'sent', message: REPORT_MESSAGES.already_reported });
      return;
    }
    patchReport({ status: 'editing', message: REPORT_MESSAGES[error.code] || error.message, openedAt: performance.now() - REPORT_MIN_MS });
  }
}

// ── Paylaş · telefona aktar (QR) · uygulama (#12) ────────────────────────────

const PAGE_BASE = `${window.location.origin}${window.location.pathname}`;
const TOAST_MS = 2400;
let toastTimer = 0;
/** Pencere kapanınca odak açan düğmeye döner (yeniden çizimde düğme yenilendiği için seçiciyle). */
let returnFocus = null;

function toast(text) {
  window.clearTimeout(toastTimer);
  store.update({ toast: text });
  toastTimer = window.setTimeout(() => store.update({ toast: null }), TOAST_MS);
}

function currentShare(kind) {
  const s = store.get();
  if (kind === 'pharmacy') {
    const p = s.items[s.selected];
    return p && Number.isInteger(p.ilceId) ? pharmacyShare(p, s.dutyDate, PAGE_BASE) : null;
  }
  return s.source && s.source.type !== 'nearby' ? listShare(s.source, resultsTitle(s).title, s.dutyDate, PAGE_BASE) : null;
}

/** Menünün yeri: düğme ekranın alt yarısındaysa üstüne, değilse altına; sağ kenarı düğmeyle hizalı. */
function menuPosition(el) {
  const r = el.getBoundingClientRect();
  const width = 340;
  const left = Math.max(12, Math.min(r.right - width, window.innerWidth - width - 12));
  return r.top > window.innerHeight / 2 ? { left, bottom: window.innerHeight - r.top + 8 } : { left, top: r.bottom + 8 };
}

async function openShare(el) {
  const share = currentShare(el.dataset.share);
  if (!share) return;
  returnFocus = `[data-action="share"][data-share="${el.dataset.share}"]`;
  const payload = { title: share.title, text: share.text, url: share.url };
  if (navigator.share && (!navigator.canShare || navigator.canShare(payload))) {
    try {
      await navigator.share(payload);
      return;
    } catch (error) {
      if (error?.name === 'AbortError') return;      // kullanıcı sistem menüsünü kapattı
    }
  }
  store.update({ radiusOpen: false, shareMenu: { share, pos: desktop.matches ? menuPosition(el) : null } });
}

// ── Ara (#16): numara listede yok; dokununca kapıdan, eczane başına bilet + sınırla ─────────────────────

/** Kapı "çok hızlı" derse (sayfa jetonu 2 sn'den genç) bu kadar beklenip bir kez yeniden denenir. */
const PHONE_RETRY_WAIT_MS = 2100;
let phoneSeq = 0;
const wait = (ms) => new Promise((resolve) => { window.setTimeout(resolve, ms); });

/** "Ara"ya basılan eczane: alt kart → sonuçtaki satır. */
const callTarget = (el) => store.get().items[Number(el.dataset.index)] || null;

/** Bilet bu jetonun: jeton yenilendiyse (401 → yeni oturum) liste yeni biletlerle yeniden alınır ve aynı eczane bulunur. */
// ── Nöbet bitişi (#17) ──────────────────────────────────────────────────────
// Servis bitmiş nöbeti listeye koymaz; sayfa açık kalırsa ekrandaki ilk bitişte (liste ya da son eczane kartı) yenilenir.
let expiryTimer = 0;
const MAX_TIMER_MS = 6 * 60 * 60 * 1000;   // uzun zamanlayıcı sekme uykusunda kayar: en çok 6 saatte bir yeniden kurulur

const shownDuties = (s) => (s.screen === 'results' ? s.items : s.screen === 'home' ? [lastPharmacy()].filter(Boolean) : []);

function scheduleExpiry(s) {
  window.clearTimeout(expiryTimer);
  const ms = msUntilFirstEnd(shownDuties(s));
  if (ms !== null) expiryTimer = window.setTimeout(refreshExpired, Math.min(ms + 1000, MAX_TIMER_MS));
}

function refreshExpired() {
  const s = store.get();
  if (msUntilFirstEnd(shownDuties(s)) !== 0) return scheduleExpiry(s);
  if (s.screen === 'results' && s.status === 'ready') loadResults();
  else store.update({ clock: Date.now() });   // anasayfa: son eczane kartı `lastPharmacy()` ile düşer
}

async function fetchPhones(p) {
  try {
    return (await api.phone(p)).data;
  } catch (error) {
    if (error.code === 'too_fast') {
      await wait(PHONE_RETRY_WAIT_MS);
      return (await api.phone(p)).data;
    }
    if (error.code !== 'forbidden' || store.get().screen !== 'results') throw error;
    await loadResults();
    const fresh = store.get().items.find((x) => x.sicil === p.sicil);
    if (!fresh?.phoneTicket) throw error;
    return (await api.phone(fresh)).data;
  }
}

async function openPhone(el) {
  const p = callTarget(el);
  if (!p) return;
  const scope = el.dataset.scope;
  returnFocus = el.dataset.index === undefined ? `[data-action="call"][data-scope="${scope}"]` : `[data-action="call"][data-scope="${scope}"][data-index="${el.dataset.index}"]`;
  const base = { name: p.ad, scope, sicil: p.sicil, pos: desktop.matches ? menuPosition(el) : null };
  if (!p.phoneTicket) return;
  const seq = ++phoneSeq;
  store.update({ shareMenu: null, radiusOpen: false, phoneMenu: { ...base, status: 'loading', phones: null } });
  try {
    const phones = await fetchPhones(p);
    if (seq !== phoneSeq || !store.get().phoneMenu) return;
    store.update({ phoneMenu: { ...store.get().phoneMenu, status: 'ready', phones } });
  } catch (error) {
    if (error.code === 'not_found') forgetPharmacy(p.sicil);   // #17: yayında değil (nöbeti bitti) → son eczane kartı silinir
    if (seq !== phoneSeq || !store.get().phoneMenu) return;
    const message = error.code === 'network' || error.code === 'unavailable' ? 'Numara alınamadı. İnternet bağlantınızı kontrol edin.' : error.message;
    store.update({ phoneMenu: { ...store.get().phoneMenu, status: 'error', message } });
  }
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    const area = Object.assign(document.createElement('textarea'), { value: text, readOnly: true });
    area.style.cssText = 'position:fixed;opacity:0';
    document.body.appendChild(area);
    area.select();
    const ok = document.execCommand('copy');
    area.remove();
    return ok;
  }
}

async function loadQr(target) {
  const params = { kind: target.kind, ...(target.id ? { id: target.id } : {}), ...(target.pharmacy ? { e: target.pharmacy } : {}) };
  return (await api.qr(params)).data;
}

/** "Bu listeyi telefonunuzda açın": ilçe/bölge listesi ya da (paylaş menüsünden) seçili eczane. */
async function openQr(share) {
  const s = store.get();
  const day = dutyDayText(s.dutyDate);
  const pharmacy = Boolean(share.target.pharmacy);
  const qr = {
    target: share.target, status: 'loading', url: null, png: null,
    eyebrow: [pharmacy ? resultsTitle(s).title : share.label, day].filter(Boolean).join(' · '),
    heading: pharmacy ? 'Bu eczaneyi telefonunuzda açın' : 'Bu listeyi telefonunuzda açın',
  };
  store.update({ shareMenu: null, radiusOpen: false, qr });
  try {
    const image = await loadQr(share.target);
    if (store.get().qr?.target === share.target) store.update({ qr: { ...store.get().qr, status: 'ready', ...image } });
  } catch {
    if (store.get().qr?.target === share.target) store.update({ qr: { ...store.get().qr, status: 'error' } });
  }
}

/** Uygulama QR'ı (mağazaya yönlendiren `app/`): pencere için oturumda bir kez, yalnız masaüstünde (telefonda QR anlamsız; rozetler yeter). */
async function ensureAppQr() {
  if (!desktop.matches || (store.get().appQr && store.get().appQr.status !== 'error')) return;
  store.update({ appQr: { status: 'loading' } });
  try {
    store.update({ appQr: { status: 'ready', ...(await loadQr({ kind: 'app' })) } });
  } catch {
    store.update({ appQr: { status: 'error' } });
  }
}

function closeOverlays() {
  phoneSeq += 1;
  store.update({ shareMenu: null, phoneMenu: null, qr: null, appModal: false });
}

// ── Eylemler ─────────────────────────────────────────────────────────────────

// ── Masaüstü ilçe kutusu (P1, #17) ──────────────────────────────────────────

const searchField = () => document.getElementById('district-search');

/** Kutuyu açar (liste bölmesinin üstüne) ve arama alanına odaklanır; `search` ilk basılan harf olabilir. */
function openPicker(search = '') {
  store.update({ pickerOpen: true, search, pickerActive: search.trim() ? 0 : -1, radiusOpen: false });
  const field = searchField();
  field?.focus();
  field?.setSelectionRange(search.length, search.length);
}

/** Kapanınca il düğmesi kutudaki ilçeye döner (Yalova'ya geçip seçmeden kapatınca "Kartal · İstanbul" ile çelişmesin). */
function pickerClosed() {
  const src = store.get().source;
  const city = src?.type === 'district' ? districtById(src.id)?.ilId : null;
  return { pickerOpen: false, search: '', pickerActive: -1, ...(city ? { city } : {}) };
}

function closePicker() {
  const inside = document.activeElement?.closest?.('.dcombo');
  store.update(pickerClosed());
  if (inside) (document.querySelector('[data-action="picker-open"]') || searchField())?.focus();
}

/** ↑↓ seçili satırı gezdirir, Enter seçer. Yalnız masaüstü kutusunda (mobil ilçe ekranı kendi listesini kullanır). */
function pickerKey(event) {
  const s = store.get();
  const list = pickerList(s);
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    if (!list.length) return;
    event.preventDefault();
    const step = event.key === 'ArrowDown' ? 1 : -1;
    const next = s.pickerActive < 0 ? (step > 0 ? 0 : list.length - 1) : (s.pickerActive + step + list.length) % list.length;
    store.update({ pickerOpen: s.screen === 'results', pickerActive: next });
    document.getElementById(`dopt-${list[next].ilceId}`)?.scrollIntoView({ block: 'nearest' });
  } else if (event.key === 'Enter') {
    const d = list[s.pickerActive] || (list.length === 1 ? list[0] : null);
    if (!d) return;
    event.preventDefault();
    ACTIONS['pick-district']({ dataset: { id: String(d.ilceId) } });
  }
}

function select(index) {
  const s = store.get();
  const p = s.items[index];
  if (!p) return;
  rememberPharmacy(p);
  store.update({ selected: index, radiusOpen: false, view: desktop.matches ? s.view : 'map' });
}

const ACTIONS = {
  near: () => startNearby(),
  'skip-location': () => { askSeq += 1; store.update({ asking: false }); goDistricts(null); },
  districts: () => goDistricts(null),
  back: () => (depth > 0 ? history.back() : navigate({ screen: 'home' }, { replace: true })),
  city: (el) => {
    store.update({ city: Number(el.dataset.city), search: '', pickerActive: -1 });
    if (desktop.matches && store.get().screen === 'results') openPicker();   // başka ilin ilçesi kutudan seçilir
  },
  'clear-search': () => { store.update({ search: '', pickerActive: -1 }); searchField()?.focus(); },
  'picker-open': () => openPicker(),
  'pick-district': (el) => {
    const id = Number(el.dataset.id);
    rememberDistrict(id);
    navigate({ screen: 'results', view: 'list', source: { type: 'district', id } });
  },
  last: () => {
    const last = lastPharmacy();
    if (!last) return;
    navigate({ screen: 'results', view: desktop.matches ? 'list' : 'map', pendingSicil: last.sicil, source: { type: 'district', id: last.ilceId } });
  },
  view: (el) => {
    const view = el.dataset.view;
    store.update({ view, radiusOpen: false });   // Harita'ya geçişte eczane seçilmez (#16)
  },
  select: (el) => select(Number(el.dataset.index)),
  deselect: () => store.update({ selected: -1 }),
  'radius-toggle': () => store.update({ radiusOpen: !store.get().radiusOpen }),
  radius: (el) => { store.update({ radiusKm: Number(el.dataset.km), radiusOpen: false }); loadResults(); },
  widen: () => { store.update({ radiusKm: nextRadius(store.get().radiusKm) }); loadResults(); },
  retry: () => loadResults(),
  'report-open': (el) => { returnFocus = `[data-action="report-open"][data-scope="${el.dataset.scope}"]`; openReport(el.dataset.scope); },
  'report-close': () => store.update({ report: null }),
  'report-type': (el) => patchReport({ type: el.dataset.type, errors: { ...store.get().report.errors, type: undefined, message: undefined } }),
  'report-remove-context': () => patchReport({ context: null }),
  share: (el) => openShare(el),
  'share-copy': async () => {
    const share = store.get().shareMenu?.share;
    if (!share) return;
    const ok = await copyText(fullText(share));
    store.update({ shareMenu: null });
    toast(ok ? 'Bağlantı kopyalandı' : 'Kopyalanamadı — bağlantıyı elle seçin');
  },
  'share-mail': (el) => { store.update({ shareMenu: null }); window.location.href = el.getAttribute('href'); },
  call: (el) => openPhone(el),
  'call-retry': () => {
    const menu = store.get().phoneMenu;
    const el = menu && document.querySelector(returnFocus);
    if (el) openPhone(el);
  },
  'share-qr': () => { const share = store.get().shareMenu?.share; if (share) openQr(share); },
  'qr-open': () => {
    const share = currentShare('list');
    if (!share) return;
    returnFocus = '[data-action="qr-open"]';
    openQr(share);
  },
  'qr-retry': () => { const qr = store.get().qr; if (qr) openQr({ target: qr.target, label: qr.eyebrow.split(' · ')[0] }); },
  'qr-copy': async () => { const url = store.get().qr?.url; if (url) toast((await copyText(url)) ? 'Bağlantı kopyalandı' : 'Kopyalanamadı'); },
  'app-open': () => { returnFocus = '[data-action="app-open"]'; store.update({ shareMenu: null, appModal: true }); ensureAppQr(); },
  'modal-close': () => closeOverlays(),
  'modal-scrim': () => closeOverlays(),
  // Kullanıcı dokunuşu (Chrome `intent:`'i yalnız bununla açar); adres o anki sayfa — gezinmede değişir.
  'band-open': () => { window.location.href = appIntentUrl(APP, window.location.href); },
  'band-close': () => { dismissBand('mobile'); document.getElementById('band')?.replaceChildren(); },
  'dapp-close': () => { dismissBand('desktop'); store.update({ appBandHidden: true }); },
};

// ── Çizim ────────────────────────────────────────────────────────────────────

function resultsTitle(s) {
  const src = s.source;
  if (src.type === 'nearby') return { title: 'Yakınımdakiler', subtitle: src.fixed ? 'Seçilen konuma göre' : 'Konumuna göre' };
  if (src.type === 'region') return { title: s.items.find((p) => p.isOwnRegion)?.ilce || s.items[0]?.ilce || 'Nöbet bölgesi', subtitle: 'Nöbet bölgesi' };
  const d = districtById(src.id);
  return { title: d?.ilceAd || 'İlçe', subtitle: PROVINCES.find((p) => p.id === d?.ilId)?.name || '' };
}

function screenHtml(s) {
  if (desktop.matches) {
    const titles = s.screen === 'results' && s.source ? resultsTitle(s) : { title: '', subtitle: '' };
    return renderDesktop(s, { ...titles, recent: recentDistricts(), app: APP, showApp: !s.appBandHidden && Boolean(APP.name) });
  }
  if (s.screen === 'districts') {
    return renderDistricts(s, { recent: recentDistricts(), currentId: s.source?.type === 'district' ? s.source.id : null });
  }
  if (s.screen === 'results' && s.source) return renderResults(s, resultsTitle(s));
  return renderHome(s, { last: lastPharmacy() });
}

/** Ekranın üstündeki katmanlar: konum kartı (yalnız mobil; masaüstünde liste bölmesinde), hata bildir, QR, uygulama, paylaş menüsü, bildirim. */
function overlaysHtml(s) {
  return (s.asking && !desktop.matches ? renderAsk() : '')
    + (s.report ? renderReport(s.report) : '')
    + (s.qr ? renderQrModal(s.qr, Boolean(APP.linksLive)) : '')
    + (s.appModal ? renderAppModal(APP, s.appQr, desktop.matches) : '')
    + (s.shareMenu ? renderShareMenu(s.shareMenu, { desktop: desktop.matches }) : '')
    + (s.phoneMenu ? renderPhoneMenu(s.phoneMenu, { desktop: desktop.matches }) : '')
    + (s.toast ? renderToast(s.toast) : '');
}

/** Pencere/menü açılıp kapanınca odak yönetimi gerekir (açılışta kapat düğmesine, kapanışta açan düğmeye). */
const overlayKey = (s) => [desktop.matches, Boolean(s.report), s.report?.status, Boolean(s.qr), Boolean(s.appModal), Boolean(s.shareMenu), Boolean(s.phoneMenu)].join('|');

function documentTitle(s) {
  if (s.screen !== 'results' || !s.source) return 'Nöbetçi Eczaneler | İstanbul Eczacı Odası';
  return `${resultsTitle(s).title} Nöbetçi Eczaneler | İstanbul Eczacı Odası`;
}

/** Yeniden çizimde odak, imleç ve kaydırma korunur (arama alanına yazarken liste yenilenir). */
function snapshot() {
  const el = document.activeElement;
  const scrolls = [...app.querySelectorAll('[data-scroll-key]')].map((n) => [n.dataset.scrollKey, n.scrollTop]);
  if (!el || !app.contains(el)) return { scrolls };
  const key = el.dataset.focusKey ? `[data-focus-key="${el.dataset.focusKey}"]`
    : el.id ? `#${CSS.escape(el.id)}`
    : el.dataset.action ? `[data-action="${el.dataset.action}"]${['id', 'index', 'view', 'city', 'km', 'type', 'share', 'scope'].map((k) => (el.dataset[k] ? `[data-${k}="${el.dataset[k]}"]` : '')).join('')}` : null;
  return { key, caret: el.selectionStart ?? null, scrolls };
}

function restoreSnapshot(snap, screenChanged, overlayChanged) {
  if (!screenChanged) snap.scrolls.forEach(([k, top]) => { const n = app.querySelector(`[data-scroll-key="${k}"]`); if (n) n.scrollTop = top; });
  const auto = (screenChanged || overlayChanged) && app.querySelector('[data-autofocus]');
  if (auto) { auto.focus(); return; }
  if (overlayChanged && returnFocus && !app.querySelector('.modal, .sharepop, .report')) {
    const opener = document.querySelector(returnFocus);
    returnFocus = null;
    if (opener) { opener.focus({ preventScroll: true }); return; }
  }
  if (screenChanged || (overlayChanged && app.querySelector('#report-title'))) {
    const heading = app.querySelector('#report-title') || app.querySelector('.hero__title, .dist__title, #results-title');
    if (heading) { heading.setAttribute('tabindex', '-1'); heading.focus({ preventScroll: true }); }
    window.scrollTo(0, 0);
    return;
  }
  const target = snap.key && app.querySelector(snap.key);
  if (!target) return;
  if (target.id === 'results-title') target.setAttribute('tabindex', '-1');
  target.focus({ preventScroll: true });
  if (snap.caret !== null && typeof target.setSelectionRange === 'function') target.setSelectionRange(snap.caret, snap.caret);
}

function render(s, prev) {
  const snap = snapshot();
  // Giriş hareketi yalnız değişen parçada (CSS `.app[data-enter~=…]`): menü/bildirim açılınca ekran yeniden oynamaz.
  app.dataset.enter = !prev ? 'screen sheet overlay toast' : [
    (prev.screen !== s.screen || prev.asking !== s.asking) && 'screen',
    (prev.selected !== s.selected || prev.items !== s.items) && 'sheet',
    overlayKey(prev) !== overlayKey(s) && 'overlay',
    prev.toast !== s.toast && 'toast',
  ].filter(Boolean).join(' ');
  app.innerHTML = screenHtml(s) + overlaysHtml(s);
  document.title = documentTitle(s);
  const day = document.getElementById('topbar-day');
  if (day && s.dutyDate) day.innerHTML = `${icon('moon')}<span>${esc(dutyDayText(s.dutyDate))} gecesi</span>`;
  document.querySelector('[data-action="app-open"]')?.setAttribute('aria-pressed', String(Boolean(s.appModal)));
  restoreSnapshot(snap, !prev || prev.screen !== s.screen || prev.asking !== s.asking, Boolean(prev) && overlayKey(prev) !== overlayKey(s));
  sheetDrag.apply(`${s.view}|${s.items[s.selected]?.sicil ?? ''}`);
}

const MAP_KEYS = ['screen', 'items', 'selected', 'view', 'status', 'source', 'radiusKm'];

function syncMap(s, prev, force = false) {
  const isDesktop = desktop.matches;
  const visible = isDesktop || (s.screen === 'results' && s.view === 'map');
  document.body.dataset.map = !isDesktop && visible ? 'on' : 'off';
  if (!visible || (!force && prev && MAP_KEYS.every((k) => s[k] === prev[k]))) return;
  if (s.screen !== 'results' || s.status !== 'ready') {
    if (s.screen !== 'results') map.idle();
    return;
  }
  const nearby = s.source.type === 'nearby';
  const sheetOpen = s.selected >= 0;
  const margins = isDesktop
    ? [60, 70, sheetOpen ? 360 : 60, 60]          // harita yalnız sağ alanda (T1); alt kart sol altta (~290 px + 52)
    : [170, 30, sheetOpen ? (sheetDrag.collapsed() ? 140 : 340) : 110, 30];
  map.show({
    items: s.items,
    selected: s.selected,
    user: nearby && !s.source.locate ? { lat: s.source.lat, lng: s.source.lng } : null,
    radiusKm: nearby ? s.radiusKm : null,
    margins,
  });
}

function announce(text) {
  live.textContent = '';
  window.setTimeout(() => { live.textContent = text; }, 50);
}

// ── Olaylar ──────────────────────────────────────────────────────────────────

// Eylemler sayfanın her yerinde (üst bardaki "Mobil uygulama", bant dahil). Paylaş menüsü dışarı tıklanınca kapanır;
// pencere zemini yalnız kendisine tıklanınca kapatır (kartın içinden kabarcıklanan tıklama değil).
document.addEventListener('click', (event) => {
  const el = event.target.closest('[data-action]');
  if (store.get().shareMenu && !event.target.closest('.sharepop') && el?.dataset.action !== 'share') store.update({ shareMenu: null });
  if (store.get().phoneMenu && !event.target.closest('.phonepop') && el?.dataset.action !== 'call') { phoneSeq += 1; store.update({ phoneMenu: null }); }
  if (store.get().pickerOpen && !event.target.closest('.dcombo') && el?.dataset.action !== 'city') store.update(pickerClosed());
  if (!el || (el.dataset.action === 'modal-scrim' && event.target !== el)) return;
  const action = ACTIONS[el.dataset.action];
  if (!action) return;
  event.preventDefault();
  action(el);
});

app.addEventListener('input', (event) => {
  if (event.target.id === 'district-search') {
    const search = event.target.value;
    store.update({ search, pickerActive: search.trim() ? 0 : -1, pickerOpen: desktop.matches && store.get().screen === 'results' });
  }
  const field = event.target.dataset.reportField;
  if (field && store.get().report) {
    const r = store.get().report;
    const cleared = field === 'phone' ? { phone: undefined, email: undefined } : { [field]: undefined };   // "e-posta ya da telefon" uyarısı e-postada durur
    patchReport({ fields: { ...r.fields, [field]: event.target.value }, errors: { ...r.errors, ...cleared } });
  }
});

app.addEventListener('submit', (event) => {
  event.preventDefault();
  if (event.target.id === 'report-form') submitReport();
});

// Masaüstü kutusu: boş alana tıklayınca 39 ilçe açılır; kapalı kutu düğmesinde harfe basmak aramayı o harfle başlatır.
app.addEventListener('focusin', (event) => {
  const s = store.get();
  if (event.target.id === 'district-search' && desktop.matches && s.screen === 'results' && !s.pickerOpen) openPicker(s.search);
});

app.addEventListener('keydown', (event) => {
  if (!desktop.matches) return;
  if (event.target.id === 'district-search') pickerKey(event);
  else if (event.target.dataset?.action === 'picker-open' && event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey && event.key !== ' ') {
    event.preventDefault();
    openPicker(event.key);
  }
});

document.addEventListener('keydown', (event) => {
  if (event.key !== 'Escape') return;
  const s = store.get();
  if (s.pickerOpen || (s.search && desktop.matches && event.target.id === 'district-search')) closePicker();
  else if (s.shareMenu || s.phoneMenu || s.qr || s.appModal) closeOverlays();
  else if (s.report && s.report.status !== 'sending') store.update({ report: null });
  else if (s.asking) ACTIONS['skip-location']();
  else if (s.radiusOpen) store.update({ radiusOpen: false });
  else if (s.selected >= 0 && s.screen === 'results') store.update({ selected: -1 });
});

// Eski `#!il/ilce` bağlantısı açık sayfada tıklanırsa (yalnız hash değişir, sayfa yeniden yüklenmez).
window.addEventListener('hashchange', () => {
  const initial = parseLocation(window.location, store.get().districts);
  if (initial) navigate({ ...initial, view: 'list' });
});

window.addEventListener('popstate', (event) => {
  if (event.state && event.state.screen) restore(event.state);
});

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') refreshExpired();
});

desktop.addEventListener('change', () => {
  showBand();
  if (store.get().appModal) ensureAppQr();
  const s = store.get();
  render(s, s);
  syncMap(s, null, true);
  map.resize();
});

store.subscribe((s, prev) => {
  render(s, prev);
  syncMap(s, prev);
  if (s.screen !== prev.screen || s.items !== prev.items || s.clock !== prev.clock) scheduleExpiry(s);
});

// ── Başlangıç ────────────────────────────────────────────────────────────────

/**
 * Bandın platformu; `null` = bant yok: Android/iOS uygulama içi web görünümü, iOS Safari (bandı `apple-itunes-app` metasıyla
 * Safari kendisi çizer). iOS'taki Chrome/Firefox/Edge meta bandını göstermez → bizimki (App Store).
 */
function bandPlatform(ua) {
  if (/Android/i.test(ua)) return /; wv\)/.test(ua) ? null : 'android';
  if (/iPhone|iPad|iPod/i.test(ua)) return /CriOS|FxiOS|EdgiOS|OPiOS/.test(ua) ? 'ios' : null;
  return 'other';
}

/** Mobil web bandı (< 1024): kapatılınca yalnız bu sekmede yok; çerçevede (hastane ekranı, iframe) yok. Pencere genişliği değişince yeniden bakılır. */
function showBand() {
  const slot = document.getElementById('band');
  if (!slot) return;
  const platform = bandPlatform(navigator.userAgent);
  const eligible = platform && window.self === window.top && !desktop.matches && !bandDismissed('mobile') && APP.android;
  slot.innerHTML = eligible ? renderBand(APP, platform) : '';
}

function start() {
  // Kökte (§14 C-11) sayfa eski PWA'nın manifestine bağlı: Chrome'un "ana ekrana ekle" çubuğu uygulama bandıyla yarışmasın.
  window.addEventListener('beforeinstallprompt', (event) => event.preventDefault());
  showBand();
  // Kullanıcı reddedilmiş izni tarayıcıdan açınca liste kendiliğinden gelir ("Tekrar dene"ye basmadan).
  watchPermission((state) => { if (state !== 'denied' && showingDenied(store.get())) startNearby(); });
  const initial = parseLocation(window.location, store.get().districts);
  if (initial) {
    // `?…&e=<sicil>` (paylaşılan eczane): açılışta o eczane seçili; mobilde haritada alt kartla.
    navigate({ ...initial, view: initial.pendingSicil && !desktop.matches ? 'map' : 'list' }, { replace: true });
    return;
  }
  navigate({ screen: 'home' }, { replace: true });
  if (desktop.matches) startNearby();
}

start();
