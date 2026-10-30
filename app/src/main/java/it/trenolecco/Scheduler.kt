package it.trenolecco

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Catena di aggiornamenti: ogni esecuzione di [RefreshWorker] programma la successiva
 * (ogni pochi minuti nelle fasce attive, altrimenti all'inizio della fascia seguente).
 * Si alternano due nomi di lavoro ("chain-0"/"chain-1") per non cancellare quello in esecuzione.
 * [SafetyWorker] (ogni 15 minuti) riavvia la catena se per qualche motivo si è interrotta.
 */
object Scheduler {
    private const val SAFETY = "safety"
    private fun chainName(slot: Int) = "chain-$slot"

    /** Riparte da zero: cancella la catena esistente e ne avvia una nuova subito. */
    fun restart(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(chainName(0))
        wm.cancelUniqueWork(chainName(1))
        enqueueChain(ctx, slot = 0, delayMs = 0)
        wm.enqueueUniquePeriodicWork(
            SAFETY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SafetyWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    fun stopAll(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork(chainName(0))
        wm.cancelUniqueWork(chainName(1))
        wm.cancelUniqueWork(SAFETY)
    }

    fun enqueueChain(ctx: Context, slot: Int, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("slot" to slot))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(chainName(slot), ExistingWorkPolicy.REPLACE, req)
    }

    /** Bloccante: da chiamare solo su un thread in background. */
    fun startChainIfIdle(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val active = listOf(0, 1).any { slot ->
            wm.getWorkInfosForUniqueWork(chainName(slot)).get().any { !it.state.isFinished }
        }
        if (!active) enqueueChain(ctx, slot = 0, delayMs = 0)
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val slot = inputData.getInt("slot", 0)
        val now = ZonedDateTime.now(Config.zone)
        val active = ActiveWindow.isActive(now)

        try {
            if (active) withContext(Dispatchers.IO) { Refresher.run(applicationContext, now) }
            TrainWidget().updateAll(applicationContext)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // catena cancellata (riavvio): non programmare altro
        } catch (e: Exception) {
            // Qualunque altro errore: si riprova al prossimo giro
        }

        val delayMs = if (active) {
            TimeUnit.MINUTES.toMillis(Config.REFRESH_MINUTES)
        } else {
            val untilNext = Duration.between(now, ActiveWindow.nextStart(now)).toMillis()
            untilNext.coerceIn(TimeUnit.MINUTES.toMillis(1), TimeUnit.HOURS.toMillis(6))
        }
        Scheduler.enqueueChain(applicationContext, slot = 1 - slot, delayMs = delayMs)
        return Result.success()
    }
}

class SafetyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        withContext(Dispatchers.IO) { Scheduler.startChainIfIdle(applicationContext) }
        return Result.success()
    }
}
