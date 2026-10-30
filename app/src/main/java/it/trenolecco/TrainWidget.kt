package it.trenolecco

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import java.time.ZonedDateTime

class TrainWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = SnapshotStore.load(context)
        val now = ZonedDateTime.now(Config.zone)
        provideContent { WidgetContent(snap, now) }
    }
}

class TrainWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrainWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Scheduler.restart(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Scheduler.stopAll(context)
    }
}

@Composable
private fun small(text: String, color: androidx.compose.ui.graphics.Color = Palette.gray, bold: Boolean = false) {
    Text(
        text = text,
        maxLines = 2,
        style = TextStyle(
            color = ColorProvider(color),
            fontSize = 12.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    )
}

@Composable
private fun WidgetContent(snap: Snapshot?, now: ZonedDateTime) {
    val nowMs = now.toInstant().toEpochMilli()
    val openApp = actionStartActivity(Intent(LocalContext.current, MainActivity::class.java))
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Palette.cardBg))
            .cornerRadius(20.dp)
            .padding(12.dp)
            .clickable(openApp),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        if (!ActiveWindow.isActive(now)) {
            Text(
                "Fuori orario",
                style = TextStyle(color = ColorProvider(Palette.white), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            )
            small("Riprende ${ActiveWindow.describeNext(now)}")
            return@Column
        }

        if (snap == null || (snap.fetchedAtMs == 0L && snap.trains.isEmpty())) {
            Text(
                "Aggiornamento…",
                style = TextStyle(color = ColorProvider(Palette.white), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            )
            snap?.error?.let { small("⚠ $it", Palette.amber) }
            return@Column
        }

        val upcoming = snap.trains.filter { it.effectiveMs >= nowMs - 60_000 }

        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                snap.directionLabel,
                maxLines = 1,
                style = TextStyle(color = ColorProvider(Palette.gray), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(GlanceModifier.defaultWeight())
            small(if (snap.fetchedAtMs > 0) "agg. ${Fmt.time(snap.fetchedAtMs)}" else "")
        }
        Spacer(GlanceModifier.height(4.dp))

        if (upcoming.isEmpty()) {
            Text(
                "Nessun treno nella prossima ora",
                style = TextStyle(color = ColorProvider(Palette.white), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            )
        } else {
            val t = upcoming[0]
            Row(verticalAlignment = Alignment.Vertical.Bottom) {
                Text(
                    Fmt.time(t.scheduledMs),
                    style = TextStyle(color = ColorProvider(Palette.white), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(GlanceModifier.width(10.dp))
                Text(
                    Fmt.shortStatus(t),
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(Fmt.statusColor(t)), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                )
            }
            small(Fmt.detail(t))
            upcoming.getOrNull(1)?.let { n ->
                Spacer(GlanceModifier.height(2.dp))
                small("Poi ${Fmt.time(n.scheduledMs)} · ${Fmt.shortStatus(n)}", Fmt.statusColor(n))
            }
        }

        Fmt.warning(snap, nowMs)?.let {
            Spacer(GlanceModifier.height(4.dp))
            small(it, Palette.amber, bold = true)
        }
    }
}
