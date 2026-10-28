const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, locale: 'tr-TR',
    geolocation: { latitude: 39.9208, longitude: 32.8541 }, permissions: ['geolocation'] });
  const p = await ctx.newPage(); const log = [];
  p.on('pageerror', (e) => log.push('ERR ' + e.message));
  p.on('requestfinished', async (r) => { const u = r.url(); if (/fanatik|orhanaydogdu|raw\.github/.test(u)) { const s = (await r.response())?.status(); log.push(`${s} ${u.slice(0, 90)}`); } });
  p.on('requestfailed', (r) => log.push('FAIL ' + r.url().slice(0, 90) + ' ' + r.failure()?.errorText));
  await p.goto('https://goelcuek.github.io/Randomstuff/sehrim/'); await p.waitForTimeout(8000);
  await p.screenshot({ path: 'out/1-eczane.png' });
  log.push('board: ' + (await p.locator('.board h2').first().textContent().catch(() => 'none')));
  log.push('source: ' + (await p.locator('.note').first().textContent().catch(() => '')).slice(0, 120));
  for (const t of ['hastane', 'deprem', 'harita']) { await p.click(`.tab[data-tab="${t}"]`); await p.waitForTimeout(t === 'harita' ? 5000 : 4000); await p.screenshot({ path: `out/2-${t}.png` }); }
  log.push('quake note: ' + (await p.locator('#view-deprem .note').textContent().catch(() => '')).slice(0, 100));
  await p.click('.cities button[data-city="istanbul"]'); await p.click('.tab[data-tab="eczane"]'); await p.waitForTimeout(9000);
  await p.screenshot({ path: 'out/3-ist.png' });
  log.push('ist count: ' + (await p.locator('#view-eczane p.small').first().textContent().catch(() => '')));
  require('fs').writeFileSync('out/log.txt', log.join('\n'));
  await b.close();
})();
