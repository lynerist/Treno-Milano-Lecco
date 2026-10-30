package it.trenolecco

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/**
 * Impostazioni principali. Per cambiare orari o stazioni basta modificare questo file.
 */
object Config {
    val zone: ZoneId = ZoneId.of("Europe/Rome")

    // Codice stazione ViaggiaTreno: tutti gli orari mostrati sono quelli della stazione di Lecco
    const val LECCO = "S01520"

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
}

/**
 * Le due rotte, entrambe viste dalla stazione di Lecco:
 * - Lecco → Milano: tabellone partenze di Lecco, treni diretti a Milano P.G. (orario di partenza da Lecco)
 * - Milano → Lecco: tabellone arrivi di Lecco, treni partiti da Milano P.G. (orario di arrivo a Lecco)
 */
enum class Direction(
    val label: String,
    val arrival: Boolean,
    val matchField: String,
    val matchValues: Set<String>
) {
    TO_MILANO("Lecco → Milano P.G.", false, "destinazione", setOf("MILANO PORTA GARIBALDI")),
    TO_LECCO("Milano P.G. → Lecco", true, "origine", setOf("MILANO PORTA GARIBALDI"));

    val board: String get() = if (arrival) "arrivi" else "partenze"
    val timeField: String get() = if (arrival) "orarioArrivo" else "orarioPartenza"
}
