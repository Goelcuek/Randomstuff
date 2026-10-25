package com.goelcuek.sehrim

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Veri kaynakları:
 *  - Sabit veri (eczaneler + hatlar, OpenStreetMap): GitHub Actions haftalık üretir → sehrim-data dalı.
 *  - Nöbetçi eczaneler: önce doğrudan telefondan (fanatik.com.tr / Eskişehir Eczacı Odası),
 *    olmazsa GitHub Actions'ın saatlik ürettiği yedek JSON.
 */
class Repo(private val ctx: Context) {
    companion object {
        const val RAW = "https://raw.githubusercontent.com/Goelcuek/Randomstuff/sehrim-data/"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val STATIC_MAX_AGE = 7L * 24 * 3600 * 1000
        private const val DUTY_MAX_AGE = 30L * 60 * 1000
    }

    private fun cacheFile(name: String) = File(ctx.filesDir, name)

    private fun http(url: String, timeoutMs: Int = 20000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9")
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    // ---------------- Sabit veri ----------------

    /** Önbellek → (gerekirse) ağ → APK içindeki gömülü veri sırasıyla dener. */
    suspend fun loadStatic(city: City, forceRefresh: Boolean = false): StaticData = withContext(Dispatchers.IO) {
        val f = cacheFile("static-${city.slug}.json")
        val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > STATIC_MAX_AGE
        if (stale || forceRefresh) {
            runCatching {
                val txt = http(RAW + "static-${city.slug}.json", 45000)
                val parsed = parseStatic(txt)       // bozuk veri kaydedilmesin
                f.writeText(txt)
                return@withContext parsed
            }
        }
        if (f.exists()) runCatching { return@withContext parseStatic(f.readText()) }
        val asset = runCatching { ctx.assets.open("static-${city.slug}.json").bufferedReader().use { it.readText() } }.getOrNull()
            ?: throw RuntimeException("Hat verisi henüz hazır değil ya da indirilemedi. Biraz sonra yenile (↻) düğmesine basın.")
        parseStatic(asset)
    }

    private fun parseStatic(txt: String): StaticData {
        val o = JSONObject(txt)
        val ph = o.getJSONArray("pharmacies")
        val pharmacies = ArrayList<Pharmacy>(ph.length())
        for (i in 0 until ph.length()) {
            val a = ph.getJSONArray(i)
            pharmacies += Pharmacy(a.getString(0), a.getDouble(1), a.getDouble(2), a.optString(3), a.optString(4), a.optString(5))
        }
        val rs = o.getJSONArray("routes")
        val routes = ArrayList<Route>(rs.length())
        for (i in 0 until rs.length()) {
            val r = rs.getJSONObject(i)
            val st = r.getJSONArray("s")
            val stops = ArrayList<Stop>(st.length())
            for (j in 0 until st.length()) {
                val s = st.getJSONArray(j)
                stops += Stop(s.getString(0), s.getDouble(1), s.getDouble(2))
            }
            val g = r.getJSONArray("g")
            routes += Route(
                r.getLong("id"), r.getString("m"), r.optString("ref"), r.optString("n"), r.optString("f"),
                r.optString("t"), r.optString("o"), r.optString("c"), stops, List(g.length()) { g.getString(it) },
            )
        }
        val hs = o.optJSONArray("hospitals") ?: JSONArray()
        val hospitals = List(hs.length()) { i ->
            val a = hs.getJSONArray(i)
            Hospital(a.getString(0), a.getDouble(1), a.getDouble(2), a.optString(3), a.optString(4), a.optString(5),
                a.optString(6), a.optString(7))
        }
        return StaticData(o.optString("city"), o.optString("updated"), pharmacies, routes, hospitals)
    }

    // ---------------- Nöbetçi eczaneler ----------------

    suspend fun loadDuty(city: City, forceRefresh: Boolean = false): DutyData = withContext(Dispatchers.IO) {
        val f = cacheFile("duty-${city.slug}.json")
        val wanted = Hours.currentDutyDate(city).toString()
        if (!forceRefresh && f.exists()) {
            runCatching {
                val d = dutyFromJson(JSONObject(f.readText()))
                if (d.dutyDate == wanted && System.currentTimeMillis() - d.fetchedAt < DUTY_MAX_AGE) return@withContext d
            }
        }
        val errors = mutableListOf<String>()
        val attempts: List<() -> DutyData> = buildList {
            add { scrapeFanatik(city) }
            if (city == City.ESKISEHIR) add { scrapeEskisehirEo() }
            add { fromGithub(city) }
        }
        var fallback: DutyData? = null
        for (a in attempts) {
            val d = runCatching(a).onFailure { errors += it.message ?: it.toString() }.getOrNull() ?: continue
            if (d.items.size < 3) { errors += "${d.source}: boş liste"; continue }
            if (d.dutyDate != wanted) { if (fallback == null) fallback = d; continue }
            f.writeText(dutyToJson(d).toString())
            return@withContext d
        }
        fallback?.let { return@withContext it }
        if (f.exists()) runCatching { return@withContext dutyFromJson(JSONObject(f.readText())) }
        throw RuntimeException("Nöbetçi eczane listesi alınamadı (${errors.joinToString("; ")})")
    }

    private fun clean(s: String) = android.text.Html.fromHtml(s, 0).toString().replace(Regex("\\s+"), " ").trim()

    /**
     * fanatik.com.tr sayfasındaki tarih metni, örn. "25 Ekim Pazar akşamından 26 Ekim Pazartesi sabahına kadar."
     * İlk tarihi nöbet günü kabul ederiz.
     */
    private fun parseTurkishDate(info: String): String? {
        val months = listOf("ocak", "şubat", "mart", "nisan", "mayıs", "haziran", "temmuz", "ağustos", "eylül", "ekim", "kasım", "aralık")
        val m = Regex("""(\d{1,2})\s+(\p{L}+)""").find(info) ?: return null
        val mon = months.indexOf(m.groupValues[2].lowercase(java.util.Locale("tr"))) + 1
        if (mon == 0) return null
        val today = Hours.now().toLocalDate()
        var d = LocalDate.of(today.year, mon, m.groupValues[1].toInt())
        if (d.isAfter(today.plusDays(180))) d = d.minusYears(1)
        if (d.isBefore(today.minusDays(180))) d = d.plusYears(1)
        return d.toString()
    }

    private fun scrapeFanatik(city: City): DutyData {
        val t = http("https://www.fanatik.com.tr/nobetci-eczaneler/${city.fanatikSlug}/")
        val info = Regex("""ecz-module-info-text">(.*?)<""", RegexOption.DOT_MATCHES_ALL).find(t)?.groupValues?.get(1)?.let(::clean) ?: ""
        val items = t.split("class=\"ecz-module-pharmacy-item\"").drop(1).mapNotNull { blk ->
            fun g(p: String) = Regex(p, RegexOption.DOT_MATCHES_ALL).find(blk)?.groupValues?.get(1)?.let(::clean) ?: ""
            val name = g("""pharmacy-name">(.*?)</div>""").ifBlank { return@mapNotNull null }
            val sub = g("""pharmacy-subtitle">(.*?)</div>""")
            val c = Regex("""query=(-?[0-9.]+),(-?[0-9.]+)""").find(blk)
            DutyPharmacy(
                name = name,
                district = sub.substringAfterLast('/').trim(),
                address = g("""<b>Adres:</b>(.*?)</div>"""),
                phone = g("""<b>Telefon:</b>(.*?)</div>""").filter(Char::isDigit),
                lat = c?.groupValues?.get(1)?.toDoubleOrNull(),
                lon = c?.groupValues?.get(2)?.toDoubleOrNull(),
            )
        }
        val date = parseTurkishDate(info) ?: Hours.currentDutyDate(city).toString()
        return DutyData("fanatik.com.tr", info, date, System.currentTimeMillis(), items)
    }

    private fun scrapeEskisehirEo(): DutyData {
        val city = City.ESKISEHIR
        val t = http("https://www.eskisehireo.org.tr/eskisehir-nobetci-eczaneler")
        var window = ""
        val items = t.split("class=\"col-md-12 nobetci\"").drop(1).mapNotNull { blk ->
            val m = Regex("""<strong>(.*?)</strong>\s*-\s*(.*?)</h4>""", RegexOption.DOT_MATCHES_ALL).find(blk) ?: return@mapNotNull null
            val c = Regex("""maps\?q=(-?[0-9.]+),(-?[0-9.]+)""").find(blk)
            Regex("""([0-9.]+ [0-9:]+ / [0-9.]+ [0-9:]+) nöbetçidir""").find(blk)?.let { window = it.groupValues[1] }
            DutyPharmacy(
                name = clean(m.groupValues[1]).lowercase(java.util.Locale("tr")).split(' ')
                    .joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(java.util.Locale("tr")) } },
                district = clean(m.groupValues[2]),
                address = Regex("""fa-home'></i>(.*?)<br""", RegexOption.DOT_MATCHES_ALL).find(blk)?.groupValues?.get(1)?.let(::clean) ?: "",
                phone = Regex("""tel:([0-9]+)""").find(blk)?.groupValues?.get(1) ?: "",
                lat = c?.groupValues?.get(1)?.toDoubleOrNull(),
                lon = c?.groupValues?.get(2)?.toDoubleOrNull(),
            )
        }
        val date = Regex("""(\d{2})\.(\d{2})\.(\d{4})""").find(window)?.destructured?.let { (d, mo, y) -> "$y-$mo-$d" }
            ?: Hours.currentDutyDate(city).toString()
        return DutyData("eskisehireo.org.tr (yalnızca merkez)", if (window.isNotEmpty()) "$window nöbetçi" else "", date, System.currentTimeMillis(), items)
    }

    private fun fromGithub(city: City): DutyData {
        val d = dutyFromJson(JSONObject(http(RAW + "duty-${city.slug}.json")))
        return d.copy(source = d.source + " · yedek", fetchedAt = System.currentTimeMillis())
    }

    private fun dutyFromJson(o: JSONObject): DutyData {
        val arr = o.getJSONArray("items")
        val items = List(arr.length()) { i ->
            val x = arr.getJSONObject(i)
            DutyPharmacy(
                x.getString("n"), x.optString("d"), x.optString("a"), x.optString("p"),
                if (x.isNull("lat")) null else x.optDouble("lat"), if (x.isNull("lon")) null else x.optDouble("lon"),
            )
        }
        return DutyData(o.optString("source"), o.optString("info"), o.optString("dutyDate"), o.optLong("fetchedAt", 0), items)
    }

    private fun dutyToJson(d: DutyData) = JSONObject().apply {
        put("source", d.source); put("info", d.info); put("dutyDate", d.dutyDate); put("fetchedAt", d.fetchedAt)
        put("items", JSONArray().apply {
            d.items.forEach { p ->
                put(JSONObject().apply {
                    put("n", p.name); put("d", p.district); put("a", p.address); put("p", p.phone)
                    put("lat", p.lat ?: JSONObject.NULL); put("lon", p.lon ?: JSONObject.NULL)
                })
            }
        })
    }
}
