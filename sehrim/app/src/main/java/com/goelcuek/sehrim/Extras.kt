package com.goelcuek.sehrim

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ------------------------------------------------------------------ Deprem

data class Quake(
    val id: String,
    val magnitude: Double,
    val depthKm: Double,
    val place: String,
    val lat: Double,
    val lon: Double,
    val time: LocalDateTime, // İstanbul saati
)

data class QuakeData(val source: String, val items: List<Quake>, val fetchedAt: Long)

// ------------------------------------------------------------------ Kesinti

data class Outage(
    val district: String,
    val kind: String,          // "Arıza Kaynaklı", "Planlı" ...
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val detail: String,
    val places: String,
    val utility: String,       // "Su" / "Elektrik"
)

data class OutageLink(val title: String, val subtitle: String, val url: String, val utility: String)

object OutageSources {
    /** Doğrudan listelenemeyen (captcha / adres sorgusu / yurtdışı engeli) kaynaklar için resmi sayfalar. */
    fun links(city: City): List<OutageLink> = when (city) {
        City.ISTANBUL -> listOf(
            OutageLink("İSKİ arıza ve kesintiler", "İstanbul su kesintileri", "https://iski.istanbul/", "Su"),
            OutageLink("BEDAŞ kesinti haritası", "Avrupa Yakası elektrik", "https://kesinti.bedas.com.tr/", "Elektrik"),
            OutageLink("AYEDAŞ kesinti sorgulama", "Anadolu Yakası elektrik", "https://www.ayedas.com.tr/", "Elektrik"),
        )
        City.ANKARA -> listOf(
            OutageLink("ASKİ su arızaları", "Resmi sayfa", "https://www.aski.gov.tr/tr/Kesinti.aspx", "Su"),
            OutageLink("Başkent EDAŞ kesinti sorgulama", "Ankara elektrik", "https://www.baskentedas.com.tr/", "Elektrik"),
        )
        City.ESKISEHIR -> listOf(
            OutageLink("Eskişehir su kesintileri", "Güncel duyurular (web araması)",
                "https://www.google.com/search?q=Eski%C5%9Fehir+ESK%C4%B0+su+kesintisi+bug%C3%BCn", "Su"),
            OutageLink("OEDAŞ planlı kesintiler", "Eskişehir elektrik", "https://www.oedas.com.tr/", "Elektrik"),
        )
    }
}

class ExtrasRepo {
    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun http(url: String, timeoutMs: Int = 20000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9")
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun clean(s: String) = android.text.Html.fromHtml(s, 0).toString().replace(Regex("\\s+"), " ").trim()

    // ---------- Depremler: AFAD, yedek Kandilli ----------

    suspend fun quakes(days: Long = 7): QuakeData = withContext(Dispatchers.IO) {
        val errs = mutableListOf<String>()
        runCatching { return@withContext afad(days) }.onFailure { errs += "AFAD: ${it.message}" }
        runCatching { return@withContext kandilli() }.onFailure { errs += "Kandilli: ${it.message}" }
        throw RuntimeException("Deprem verisi alınamadı (${errs.joinToString("; ")})")
    }

    private fun afad(days: Long): QuakeData {
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val end = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5)
        val start = end.minusDays(days)
        val url = "https://servisnet.afad.gov.tr/apigateway/deprem/apiv2/event/filter?start=${start.format(f)}&end=${end.format(f)}&minmag=1&format=json"
        val arr = JSONArray(http(url, 25000))
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            runCatching {
                val utc = LocalDateTime.parse(o.getString("date").take(19))
                Quake(
                    id = "afad-" + o.optString("eventID"),
                    magnitude = o.getString("magnitude").toDouble(),
                    depthKm = o.optString("depth").toDoubleOrNull() ?: 0.0,
                    place = o.optString("location"),
                    lat = o.getString("latitude").toDouble(),
                    lon = o.getString("longitude").toDouble(),
                    time = utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(Hours.TZ).toLocalDateTime(),
                )
            }.getOrNull()
        }.sortedByDescending { it.time }
        return QuakeData("AFAD", items, System.currentTimeMillis())
    }

    private fun kandilli(): QuakeData {
        val o = JSONObject(http("https://api.orhanaydogdu.com.tr/deprem/kandilli/live?limit=500"))
        val arr = o.getJSONArray("result")
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val items = (0 until arr.length()).mapNotNull { i ->
            val q = arr.getJSONObject(i)
            runCatching {
                val c = q.getJSONObject("geojson").getJSONArray("coordinates")
                Quake(
                    id = "kan-" + q.optString("earthquake_id"), magnitude = q.getDouble("mag"),
                    depthKm = q.optDouble("depth", 0.0), place = q.optString("title"),
                    lat = c.getDouble(1), lon = c.getDouble(0), time = LocalDateTime.parse(q.getString("date_time"), f),
                )
            }.getOrNull()
        }.sortedByDescending { it.time }
        return QuakeData("Kandilli Rasathanesi", items, System.currentTimeMillis())
    }

    // ---------- Kesintiler ----------

    suspend fun outages(city: City): List<Outage> = withContext(Dispatchers.IO) {
        when (city) {
            City.ANKARA -> aski()
            else -> emptyList()
        }
    }

    private fun aski(): List<Outage> {
        val t = http("https://www.aski.gov.tr/tr/Kesinti.aspx")
        val f = DateTimeFormatter.ofPattern("d.M.yyyy H:mm:ss")
        fun dt(s: String) = runCatching { LocalDateTime.parse(s.trim(), f) }.getOrNull()
        return t.split("class=\"appear-animation\"").drop(1).mapNotNull { blk ->
            val strongs = Regex("""<strong>(.*?)</strong>""", RegexOption.DOT_MATCHES_ALL).findAll(blk).map { clean(it.groupValues[1]) }.toList()
            if (strongs.isEmpty()) return@mapNotNull null
            fun field(label: String) = Regex("""<b>\s*$label\s*:?\s*</b>(.*?)(<br|</p>)""", RegexOption.DOT_MATCHES_ALL)
                .find(blk)?.groupValues?.get(1)?.let(::clean) ?: ""
            Outage(
                district = strongs[0], kind = strongs.getOrElse(1) { "" },
                start = dt(field("Arıza Tarihi")), end = dt(field("Tamir Tarihi")),
                detail = field("Detay"), places = field("Etkilenen Yerler"), utility = "Su",
            )
        }
    }
}
