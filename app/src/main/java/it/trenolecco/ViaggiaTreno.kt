package it.trenolecco

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Client per l'API non ufficiale di ViaggiaTreno (copre anche i treni Trenord).
 * Endpoint usato: /partenze/{stazione}/{data} → tabellone partenze con ritardo in tempo reale.
 */
object ViaggiaTreno {
    // Il server reindirizza https → http, quindi si usa direttamente http
    // (consentito solo per questo dominio in res/xml/network_security_config.xml)
    private const val BASE = "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // Formato richiesto dall'API, es. "Fri Oct 30 2026 17:55:00 GMT+0100" (Locale.US obbligatorio)
    private val dateFmt = DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'xx", Locale.US)

    private const val SLOW_MS = 6_000L

    private fun get(url: String): Pair<JSONArray, Long> {
        val started = System.currentTimeMillis()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 6_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            val code = conn.responseCode
            if (code != 200) throw java.io.IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            // L'API a volte risponde con corpo vuoto invece di un array vuoto
            val arr = if (body.isEmpty()) JSONArray() else JSONArray(body)
            return arr to (System.currentTimeMillis() - started)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Cerca i prossimi treni nella direzione indicata. Il tabellone copre circa un'ora di partenze;
     * lo interroghiamo a "adesso" e a +60 minuti e uniamo i risultati.
     */
    fun fetch(direction: Direction, now: ZonedDateTime): Section {
        val nowMs = now.toInstant().toEpochMilli()
        val seen = HashSet<String>()
        val trains = ArrayList<Train>()
        var maxLatency = 0L

        for ((i, offset) in listOf(0L, 60L).withIndex()) {
            try {
                val stamp = now.plusMinutes(offset).format(dateFmt).replace(" ", "%20")
                val (arr, latency) = get("$BASE/${direction.board}/${Config.LECCO}/$stamp")
                maxLatency = maxOf(maxLatency, latency)
                parseInto(arr, direction, seen, trains)
            } catch (e: Exception) {
                if (i == 0) throw e // se fallisce la chiamata principale, l'aggiornamento è fallito
            }
        }

        val upcoming = trains
            .filter { it.effectiveMs >= nowMs - 60_000 }
            .sortedBy { it.effectiveMs }
            .take(3)

        val notes = ArrayList<String>()
        val staleMin = upcoming.take(2)
            .filter { (it.status == TrainStatus.ON_TIME || it.status == TrainStatus.DELAYED) && it.lastSeenMs != null }
            .maxOfOrNull { (nowMs - it.lastSeenMs!!) / 60_000 } ?: 0L
        if (staleMin > Config.STALE_TRAIN_MINUTES) {
            notes.add("Rilevamento fermo da $staleMin min: il ritardo potrebbe non essere aggiornato")
        }
        if (maxLatency > SLOW_MS) notes.add("Servizio lento (${maxLatency / 1000} s)")

        return Section(
            directionLabel = direction.label,
            arrival = direction.arrival,
            trains = upcoming,
            note = notes.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        )
    }

    private fun parseInto(arr: JSONArray, direction: Direction, seen: HashSet<String>, out: MutableList<Train>) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val other = o.strOrNull(direction.matchField)?.uppercase() ?: continue
            if (other !in direction.matchValues) continue

            val key = "${o.optInt("numeroTreno")}-${o.optLong("dataPartenzaTreno")}"
            if (!seen.add(key)) continue

            val scheduled = o.optLong(direction.timeField, 0L)
            if (scheduled == 0L) continue

            val delay: Int? = if (o.isNull("ritardo")) null else o.optInt("ritardo")
            val provvedimento = o.optInt("provvedimento", 0)
            val status = when {
                provvedimento == 1 -> TrainStatus.CANCELLED
                provvedimento == 2 -> TrainStatus.PARTIAL
                delay == null -> TrainStatus.NO_DATA
                delay <= 0 -> TrainStatus.ON_TIME
                else -> TrainStatus.DELAYED
            }
            val label = o.strOrNull("compNumeroTreno")
                ?: "${o.strOrNull("categoria") ?: "Treno"} ${o.optInt("numeroTreno")}"
            val platform = if (direction.arrival) {
                o.strOrNull("binarioEffettivoArrivoDescrizione") ?: o.strOrNull("binarioProgrammatoArrivoDescrizione")
            } else {
                o.strOrNull("binarioEffettivoPartenzaDescrizione") ?: o.strOrNull("binarioProgrammatoPartenzaDescrizione")
            }

            out.add(
                Train(
                    label = label,
                    destination = other,
                    scheduledMs = scheduled,
                    delayMin = delay ?: 0,
                    status = status,
                    platform = platform,
                    lastSeenMs = if (o.isNull("ultimoRilev")) null else o.optLong("ultimoRilev")
                )
            )
        }
    }
}
