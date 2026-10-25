package com.goelcuek.sehrim

enum class City(
    val slug: String, val title: String, val lat: Double, val lon: Double, val fanatikSlug: String,
    /** Nöbet değişim saati (İstanbul Eczacı Odası 09:00, diğerleri 08:30). */
    val dutyChange: java.time.LocalTime,
) {
    ISTANBUL("istanbul", "İstanbul", 41.0082, 28.9784, "istanbul", java.time.LocalTime.of(9, 0)),
    ANKARA("ankara", "Ankara", 39.9334, 32.8597, "ankara", java.time.LocalTime.of(8, 30)),
    ESKISEHIR("eskisehir", "Eskişehir", 39.7767, 30.5206, "eskisehir", java.time.LocalTime.of(8, 30));

    companion object {
        fun of(slug: String?) = entries.firstOrNull { it.slug == slug }
        fun nearest(lat: Double, lon: Double) = entries.minBy { Geo.distance(lat, lon, it.lat, it.lon) }
    }
}

data class Pharmacy(
    val name: String,
    val lat: Double,
    val lon: Double,
    val hours: String,      // OSM opening_hours
    val address: String,
    val phone: String,
)

data class DutyPharmacy(
    val name: String,
    val district: String,
    val address: String,
    val phone: String,
    val lat: Double?,
    val lon: Double?,
)

data class DutyData(
    val source: String,
    val info: String,
    val dutyDate: String,     // yyyy-MM-dd, nöbetin başladığı gün (08:30)
    val fetchedAt: Long,
    val items: List<DutyPharmacy>,
)

data class Stop(val name: String, val lat: Double, val lon: Double)

data class Route(
    val id: Long,
    val mode: String,
    val ref: String,
    val name: String,
    val from: String,
    val to: String,
    val operator: String,
    val colour: String,
    val stops: List<Stop>,
    val geometry: List<String>, // encoded polylines
)

data class StaticData(
    val city: String,
    val updated: String,
    val pharmacies: List<Pharmacy>,
    val routes: List<Route>,
)

/** Aynı hat numarasının (gidiş/dönüş gibi) varyantlarını bir arada tutar. */
data class Line(val mode: String, val ref: String, val variants: List<Route>) {
    /** Arama için normalize edilmiş metin (hat adı, uçlar, durak adları). */
    val search: String by lazy {
        norm(variants.joinToString(" ") { r -> "${r.name} ${r.from} ${r.to} " + r.stops.joinToString(" ") { it.name } })
    }
    val key get() = "$mode|$ref|${variants.first().id}"
    val title get() = ref.ifBlank { variants.first().name.ifBlank { "Hat" } }
    val subtitle: String
        get() {
            val v = variants.first()
            val a = v.from; val b = v.to
            return when {
                a.isNotBlank() && b.isNotBlank() -> "$a ⇄ $b"
                v.name.isNotBlank() && v.name != ref -> v.name
                else -> v.operator
            }
        }
}

/** "Şu an açık" listesinde gösterilen birleşik kayıt. */
data class OpenPharmacy(
    val name: String,
    val address: String,
    val phone: String,
    val lat: Double?,
    val lon: Double?,
    val onDuty: Boolean,
    val hoursNote: String,
    val distanceM: Double?,
)

object Modes {
    val order = listOf("subway", "light_rail", "tram", "train", "ferry", "bus", "trolleybus", "minibus", "share_taxi", "funicular", "monorail", "aerialway")
    fun label(m: String) = when (m) {
        "bus" -> "Otobüs"
        "trolleybus" -> "Troleybüs"
        "minibus" -> "Minibüs"
        "share_taxi" -> "Dolmuş"
        "tram" -> "Tramvay"
        "light_rail" -> "Hafif raylı"
        "subway" -> "Metro"
        "train" -> "Tren"
        "ferry" -> "Vapur"
        "monorail" -> "Monoray"
        "funicular" -> "Füniküler"
        "aerialway" -> "Teleferik"
        else -> m
    }
    fun isRail(m: String) = m in setOf("subway", "light_rail", "tram", "train", "monorail", "funicular")
    fun defaultColor(m: String): Long = when (m) {
        "subway" -> 0xFFE30A17
        "light_rail" -> 0xFF8E44AD
        "tram" -> 0xFF0072BC
        "train" -> 0xFF2E7D32
        "ferry" -> 0xFF00838F
        "bus", "trolleybus" -> 0xFFF57C00
        "minibus", "share_taxi" -> 0xFF6D4C41
        else -> 0xFF455A64
    }
}
