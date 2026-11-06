package it.trenolecco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class ActiveWindowTest {
    // 2026-11-06 è un venerdì, 2026-11-07 sabato, 2026-11-09 lunedì
    private fun t(date: String, time: String): ZonedDateTime =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), Config.zone)

    @Test
    fun activeOnlyInsideWindowsOnWorkdays() {
        assertFalse(ActiveWindow.isActive(t("2026-11-06", "06:59")))
        assertTrue(ActiveWindow.isActive(t("2026-11-06", "07:00")))
        assertTrue(ActiveWindow.isActive(t("2026-11-06", "08:59")))
        assertFalse(ActiveWindow.isActive(t("2026-11-06", "09:00")))
        assertFalse(ActiveWindow.isActive(t("2026-11-06", "13:46")))
        assertTrue(ActiveWindow.isActive(t("2026-11-06", "16:00")))
        assertTrue(ActiveWindow.isActive(t("2026-11-06", "18:59")))
        assertFalse(ActiveWindow.isActive(t("2026-11-06", "19:00")))
        assertFalse(ActiveWindow.isActive(t("2026-11-07", "08:00"))) // sabato
    }

    @Test
    fun wakeInsideWindowIsOneStepAway() {
        assertEquals(t("2026-11-06", "07:13"), ActiveWindow.nextWake(t("2026-11-06", "07:10"), 3))
    }

    @Test
    fun wakeIsCappedAtWindowEnd() {
        assertEquals(t("2026-11-06", "09:00"), ActiveWindow.nextWake(t("2026-11-06", "08:59"), 3))
        assertEquals(t("2026-11-06", "19:00"), ActiveWindow.nextWake(t("2026-11-06", "18:58"), 3))
    }

    @Test
    fun wakeOutsideWindowIsExactlyNextStart() {
        assertEquals(t("2026-11-06", "16:00"), ActiveWindow.nextWake(t("2026-11-06", "09:00"), 3))
        assertEquals(t("2026-11-06", "16:00"), ActiveWindow.nextWake(t("2026-11-06", "13:46"), 3))
        assertEquals(t("2026-11-06", "07:00"), ActiveWindow.nextWake(t("2026-11-06", "05:00"), 3))
        assertEquals(t("2026-11-09", "07:00"), ActiveWindow.nextWake(t("2026-11-06", "19:00"), 3))
        assertEquals(t("2026-11-09", "07:00"), ActiveWindow.nextWake(t("2026-11-07", "10:00"), 3))
    }

    @Test
    fun chainNeverStallsFromFridayToMonday() {
        var now = t("2026-11-06", "00:00")
        val limit = t("2026-11-09", "20:00")
        val wakes = HashSet<String>()
        while (now.isBefore(limit)) {
            val next = ActiveWindow.nextWake(now, 3)
            assertTrue("la sveglia deve stare nel futuro", next.isAfter(now))
            if (ActiveWindow.isActive(now)) {
                assertTrue("in fascia attiva al massimo 3 minuti tra due sveglie",
                    Duration.between(now, next) <= Duration.ofMinutes(3))
            }
            wakes.add("${next.dayOfMonth} ${next.toLocalTime()}")
            now = next
        }
        // Inizio e fine di ogni fascia sono veri punti di risveglio
        for (d in listOf(6, 9)) for (hm in listOf("07:00", "09:00", "16:00", "19:00")) {
            assertTrue("manca la sveglia $d $hm", "$d $hm" in wakes)
        }
        // Nel weekend non ci sono sveglie intermedie
        assertFalse(wakes.any { it.startsWith("7 ") || it.startsWith("8 ") })
    }
}
