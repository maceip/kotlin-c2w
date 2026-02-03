package com.example.c2wdemo.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ZimDarkBg = Color(0xFF0A0A14)
private val ZimFrameBorder = Color(0xFF333355)
private val ZimCyan = Color(0xFF00FFFF)
private val ZimSlimeGreen = Color(0xFF00FF88)
private val ZimSlimeDark = Color(0xFF004422)
private val ZimSlimeGlow = Color(0xFF33FF99)
private val ZimPink = Color(0xFFFF1493)
private val ZimSegmentOff = Color(0xFF0A1A0A)
private val ZimLabelColor = Color(0xFF9B4DCA)

data class GaugeData(
    val ramPercent: Float = 0f,     // 0.0 to 1.0 — fraction of RAM used
    val fps: Int = 0,
    val latencyMs: Int = 0,
)

/**
 * Narrow vertical Zim-themed status gauge.
 *
 * Layout (top to bottom):
 *   - RAM % as LED number
 *   - Vertical slime progress bar (fills up = more RAM used)
 *   - FPS as LED number
 *   - Latency as LED number with "ms" label
 */
@Composable
fun ZimStatusGauge(
    data: State<GaugeData>,
    modifier: Modifier = Modifier,
) {
    val gauge = data.value
    val shape = RoundedCornerShape(6.dp)

    Column(
        modifier = modifier
            .width(52.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(ZimDarkBg)
            .border(1.dp, ZimFrameBorder, shape)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // --- Top section: RAM percentage LED readout ---
        val ramPct = (gauge.ramPercent * 100).toInt().coerceIn(0, 99)
        val ramText = ramPct.toString().padStart(2, ' ')
        LedNumber(
            text = ramText,
            digitWidth = 10.dp,
            digitHeight = 16.dp,
            onColor = if (gauge.ramPercent > 0.85f) ZimPink else ZimSlimeGreen,
            offColor = ZimSegmentOff,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // --- Middle section: Vertical slime bar (RAM usage) ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(3.dp))
                .border(1.dp, ZimFrameBorder, RoundedCornerShape(3.dp)),
        ) {
            SlimeBar(
                fillFraction = gauge.ramPercent,
                modifier = Modifier.matchParentSize(),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // --- Bottom section: FPS ---
        LedLabel(text = "FP")
        val fpsText = gauge.fps.coerceIn(0, 99).toString().padStart(2, ' ')
        LedNumber(
            text = fpsText,
            digitWidth = 10.dp,
            digitHeight = 16.dp,
            onColor = ZimCyan,
            offColor = ZimSegmentOff,
        )

        Spacer(modifier = Modifier.height(2.dp))

        // --- Bottom section: Latency ---
        LedLabel(text = "MS")
        val latText = gauge.latencyMs.coerceIn(0, 999).toString().padStart(3, ' ')
        LedNumber(
            text = latText,
            digitWidth = 8.dp,
            digitHeight = 13.dp,
            onColor = ZimCyan,
            offColor = ZimSegmentOff,
        )
    }
}

/**
 * Tiny LED-style label using the same segment aesthetic but as static text.
 */
@Composable
private fun LedLabel(text: String) {
    androidx.compose.foundation.text.BasicText(
        text = text,
        style = androidx.compose.ui.text.TextStyle(
            color = ZimLabelColor,
            fontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp),
        ),
    )
}

@Preview(name = "Idle", widthDp = 52, heightDp = 300, showBackground = true, backgroundColor = 0xFF0D0D1A)
@Composable
private fun PreviewGaugeIdle() {
    val state = remember { mutableStateOf(GaugeData(ramPercent = 0.35f, fps = 0, latencyMs = 0)) }
    ZimStatusGauge(data = state)
}

@Preview(name = "Active", widthDp = 52, heightDp = 300, showBackground = true, backgroundColor = 0xFF0D0D1A)
@Composable
private fun PreviewGaugeActive() {
    val state = remember { mutableStateOf(GaugeData(ramPercent = 0.62f, fps = 30, latencyMs = 42)) }
    ZimStatusGauge(data = state)
}

@Preview(name = "High Load", widthDp = 52, heightDp = 300, showBackground = true, backgroundColor = 0xFF0D0D1A)
@Composable
private fun PreviewGaugeHighLoad() {
    val state = remember { mutableStateOf(GaugeData(ramPercent = 0.92f, fps = 60, latencyMs = 850)) }
    ZimStatusGauge(data = state)
}

/**
 * Vertical green slime bar. Fills from bottom up.
 * Full = all green = no RAM left.
 */
@Composable
private fun SlimeBar(
    fillFraction: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val fill = fillFraction.coerceIn(0f, 1f)

        // Dark background (empty part)
        drawRoundRect(
            color = Color(0xFF020808),
            cornerRadius = CornerRadius(4f, 4f),
            size = size,
        )

        if (fill > 0f) {
            val fillHeight = h * fill
            val fillTop = h - fillHeight

            // Slime gradient: bright at top of fill, darker at bottom
            val slimeBrush = Brush.verticalGradient(
                colors = listOf(ZimSlimeGlow, ZimSlimeGreen, ZimSlimeDark),
                startY = fillTop,
                endY = h,
            )

            drawRect(
                brush = slimeBrush,
                topLeft = Offset(0f, fillTop),
                size = Size(w, fillHeight),
            )

            // Slime surface glow line at the fill level
            drawRect(
                color = ZimSlimeGlow.copy(alpha = 0.7f),
                topLeft = Offset(0f, fillTop),
                size = Size(w, 2f.coerceAtMost(fillHeight)),
            )

            // Bubble-like highlights (decorative dots at fixed positions in the fill)
            val bubbleRadius = w * 0.06f
            val bubblePositions = listOf(0.2f, 0.45f, 0.7f, 0.85f)
            for (pos in bubblePositions) {
                val bubbleY = h - (h * pos * fill)
                if (bubbleY >= fillTop && bubbleY <= h) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.15f),
                        radius = bubbleRadius,
                        center = Offset(w * (0.3f + pos * 0.4f), bubbleY),
                    )
                }
            }
        }
    }
}
