package de.marlon.orinpilot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OP.Surface)
            .border(1.dp, OP.Outline, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = OP.TextDim,
                letterSpacing = 1.2.sp,
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** Horizontaler Auslastungsbalken 0..100 */
@Composable
fun LoadBar(value: Float, color: Color = OP.Green, modifier: Modifier = Modifier, barHeight: Int = 6) {
    val v = (value / 100f).coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(barHeight.dp)
            .clip(RoundedCornerShape(50))
            .background(OP.SurfaceHi)
    ) {
        Box(
            Modifier
                .fillMaxWidth(v)
                .height(barHeight.dp)
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

fun loadColor(pct: Float): Color = when {
    pct >= 85f -> OP.Red
    pct >= 60f -> OP.Amber
    else -> OP.Green
}

fun tempColor(c: Float): Color = when {
    c >= 80f -> OP.Red
    c >= 65f -> OP.Amber
    else -> OP.Cyan
}

/** Kleiner Verlaufsgraph */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    min: Float? = 0f,
    max: Float? = null,
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = min ?: values.min()
        val hi = (max ?: values.max()).let { if (it - lo < 1e-3f) lo + 1f else it }
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * size.height
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, size.height); fill.lineTo(x, y) }
            else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        fill.lineTo((values.size - 1) * stepX, size.height)
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        // aktueller Punkt
        val lastY = size.height - ((values.last() - lo) / (hi - lo)).coerceIn(0f, 1f) * size.height
        drawCircle(color, 3.dp.toPx(), Offset((values.size - 1) * stepX, lastY))
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    color: Color,
    history: List<Float>,
    modifier: Modifier = Modifier,
    sub: String = "",
    histMax: Float? = null,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(OP.Surface)
            .border(1.dp, OP.Outline, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = OP.TextDim)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = OP.Text)
            Spacer(Modifier.width(3.dp))
            Text(unit, style = MaterialTheme.typography.bodySmall, color = OP.TextDim, modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(sub, style = MaterialTheme.typography.bodySmall, color = OP.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Sparkline(history, color, Modifier.fillMaxWidth().height(34.dp), max = histMax)
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, color = OP.TextDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Text(value.ifBlank { "–" }, color = OP.Text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

/** Dialog mit Befehlsausgabe (Monospace, kopierbar) */
@Composable
fun OutputDialog(title: String, text: String?, busy: Boolean, onDismiss: () -> Unit) {
    val clip = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 460.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0A0C0B))
                    .padding(10.dp)
            ) {
                if (busy && text.isNullOrEmpty()) {
                    CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.Center), strokeWidth = 3.dp)
                } else {
                    SelectionContainer {
                        Text(
                            text.orEmpty().ifBlank { "(keine Ausgabe)" },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = OP.Text,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
        dismissButton = {
            TextButton(onClick = { clip.setText(AnnotatedString(text.orEmpty())) }, enabled = !text.isNullOrEmpty()) {
                Text("Kopieren")
            }
        },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String = "Ausführen",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (danger) OP.Red else OP.Green)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = OP.TextDim, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Busy(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
    }
}

/** einfache Zeile aus Chips, horizontal scrollbar */
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}
