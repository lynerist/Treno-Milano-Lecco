package it.trenolecco

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    // Cambia a ogni ritorno nell'app (es. dopo le impostazioni di sistema) per rileggere i permessi
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { MainScreen(resumeTick.intValue) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

@Composable
private fun MainScreen(resumeTick: Int) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf(SnapshotStore.load(ctx)) }
    var loading by remember { mutableStateOf(false) }
    val now = ZonedDateTime.now(Config.zone)
    val active = ActiveWindow.isActive(now)

    suspend fun refresh() {
        loading = true
        snap = withContext(Dispatchers.IO) { Refresher.run(ctx) }
        TrainWidget().updateAll(ctx)
        loading = false
    }

    LaunchedEffect(Unit) {
        // Se c'è un widget sulla home, assicura che la sveglia di aggiornamento sia programmata
        Scheduler.ensureArmed(ctx)
        if (active) refresh()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Treno Lecco ⇄ Milano", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            if (active) "Controllo attivo (${ActiveWindow.describeWindows()})"
            else "Fuori orario: il widget riprende ${ActiveWindow.describeNext(now)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val s = snap
        val nowMs = now.toInstant().toEpochMilli()
        if (s != null && !s.isEmpty) {
            s.sections.forEach { sec ->
                Text(
                    sec.directionLabel,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
                val trains = sec.upcoming(nowMs)
                if (trains.isEmpty()) {
                    Text("Nessun treno nella prossima ora", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    trains.forEach { TrainCard(it, sec) }
                }
            }
            Fmt.warning(s, nowMs)?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
            if (s.fetchedAtMs > 0) {
                Text(
                    "Ultimo aggiornamento: ${Fmt.time(s.fetchedAtMs)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (s?.error != null) {
            Text("⚠ ${s.error}", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        } else {
            Text("Nessun dato ancora.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Button(onClick = { scope.launch { refresh() } }, enabled = !loading) {
            Text(if (loading) "Aggiorno…" else if (active) "Aggiorna ora" else "Controlla comunque")
        }
        UpdateReliability(resumeTick)
        Text(
            "Dati: ViaggiaTreno (API non ufficiale, include i treni Trenord). " +
                "Il ritardo compare solo quando il treno è partito dal capolinea.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrainCard(t: Train, sec: Section) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Palette.cardBg)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(sec.timeLabel.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Palette.gray)
                Text(Fmt.time(t.scheduledMs), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.white)
            }
            Column {
                Text(Fmt.shortStatus(t), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Fmt.statusColor(t))
                Text(Fmt.detail(t, sec.arrival), fontSize = 13.sp, color = Palette.gray)
            }
        }
    }
}

@Composable
private fun UpdateReliability(resumeTick: Int) {
    val ctx = LocalContext.current
    val exactOk = remember(resumeTick) { Scheduler.canUseExactAlarms(ctx) }
    val batteryOk = remember(resumeTick) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val last = remember(resumeTick) { Scheduler.lastRunMs(ctx) }
    val next = remember(resumeTick) { Scheduler.nextAlarmMs(ctx) }

    fun open(intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    if (!exactOk || !batteryOk) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Per aggiornarsi in orario", fontWeight = FontWeight.Bold)
                Text(
                    "Il telefono può ritardare gli aggiornamenti per risparmiare batteria. " +
                        "Consenti queste due impostazioni per far partire il widget alle 7:00 e alle 16:00 precise.",
                    fontSize = 13.sp
                )
                if (!exactOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Button(onClick = {
                        open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("Consenti sveglie precise") }
                }
                if (!batteryOk) {
                    Button(onClick = {
                        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("Escludi dal risparmio batteria") }
                }
            }
        }
    }

    val lastText = if (last > 0) Fmt.time(last) else "mai"
    val nextText = if (next > 0) Fmt.time(next) else "—"
    Text(
        "Controllo automatico: ultimo $lastText · prossimo $nextText" +
            (if (exactOk) "" else " · sveglie non precise") +
            (if (batteryOk) "" else " · risparmio batteria attivo"),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
