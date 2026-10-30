package it.trenolecco

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.max

enum class TrainStatus { ON_TIME, DELAYED, CANCELLED, PARTIAL, NO_DATA }

data class Train(
    val label: String,
    val destination: String,
    val scheduledMs: Long,
    val delayMin: Int,
    val status: TrainStatus,
    val platform: String?,
    val lastSeenMs: Long?
) {
    /** Orario di partenza effettivo (programmato + ritardo). */
    val effectiveMs: Long get() = scheduledMs + max(delayMin, 0) * 60_000L
}

/** I prossimi treni di una direzione (es. "Lecco → Milano P.G."). */
data class Section(
    val directionLabel: String,
    val trains: List<Train>,
    /** Avviso sulla qualità dei dati (rilevamento fermo, servizio lento...). */
    val note: String?
) {
    fun upcoming(nowMs: Long): List<Train> = trains.filter { it.effectiveMs >= nowMs - 60_000 }
}

data class Snapshot(
    val fetchedAtMs: Long,
    val sections: List<Section>,
    /** Errore dell'ultimo tentativo di aggiornamento (i treni restano quelli dell'ultimo riuscito). */
    val error: String?
) {
    val isEmpty: Boolean get() = fetchedAtMs == 0L && sections.all { it.trains.isEmpty() }
}

object Palette {
    val cardBg = Color(0xFF1B1D23)
    val white = Color(0xFFFFFFFF)
    val gray = Color(0xFF9AA0AB)
    val green = Color(0xFF66BB6A)
    val amber = Color(0xFFFFCA28)
    val red = Color(0xFFEF5350)
}

object Fmt {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(Config.zone).format(hhmm)

    fun shortStatus(t: Train): String = when (t.status) {
        TrainStatus.ON_TIME -> "In orario"
        TrainStatus.NO_DATA -> "Nessun ritardo"
        TrainStatus.DELAYED -> "+${t.delayMin} min"
        TrainStatus.CANCELLED -> "Soppresso"
        TrainStatus.PARTIAL -> "Soppr. parziale"
    }

    fun statusColor(t: Train): Color = when (t.status) {
        TrainStatus.ON_TIME, TrainStatus.NO_DATA -> Palette.green
        TrainStatus.DELAYED -> if (t.delayMin >= 5) Palette.red else Palette.amber
        TrainStatus.CANCELLED -> Palette.red
        TrainStatus.PARTIAL -> Palette.amber
    }

    fun detail(t: Train): String {
        val parts = ArrayList<String>()
        t.platform?.let { parts.add("Bin. $it") }
        parts.add(t.label)
        if (t.status == TrainStatus.DELAYED) parts.add("parte ~${time(t.effectiveMs)}")
        return parts.joinToString(" · ")
    }

    /** Testo di avviso da mostrare sotto i treni, o null se tutto è regolare. */
    fun warning(s: Snapshot, nowMs: Long): String? {
        val ageMin = ((nowMs - s.fetchedAtMs) / 60_000).toInt()
        val note = s.sections.firstNotNullOfOrNull { it.note }
        return when {
            s.error != null -> "⚠ ${s.error}" + if (s.fetchedAtMs > 0) " · dati di $ageMin min fa" else ""
            note != null -> "⚠ $note"
            ageMin > 8 -> "⚠ Dati di $ageMin min fa"
            else -> null
        }
    }
}

/** Salva l'ultimo risultato in SharedPreferences, così widget e app lo condividono. */
object SnapshotStore {
    private const val PREFS = "snapshot"
    private const val KEY = "json_v2"

    fun save(ctx: Context, s: Snapshot) {
        val secs = JSONArray()
        for (sec in s.sections) {
            val arr = JSONArray()
            for (t in sec.trains) {
                arr.put(
                    JSONObject()
                        .put("label", t.label)
                        .put("dest", t.destination)
                        .put("sched", t.scheduledMs)
                        .put("delay", t.delayMin)
                        .put("status", t.status.name)
                        .put("platform", t.platform)
                        .put("seen", t.lastSeenMs)
                )
            }
            secs.put(JSONObject().put("dir", sec.directionLabel).put("note", sec.note).put("trains", arr))
        }
        val o = JSONObject()
            .put("fetched", s.fetchedAtMs)
            .put("error", s.error)
            .put("sections", secs)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    fun load(ctx: Context): Snapshot? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val secs = o.getJSONArray("sections")
            val sections = (0 until secs.length()).map { si ->
                val so = secs.getJSONObject(si)
                val arr = so.getJSONArray("trains")
                val trains = (0 until arr.length()).map { i ->
                    val t = arr.getJSONObject(i)
                    Train(
                        label = t.getString("label"),
                        destination = t.getString("dest"),
                        scheduledMs = t.getLong("sched"),
                        delayMin = t.getInt("delay"),
                        status = TrainStatus.valueOf(t.getString("status")),
                        platform = t.strOrNull("platform"),
                        lastSeenMs = if (t.isNull("seen")) null else t.getLong("seen")
                    )
                }
                Section(so.getString("dir"), trains, so.strOrNull("note"))
            }
            Snapshot(o.getLong("fetched"), sections, o.strOrNull("error"))
        } catch (e: Exception) {
            null
        }
    }
}

/** Stringa non vuota oppure null (gestisce chiave assente e JSON null). */
fun JSONObject.strOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
