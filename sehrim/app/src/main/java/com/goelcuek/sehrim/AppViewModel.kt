package com.goelcuek.sehrim

import android.app.Application
import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repo(app)
    private val prefs = app.getSharedPreferences("sehrim", 0)

    var city by mutableStateOf(City.of(prefs.getString("city", null)))
        private set
    var static by mutableStateOf<StaticData?>(null); private set
    var duty by mutableStateOf<DutyData?>(null); private set
    var staticError by mutableStateOf<String?>(null); private set
    var dutyError by mutableStateOf<String?>(null); private set
    var loadingStatic by mutableStateOf(false); private set
    var loadingDuty by mutableStateOf(false); private set
    var location by mutableStateOf<Location?>(null); private set
    var locating by mutableStateOf(false); private set
    var lines by mutableStateOf<List<Line>>(emptyList()); private set
    /** Ekranın "şu an" değerini dakikada bir tazelemek için */
    var tick by mutableStateOf(0L); private set

    private var loadJob: Job? = null

    init {
        city?.let { load(it) }
        viewModelScope.launch { while (isActive) { delay(60_000); tick++ } }
    }

    fun selectCity(c: City) {
        prefs.edit().putString("city", c.slug).apply()
        if (c != city) { static = null; duty = null; lines = emptyList() }
        city = c
        load(c)
    }

    fun load(c: City, force: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            launch {
                loadingDuty = true; dutyError = null
                runCatching { repo.loadDuty(c, force) }
                    .onSuccess { duty = it }.onFailure { dutyError = it.message }
                loadingDuty = false
            }
            loadingStatic = true; staticError = null
            runCatching { repo.loadStatic(c, force) }
                .onSuccess { s ->
                    static = s
                    lines = withContext(Dispatchers.Default) { groupLines(s.routes) }
                }
                .onFailure { staticError = it.message }
            loadingStatic = false
        }
    }

    fun refresh() { city?.let { load(it, force = true) }; requestLocation() }

    fun requestLocation(onCity: ((City) -> Unit)? = null) {
        if (locating) return
        viewModelScope.launch {
            locating = true
            val l = runCatching { Geo.currentLocation(getApplication()) }.getOrNull()
            locating = false
            if (l != null) {
                location = l
                onCity?.invoke(City.nearest(l.latitude, l.longitude))
            }
        }
    }

    private fun groupLines(routes: List<Route>): List<Line> {
        val groups = LinkedHashMap<String, MutableList<Route>>()
        for (r in routes) {
            val key = if (r.ref.isNotBlank()) "${r.mode}|${r.ref}" else "${r.mode}|#${r.id}"
            groups.getOrPut(key) { mutableListOf() }.add(r)
        }
        return groups.values.map { Line(it.first().mode, it.first().ref, it.sortedByDescending { r -> r.stops.size }) }
            .sortedWith(compareBy<Line>({ Modes.order.indexOf(it.mode).let { i -> if (i < 0) 99 else i } }, { refSortKey(it.ref) }, { it.ref }))
    }

    private fun refSortKey(ref: String): String {
        // "M1" < "M2" < "M10"; "14" < "14A" < "100"
        val m = Regex("""^(\D*)(\d+)(.*)$""").find(ref) ?: return "~$ref"
        return m.groupValues[1] + m.groupValues[2].padStart(5, '0') + m.groupValues[3]
    }

    /** Şu an açık eczaneler – nöbetçiler + çalışma saatindeki normal eczaneler, yakından uzağa. */
    fun openPharmacies(includeRegular: Boolean): List<OpenPharmacy> {
        @Suppress("UNUSED_VARIABLE") val t = tick
        val loc = location
        val now = Hours.now()
        val out = ArrayList<OpenPharmacy>()
        val d = duty
        val dutyValid = d != null && d.dutyDate == Hours.currentDutyDate(now).toString()
        if (d != null) {
            for (p in d.items) {
                out += OpenPharmacy(
                    p.name, listOf(p.address, p.district).filter { it.isNotBlank() }.joinToString(" · "), p.phone,
                    p.lat, p.lon, onDuty = true,
                    hoursNote = if (dutyValid) "Nöbetçi · sabah 08:30'a kadar" else "Nöbet listesi güncel olmayabilir",
                    distanceM = if (loc != null && p.lat != null && p.lon != null) Geo.distance(loc.latitude, loc.longitude, p.lat, p.lon) else null,
                )
            }
        }
        if (includeRegular) {
            val dutyPts = out.filter { it.lat != null }
            static?.pharmacies?.forEach { p ->
                if (Hours.isOpen(p.hours, now) != true) return@forEach
                // nöbetçi listede zaten varsa tekrar ekleme
                if (dutyPts.any { Geo.distance(it.lat!!, it.lon!!, p.lat, p.lon) < 60 }) return@forEach
                out += OpenPharmacy(
                    p.name, p.address, p.phone, p.lat, p.lon, onDuty = false,
                    hoursNote = if (p.hours.isBlank()) "Olağan saatler (tahmini) · 19:00'a kadar" else "Saat: ${p.hours}",
                    distanceM = loc?.let { Geo.distance(it.latitude, it.longitude, p.lat, p.lon) },
                )
            }
        }
        return if (loc != null) out.sortedWith(compareBy(nullsLast()) { it.distanceM })
        else out.sortedWith(compareBy({ !it.onDuty }, { it.address }))
    }

    /** Kullanıcıya yakın duraklar ve oradan geçen hatlar. */
    fun nearbyStops(radiusM: Double = 600.0): List<Triple<Stop, Double, List<Line>>> {
        val loc = location ?: return emptyList()
        val ls = lines
        val byStop = LinkedHashMap<String, Triple<Stop, Double, MutableSet<Line>>>()
        for (line in ls) for (r in line.variants) for (s in r.stops) {
            val dist = Geo.distance(loc.latitude, loc.longitude, s.lat, s.lon)
            if (dist > radiusM) continue
            val key = s.name.ifBlank { "%.4f,%.4f".format(s.lat, s.lon) }
            val cur = byStop[key]
            if (cur == null) byStop[key] = Triple(s, dist, mutableSetOf(line))
            else {
                cur.third.add(line)
                if (dist < cur.second) byStop[key] = Triple(s, dist, cur.third)
            }
        }
        return byStop.values.sortedBy { it.second }.take(15).map { Triple(it.first, it.second, it.third.toList()) }
    }
}
