package it.trenolecco

import android.os.Bundle
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
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }
}

@Composable
private fun MainScreen() {
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
        // Se c'è un widget sulla home, assicura che la catena di aggiornamenti sia attiva
        val hasWidget = GlanceAppWidgetManager(ctx).getGlanceIds(TrainWidget::class.java).isNotEmpty()
        if (hasWidget) Scheduler.restart(ctx)
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
                    trains.forEach { TrainCard(it) }
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
        Text(
            "Dati: ViaggiaTreno (API non ufficiale, include i treni Trenord). " +
                "Il ritardo compare solo quando il treno è partito dal capolinea.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrainCard(t: Train) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Palette.cardBg)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(Fmt.time(t.scheduledMs), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.white)
            Column {
                Text(Fmt.shortStatus(t), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Fmt.statusColor(t))
                Text(Fmt.detail(t), fontSize = 13.sp, color = Palette.gray)
            }
        }
    }
}
