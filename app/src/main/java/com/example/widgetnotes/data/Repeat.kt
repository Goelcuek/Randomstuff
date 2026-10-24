package com.example.widgetnotes.data

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

enum class RepeatUnit { DAY, WEEKDAYS, WEEK, MONTH, YEAR }

/**
 * A repeat rule. [anchor] is the first occurrence; later ones are always counted from it
 * (anchor + k × interval), so "monthly on the 31st" gives 28/29 Feb, then 31 Mar again
 * instead of drifting to the 28th forever.
 */
data class Repeat(val unit: RepeatUnit, val every: Int = 1, val anchor: Long) {

    val label: String
        get() = when (unit) {
            RepeatUnit.DAY -> if (every == 1) "Daily" else "Every $every days"
            RepeatUnit.WEEKDAYS -> "Weekdays"
            RepeatUnit.WEEK -> if (every == 1) "Weekly" else "Every $every weeks"
            RepeatUnit.MONTH -> if (every == 1) "Monthly" else "Every $every months"
            RepeatUnit.YEAR -> if (every == 1) "Yearly" else "Every $every years"
        }

    /** First occurrence strictly after [after] (epoch millis). */
    fun nextAfter(after: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val a = Instant.ofEpochMilli(anchor).atZone(zone)
        if (anchor > after) return anchor

        if (unit == RepeatUnit.WEEKDAYS) {
            // Same clock time as the anchor, on the next Mon–Fri after `after`
            var d = Instant.ofEpochMilli(after).atZone(zone)
                .with(a.toLocalTime())
                .withZoneSameLocal(zone)
            while (d.toInstant().toEpochMilli() <= after || d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) {
                d = d.plusDays(1).with(a.toLocalTime())
            }
            return d.toInstant().toEpochMilli()
        }

        val step = every.coerceAtLeast(1).toLong()
        val target = Instant.ofEpochMilli(after).atZone(zone)
        val elapsed = when (unit) {
            RepeatUnit.DAY -> ChronoUnit.DAYS.between(a, target)
            RepeatUnit.WEEK -> ChronoUnit.WEEKS.between(a, target)
            RepeatUnit.MONTH -> ChronoUnit.MONTHS.between(a, target)
            RepeatUnit.YEAR -> ChronoUnit.YEARS.between(a, target)
            RepeatUnit.WEEKDAYS -> 0L
        }
        // Jump close, then step forward to the first one past `after`
        var k = (elapsed / step - 1).coerceAtLeast(0)
        while (occurrence(a, k * step).toInstant().toEpochMilli() <= after) k++
        return occurrence(a, k * step).toInstant().toEpochMilli()
    }

    private fun occurrence(a: ZonedDateTime, n: Long): ZonedDateTime = when (unit) {
        RepeatUnit.DAY -> a.plusDays(n)
        RepeatUnit.WEEK -> a.plusWeeks(n)
        RepeatUnit.MONTH -> a.plusMonths(n)
        RepeatUnit.YEAR -> a.plusYears(n)
        RepeatUnit.WEEKDAYS -> a.plusDays(n)
    }

    fun toJson(): JSONObject = JSONObject().put("unit", unit.name).put("every", every).put("anchor", anchor)

    companion object {
        fun fromJson(o: JSONObject?): Repeat? = o?.let {
            val unit = RepeatUnit.entries.firstOrNull { u -> u.name == it.optString("unit") } ?: return null
            Repeat(unit, it.optInt("every", 1).coerceAtLeast(1), it.getLong("anchor"))
        }
    }
}
