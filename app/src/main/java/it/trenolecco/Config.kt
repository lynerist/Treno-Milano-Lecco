package it.trenolecco

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Impostazioni principali. Per cambiare orari o stazioni basta modificare questo file.
 */
object Config {
    val zone: ZoneId = ZoneId.of("Europe/Rome")

    // Codici stazione ViaggiaTreno
    const val LECCO = "S01520"
    const val MILANO_PG = "S01645"

    /** Giorni in cui l'app controlla i treni. */
    val workdays = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
    )

    /** Fasce orarie attive (inizio incluso, fine esclusa). */
    val windows = listOf(
        LocalTime.of(7, 0) to LocalTime.of(9, 0),
        LocalTime.of(16, 0) to LocalTime.of(19, 0)
    )

    /** Ogni quanti minuti il widget si aggiorna dentro le fasce attive. */
    const val REFRESH_MINUTES = 3L

    /** Oltre questi minuti senza rilevamenti il dato di ritardo di un treno è considerato "fermo". */
    const val STALE_TRAIN_MINUTES = 10L

    /** Prima di quest'ora si controlla Lecco → Milano, dopo Milano → Lecco. */
    const val SWITCH_HOUR = 13
}

enum class Direction(
    val label: String,
    val originId: String,
    val destinations: Set<String>
) {
    TO_MILANO("Lecco → Milano P.G.", Config.LECCO, setOf("MILANO PORTA GARIBALDI")),
    TO_LECCO("Milano P.G. → Lecco", Config.MILANO_PG, setOf("LECCO", "COLICO", "SONDRIO", "TIRANO"));

    companion object {
        fun forTime(now: ZonedDateTime): Direction =
            if (now.hour < Config.SWITCH_HOUR) TO_MILANO else TO_LECCO
    }
}
