package it.trenolecco

import android.content.Context
import org.json.JSONException
import java.io.IOException
import java.time.ZonedDateTime

/** Scarica i dati di entrambe le direzioni, li salva e restituisce lo snapshot. Chiamare fuori dal thread UI. */
object Refresher {
    fun run(ctx: Context, now: ZonedDateTime = ZonedDateTime.now(Config.zone)): Snapshot {
        val prev = SnapshotStore.load(ctx)
        var error: String? = null

        val sections = Direction.entries.map { d ->
            try {
                ViaggiaTreno.fetch(d, now)
            } catch (e: Exception) {
                if (error == null) {
                    error = when (e) {
                        is JSONException -> "Risposta del servizio non valida"
                        is IOException -> "Servizio non raggiungibile (${e.message ?: "rete"})"
                        else -> "Errore: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
                // Si tengono i dati precedenti di questa direzione
                prev?.sections?.firstOrNull { it.directionLabel == d.label }
                    ?: Section(d.label, d.arrival, emptyList(), null)
            }
        }

        val fetchedAt = if (error == null) now.toInstant().toEpochMilli() else (prev?.fetchedAtMs ?: 0L)
        val snap = Snapshot(fetchedAt, sections, error)
        SnapshotStore.save(ctx, snap)
        return snap
    }
}
