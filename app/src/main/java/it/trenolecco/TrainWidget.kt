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
private fun small(
    text: String,
    color: androidx.compose.ui.graphics.Color = Palette.gray,
    bold: Boolean = false,
    size: Int = 11,
    lines: Int = 1
) {
    Text(
        text = text,
        maxLines = lines,
        style = TextStyle(
            color = ColorProvider(color),
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
        )
    )
}

@Composable
private fun DirectionBlock(sec: Section, nowMs: Long) {
    val upcoming = sec.upcoming(nowMs)
    small(sec.directionLabel.uppercase(), Palette.gray, bold = true, size = 11)
    if (upcoming.isEmpty()) {
        small("Nessun treno nella prossima ora", Palette.white, bold = true, size = 14)
        return
    }
    val t = upcoming[0]
    Row(verticalAlignment = Alignment.Vertical.Bottom) {
        Text(
            Fmt.time(t.scheduledMs),
            style = TextStyle(color = ColorProvider(Palette.white), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            Fmt.shortStatus(t),
            maxLines = 1,
            style = TextStyle(color = ColorProvider(Fmt.statusColor(t)), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        )
    }
    val next = upcoming.getOrNull(1)
    val nextText = next?.let {
        " · poi ${Fmt.time(it.scheduledMs)}" + if (it.status == TrainStatus.DELAYED) " (+${it.delayMin})" else ""
    } ?: ""
    small(Fmt.detail(t) + nextText)
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
            small("Riprende ${ActiveWindow.describeNext(now)}", size = 12, lines = 2)
            return@Column
        }

        if (snap == null || snap.isEmpty) {
            Text(
                "Aggiornamento…",
                style = TextStyle(color = ColorProvider(Palette.white), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            )
            snap?.error?.let { small("⚠ $it", Palette.amber, size = 12, lines = 2) }
            return@Column
        }

        snap.sections.forEachIndexed { i, sec ->
            if (i > 0) Spacer(GlanceModifier.height(8.dp))
            DirectionBlock(sec, nowMs)
        }

        Spacer(GlanceModifier.height(6.dp))
        val warn = Fmt.warning(snap, nowMs)
        if (warn != null) small(warn, Palette.amber, bold = true, lines = 2)
        else small("agg. ${Fmt.time(snap.fetchedAtMs)}")
    }
}
