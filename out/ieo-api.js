// ieoroot web kapısının (api/nobetci-eczane-web) istemcisi — görünümün tek veri yolu. Jeton burada tutulur:
// ilk jeton sayfa kabuğundan gelir; 401 `token` alınırsa bir kez `session` ile yenilenip istek tekrarlanır.

const TIMEOUT_MS = 10000;

/** Kapı hatası: `code` kapının sözleşmesi (WebGateError), ağ/zaman aşımı `network`. */
export class GateError extends Error {
  constructor(code, message, status = 0) {
    super(message || 'Beklenmeyen bir hata oluştu.');
    this.code = code;
    this.status = status;
  }
}

export function createApi(gateUrl, initialToken) {
  let token = initialToken || '';
  let refreshing = null;

  async function call(action, { method = 'GET', params = {}, body = null } = {}) {
    const url = `${gateUrl}?${new URLSearchParams({ action, ...params })}`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
    try {
      const response = await fetch(url, {
        method,
        credentials: 'same-origin',
        headers: { Accept: 'application/json', ...(token ? { 'X-Web-Token': token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
        body: body ? JSON.stringify(body) : undefined,
        signal: controller.signal,
      });
      const payload = await response.json().catch(() => null);
      if (!payload || typeof payload !== 'object') throw new GateError('unavailable', 'Sunucuya şu an ulaşılamıyor.', response.status);
      if (!payload.success) throw new GateError(payload.error?.code || 'unavailable', payload.error?.message, response.status);
      return payload;
    } catch (error) {
      if (error instanceof GateError) throw error;
      throw new GateError('network', 'Lütfen internet bağlantınızı kontrol edin.');
    } finally {
      clearTimeout(timer);
    }
  }

  async function refreshToken() {
    refreshing = refreshing || call('session').then((r) => { token = r.data.token; }).finally(() => { refreshing = null; });
    return refreshing;
  }

  async function read(action, options) {
    if (!token) await refreshToken();
    try {
      return await call(action, options);
    } catch (error) {
      if (error.code !== 'token') throw error;
      await refreshToken();
      return call(action, options);
    }
  }

  return {
    districts: () => read('districts'),
    district: (id) => read('district', { params: { id } }),
    region: (id) => read('region', { params: { id } }),
    nearby: (lat, lng, radiusKm) => read('nearby', { method: 'POST', body: { lat, lng, radiusKm } }),
    meta: () => read('meta'),
    report: (payload) => read('report', { method: 'POST', body: payload }),
    /** "Ara" (#16): listedeki bilete karşılık `{tel, tel2}`. */
    phone: (p) => read('phone', { method: 'POST', body: { regionId: p.regionId, sicil: String(p.sicil), ticket: p.phoneTicket } }),
    /** `{kind: district|region|app, id?, e?}` → `{url, png}`; QR'ın adresini sunucu kurar (#12). */
    qr: (params) => read('qr', { params }),
  };
}
