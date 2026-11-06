package it.trenolecco

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Aggiornamenti puntuali con sveglie di sistema (AlarmManager) invece dei ritardi di WorkManager,
 * che Android può rimandare di molti minuti (risparmio energetico, Doze, standby dell'app).
 *
 * Ogni sveglia → [RefreshReceiver]: per prima cosa programma la sveglia successiva (così la catena
 * non si spezza mai), poi aggiorna i dati (solo in fascia attiva) e ridisegna il widget.
 * [SafetyWorker] (ogni 15 minuti) è la rete di sicurezza: riarma la sveglia se manca e aggiorna
 * comunque il widget se le sveglie venissero bloccate.
 */
object Scheduler {
    private const val PREFS = "scheduler"
    private const val ACTION_REFRESH = "it.trenolecco.REFRESH"
    private const val SAFETY = "safety"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun alarmManager(ctx: Context) = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun refreshIntent(ctx: Context) =
        Intent(ctx, RefreshReceiver::class.java).setAction(ACTION_REFRESH)

    private fun pendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 0, refreshIntent(ctx),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Su Android 12+ le sveglie esatte richiedono il consenso dell'utente ("Sveglie e promemoria"). */
    fun canUseExactAlarms(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(ctx).canScheduleExactAlarms()

    fun hasWidget(ctx: Context): Boolean =
        AppWidgetManager.getInstance(ctx)
            .getAppWidgetIds(ComponentName(ctx, TrainWidgetReceiver::class.java)).isNotEmpty()

    /** Programma la prossima sveglia (sostituisce quella esistente). */
    fun arm(ctx: Context, now: ZonedDateTime = ZonedDateTime.now(Config.zone)) {
        val at = ActiveWindow.nextWake(now, Config.REFRESH_MINUTES).toInstant().toEpochMilli()
        val am = alarmManager(ctx)
        val pi = pendingIntent(ctx)
        try {
            if (canUseExactAlarms(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            // Consenso alle sveglie esatte revocato nel frattempo: ripiega su quella approssimata
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        prefs(ctx).edit().putLong("next", at).apply()
    }

    /** Nuovo widget, avvio, cambio ora o aggiornamento dell'app: riarma e aggiorna subito. */
    fun restart(ctx: Context) {
        arm(ctx)
        ctx.sendBroadcast(refreshIntent(ctx))
        enqueueSafety(ctx)
    }

    /** Riarma solo se la sveglia risulta mancante o scaduta (chiamata a ogni apertura dell'app). */
    fun ensureArmed(ctx: Context) {
        if (!hasWidget(ctx)) return
        val next = prefs(ctx).getLong("next", 0L)
        if (next < System.currentTimeMillis() - 60_000L) arm(ctx)
        enqueueSafety(ctx)
    }

    fun stopAll(ctx: Context) {
        alarmManager(ctx).cancel(pendingIntent(ctx))
        WorkManager.getInstance(ctx).cancelUniqueWork(SAFETY)
        prefs(ctx).edit().remove("next").apply()
    }

    private fun enqueueSafety(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            SAFETY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SafetyWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    fun markRun(ctx: Context) {
        prefs(ctx).edit().putLong("last", System.currentTimeMillis()).apply()
    }

    fun lastRunMs(ctx: Context): Long = prefs(ctx).getLong("last", 0L)
    fun nextAlarmMs(ctx: Context): Long = prefs(ctx).getLong("next", 0L)
}

/** Riceve la sveglia: riarma la successiva, poi aggiorna dati e widget. */
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        val now = ZonedDateTime.now(Config.zone)

        // Prima di tutto la sveglia successiva: la catena resta viva anche se il resto fallisce
        Scheduler.arm(app, now)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (ActiveWindow.isActive(now)) Refresher.run(app, now)
                Scheduler.markRun(app)
                TrainWidget().updateAll(app)
            } catch (e: Exception) {
                // Si riprova alla sveglia successiva
            } finally {
                pending.finish()
            }
        }
    }
}

/** Dopo riavvio, aggiornamento dell'app o cambio ora/fuso le sveglie vanno riprogrammate. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val app = context.applicationContext
                if (Scheduler.hasWidget(app)) Scheduler.restart(app)
            }
        }
    }
}

/** Rete di sicurezza ogni ~15 minuti, nel caso le sveglie vengano bloccate dal telefono. */
class SafetyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Scheduler.hasWidget(ctx)) return Result.success()

        val now = ZonedDateTime.now(Config.zone)
        val nowMs = now.toInstant().toEpochMilli()
        if (Scheduler.nextAlarmMs(ctx) < nowMs - 60_000L) Scheduler.arm(ctx, now)

        try {
            if (ActiveWindow.isActive(now)) {
                val fetchedAt = SnapshotStore.load(ctx)?.fetchedAtMs ?: 0L
                if (nowMs - fetchedAt > 4 * 60_000L) {
                    withContext(Dispatchers.IO) { Refresher.run(ctx, now) }
                    Scheduler.markRun(ctx)
                }
            }
            TrainWidget().updateAll(ctx)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Si riprova al prossimo giro
        }
        return Result.success()
    }
}
