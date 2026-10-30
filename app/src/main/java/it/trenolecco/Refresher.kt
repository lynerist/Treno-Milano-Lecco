package it.trenolecco

import android.content.Context
import org.json.JSONException
import java.io.IOException
import java.time.ZonedDateTime

/** Scarica i dati, li salva e restituisce lo snapshot (anche in caso di errore). Chiamare fuori dal thread UI. */
object Refresher {
    fun run(ctx: Context, now: ZonedDateTime = ZonedDateTime.now(Config.zone)): Snapshot {
        val direction = Direction.forTime(now)
        val snap = try {
            ViaggiaTreno.fetch(direction, now)
        } catch (e: Exception) {
            val msg = when (e) {
                is JSONException -> "Risposta del servizio non valida"
                is IOException -> "Servizio non raggiungibile (${e.message ?: "rete"})"
                else -> "Errore: ${e.message ?: e.javaClass.simpleName}"
            }
            val prev = SnapshotStore.load(ctx)?.takeIf { it.directionLabel == direction.label }
                ?: Snapshot(0L, direction.label, emptyList(), null, null)
            prev.copy(error = msg)
        }
        SnapshotStore.save(ctx, snap)
        return snap
    }
}
