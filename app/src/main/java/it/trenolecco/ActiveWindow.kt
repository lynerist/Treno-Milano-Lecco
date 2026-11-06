package it.trenolecco

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Decide quando l'app deve controllare i treni (solo feriali, solo nelle fasce lavorative). */
object ActiveWindow {

    fun isActive(now: ZonedDateTime): Boolean {
        if (now.dayOfWeek !in Config.workdays) return false
        val t = now.toLocalTime()
        return Config.windows.any { (from, to) -> !t.isBefore(from) && t.isBefore(to) }
    }

    /** Prossimo inizio di fascia strettamente successivo a [now]. */
    fun nextStart(now: ZonedDateTime): ZonedDateTime {
        for (d in 0L..8L) {
            val date = now.toLocalDate().plusDays(d)
            if (date.dayOfWeek !in Config.workdays) continue
            for ((from, _) in Config.windows) {
                val start = date.atTime(from).atZone(Config.zone)
                if (start.isAfter(now)) return start
            }
        }
        return now.plusDays(1)
    }

    /** Fine della fascia in corso (solo se [now] è dentro una fascia attiva). */
    private fun currentEnd(now: ZonedDateTime): ZonedDateTime? {
        val t = now.toLocalTime()
        val (_, to) = Config.windows.firstOrNull { (from, to) -> !t.isBefore(from) && t.isBefore(to) } ?: return null
        return now.toLocalDate().atTime(to).atZone(Config.zone)
    }

    /**
     * Prossimo momento in cui il widget deve svegliarsi: dentro una fascia ogni [stepMin] minuti
     * (ma mai oltre la fine della fascia, così a fine fascia si aggiorna subito in "Fuori orario");
     * fuori fascia, esattamente all'inizio della fascia successiva.
     */
    fun nextWake(now: ZonedDateTime, stepMin: Long): ZonedDateTime {
        if (now.dayOfWeek !in Config.workdays) return nextStart(now)
        val end = currentEnd(now) ?: return nextStart(now)
        val step = now.plusMinutes(stepMin)
        return if (step.isBefore(end)) step else end
    }

    /** Es. "oggi alle 16:00", "domani alle 07:00", "lunedì alle 07:00". */
    fun describeNext(now: ZonedDateTime): String {
        val s = nextStart(now)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), s.toLocalDate())
        val day = when (days) {
            0L -> "oggi"
            1L -> "domani"
            else -> s.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ITALIAN)
        }
        return "$day alle ${s.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }

    /** Es. "lun-ven 07:00–09:00 e 16:00–19:00". */
    fun describeWindows(): String =
        "lun-ven " + Config.windows.joinToString(" e ") { (a, b) -> "$a–$b" }
}
