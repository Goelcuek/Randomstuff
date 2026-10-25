package com.goelcuek.sehrim

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Eczane çalışma saati mantığı.
 *  - Normal eczaneler: OSM'de opening_hours varsa o, yoksa Türkiye'deki olağan saatler
 *    (Pzt–Cmt 08:30–19:00, Pazar ve resmi tatillerde kapalı).
 *  - Nöbetçi eczaneler: nöbet günü 08:30'dan ertesi gün 08:30'a kadar açık.
 */
object Hours {
    val TZ: ZoneId = ZoneId.of("Europe/Istanbul")
    private val DEFAULT_OPEN = LocalTime.of(8, 30)
    private val DEFAULT_CLOSE = LocalTime.of(19, 0)
    val DUTY_CHANGE: LocalTime = LocalTime.of(8, 30)

    // Resmi tatiller (tam gün). Arife yarım günleri normal gün sayılır.
    private val HOLIDAYS: Set<LocalDate> = setOf(
        // 2026
        "2026-01-01", "2026-03-20", "2026-03-21", "2026-03-22", "2026-04-23", "2026-05-01", "2026-05-19",
        "2026-05-27", "2026-05-28", "2026-05-29", "2026-05-30", "2026-07-15", "2026-08-30", "2026-10-29",
        // 2027
        "2027-01-01", "2027-03-09", "2027-03-10", "2027-03-11", "2027-04-23", "2027-05-01", "2027-05-16",
        "2027-05-17", "2027-05-18", "2027-05-19", "2027-07-15", "2027-08-30", "2027-10-29",
        // 2028 (sabit tarihli olanlar)
        "2028-01-01", "2028-04-23", "2028-05-01", "2028-05-19", "2028-07-15", "2028-08-30", "2028-10-29",
    ).map(LocalDate::parse).toSet()

    fun now(): LocalDateTime = LocalDateTime.now(TZ)

    fun isHoliday(d: LocalDate) = d in HOLIDAYS

    /** Şu anki nöbetin başladığı gün (08:30'dan önceyse dün). */
    fun currentDutyDate(t: LocalDateTime = now()): LocalDate =
        if (t.toLocalTime() < DUTY_CHANGE) t.toLocalDate().minusDays(1) else t.toLocalDate()

    /** Normal (nöbetçi olmayan) eczane şu an açık mı? null = bilinmiyor. */
    fun isOpen(openingHours: String, t: LocalDateTime = now()): Boolean? {
        val oh = openingHours.trim()
        if (oh.isEmpty()) return defaultOpen(t)
        if (oh == "24/7") return true
        return runCatching { parse(oh, t) }.getOrNull() ?: defaultOpen(t)
    }

    fun defaultOpen(t: LocalDateTime): Boolean {
        val d = t.toLocalDate()
        if (d.dayOfWeek == DayOfWeek.SUNDAY || isHoliday(d)) return false
        val time = t.toLocalTime()
        return time >= DEFAULT_OPEN && time < DEFAULT_CLOSE
    }

    /** Normal eczanelerin genel olarak açık olduğu saatler mi? (ekranda ipucu için) */
    fun regularHoursNow(t: LocalDateTime = now()) = defaultOpen(t)

    private val dayCodes = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

    /**
     * Basit opening_hours ayrıştırıcı: "Mo-Fr 08:30-19:00; Sa 09:00-19:00; Su off", "Mo-Sa 09:00-19:00,20:00-22:00",
     * "PH off" vb. Desteklenmeyen kurallarda null döner.
     */
    private fun parse(oh: String, t: LocalDateTime): Boolean? {
        val today = t.toLocalDate()
        val dayIdx = today.dayOfWeek.value - 1
        val time = t.toLocalTime()
        val holiday = isHoliday(today)
        var result: Boolean? = null
        var matchedAny = false
        for (raw in oh.split(';')) {
            val rule = raw.trim()
            if (rule.isEmpty()) continue
            val m = Regex("""^((?:(?:Mo|Tu|We|Th|Fr|Sa|Su|PH)(?:\s*-\s*(?:Mo|Tu|We|Th|Fr|Sa|Su))?\s*,?\s*)+)?\s*(.*)$""").find(rule) ?: return null
            val daysPart = m.groupValues[1].trim()
            val timesPart = m.groupValues[2].trim()
            val applies = if (daysPart.isEmpty()) true else dayMatches(daysPart, dayIdx, holiday) ?: return null
            if (!applies) continue
            matchedAny = true
            result = when {
                timesPart.equals("off", true) || timesPart.equals("closed", true) -> false
                timesPart.isEmpty() || timesPart == "24/7" -> true
                else -> {
                    val ranges = timesPart.split(',').map { it.trim() }
                    var open = false
                    for (r in ranges) {
                        val tm = Regex("""^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\+?$""").find(r) ?: return null
                        val (h1, m1, h2, m2) = tm.destructured
                        val start = LocalTime.of(h1.toInt() % 24, m1.toInt())
                        val endH = h2.toInt()
                        val end = if (endH >= 24) LocalTime.MAX else LocalTime.of(endH, m2.toInt())
                        open = open || if (end > start) time >= start && time < end else time >= start || time < end
                    }
                    open
                }
            }
        }
        if (!matchedAny) return if (holiday) false else false
        return result
    }

    private fun dayMatches(part: String, idx: Int, holiday: Boolean): Boolean? {
        var hit = false
        var hasPH = false
        for (tok in part.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            if (tok == "PH") { hasPH = true; if (holiday) hit = true; continue }
            val ends = tok.split('-').map { it.trim() }
            val a = dayCodes.indexOf(ends[0]); if (a < 0) return null
            val b = if (ends.size > 1) dayCodes.indexOf(ends[1]).also { if (it < 0) return null } else a
            val inRange = if (a <= b) idx in a..b else idx >= a || idx <= b
            if (inRange) hit = true
        }
        // Resmi tatilde, kural PH içermiyorsa ve "PH off" yoksa varsayılan olarak kapalı kabul edilir
        if (holiday && !hasPH) return false
        return hit
    }
}
