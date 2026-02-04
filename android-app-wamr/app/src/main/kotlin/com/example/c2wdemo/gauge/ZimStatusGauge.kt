package com.example.c2wdemo.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

private val ZimDarkBg = Color(0xFF0A0A14)
private val ZimFrameBorder = Color(0xFF333355)
private val ZimCyan = Color(0xFF00FFFF)
private val ZimSegmentOff = Color(0xFF0A1A0A)
private val ZimLabelColor = Color(0xFF9B4DCA)

data class GaugeData(
    val ramPercent: Float = 0f,     // 0.0 to 1.0 — fraction of RAM used
    val fps: Int = 0,
    val latencyMs: Int = 0,
    val thermalFraction: Float = 0f, // 0.0 = cool, 1.0 = emergency
)

/**
 * Compact vertical Zim-themed status gauge.
 * Sits on the right frame edge, replacing the border.
 *
 * Layout (top to bottom):
 *   - 3D glass-tube slime bar (RAM usage, thermal-colored)
 *   - FPS as LED number
 *   - Latency as LED number with "MS" label
 */
@Composable
fun ZimStatusGauge(
    data: State<GaugeData>,
    modifier: Modifier = Modifier,
) {
    val gauge = data.value
    val shape = RoundedCornerShape(4.dp)

    Column(
        modifier = modifier
            .width(36.dp)
            .height(110.dp)
            .clip(shape)
            .background(ZimDarkBg)
            .border(1.dp, ZimFrameBorder, shape)
            .padding(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // --- Slime tube: 3D glass tube showing RAM usage ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, ZimFrameBorder.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
        ) {
            SlimeBar(
                fillFraction = gauge.ramPercent,
                thermalFraction = gauge.thermalFraction,
                modifier = Modifier.matchParentSize(),
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // --- FPS ---
        LedLabel(text = "FP")
        val fpsText = gauge.fps.coerceIn(0, 99).toString().padStart(2, ' ')
        LedNumber(
            text = fpsText,
            digitWidth = 7.dp,
            digitHeight = 11.dp,
            onColor = ZimCyan,
            offColor = ZimSegmentOff,
        )

        Spacer(modifier = Modifier.height(1.dp))

        // --- Latency ---
        LedLabel(text = "MS")
        val latText = gauge.latencyMs.coerceIn(0, 999).toString().padStart(3, ' ')
        LedNumber(
            text = latText,
            digitWidth = 6.dp,
            digitHeight = 9.dp,
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
            fontSize = androidx.compose.ui.unit.TextUnit(7f, androidx.compose.ui.unit.TextUnitType.Sp),
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp),
        ),
    )
}

@Preview(name = "Idle", widthDp = 36, heightDp = 110, showBackground = true, backgroundColor = 0xFF0D0D1A)
@Composable
private fun PreviewGaugeIdle() {
    val state = remember { mutableStateOf(GaugeData(ramPercent = 0.35f, fps = 0, latencyMs = 0, thermalFraction = 0.05f)) }
    ZimStatusGauge(data = state)
}

@Preview(name = "Active", widthDp = 36, heightDp = 110, showBackground = true, backgroundColor = 0xFF0D0D1A)
@Composable
private fun PreviewGaugeActive() {
    val state = remember { mutableStateOf(GaugeData(ramPercent = 0.62f, fps = 30, latencyMs = 42, thermalFraction = 0.3f)) }
    ZimStatusGauge(data = state)
}

@Preview(name = "High Load", widthDp = 36, heightDp = 110, showBackground = true, backgroundColor = 0xFF0D0D1A)
@Composable
private fun PreviewGaugeHighLoad() {
    val state = remember { mutableStateOf(GaugeData(ramPercent = 0.92f, fps = 60, latencyMs = 850, thermalFraction = 0.55f)) }
    ZimStatusGauge(data = state)
}

@Preview(name = "Empty", widthDp = 36, heightDp = 110, showBackground = true, backgroundColor = 0xFF0D0D1A)
@Composable
private fun PreviewGaugeEmpty() {
    val state = remember { mutableStateOf(GaugeData(ramPercent = 0f, fps = 0, latencyMs = 0, thermalFraction = 0f)) }
    ZimStatusGauge(data = state)
}

// 4-stop thermal gradient: cool → warming → hot → critical
private val ThermalStops = listOf(
    0.00f to Color(0xFF00FF88), // Zim green (cool)
    0.35f to Color(0xFFCCFF00), // yellow-green (warming)
    0.60f to Color(0xFFFF8800), // orange (hot)
    0.85f to Color(0xFFFF1A1A), // red (critical)
)

/** Interpolates across the 4-stop thermal gradient. */
internal fun thermalColor(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    for (i in 0 until ThermalStops.lastIndex) {
        val (startF, startC) = ThermalStops[i]
        val (endF, endC) = ThermalStops[i + 1]
        if (f <= endF) {
            val t = ((f - startF) / (endF - startF)).coerceIn(0f, 1f)
            return lerp(startC, endC, t)
        }
    }
    return ThermalStops.last().second
}

/**
 * 3D glass-tube slime bar. Fills from bottom up.
 * Cylindrical gradient creates a tube appearance.
 * Color reflects device thermal state via [thermalFraction].
 * Empty tube (fillFraction == 0) shows only the dark glass tube.
 */
@Composable
private fun SlimeBar(
    fillFraction: Float,
    thermalFraction: Float,
    modifier: Modifier = Modifier,
) {
    val baseColor = thermalColor(thermalFraction)
    val glowColor = lerp(baseColor, Color.White, 0.4f)
    val darkColor = lerp(baseColor, Color.Black, 0.6f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val fill = fillFraction.coerceIn(0f, 1f)
        val cornerR = CornerRadius(w * 0.35f, w * 0.35f)

        // --- Dark glass tube background (cylindrical 3D look) ---
        // Base dark fill
        drawRoundRect(
            color = Color(0xFF020808),
            cornerRadius = cornerR,
            size = size,
        )
        // Cylindrical shading: darker edges, slightly lighter center
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF000000).copy(alpha = 0.4f),
                    Color(0xFF0A1A14).copy(alpha = 0.3f),
                    Color(0xFF0F2018).copy(alpha = 0.4f),
                    Color(0xFF0A1A14).copy(alpha = 0.3f),
                    Color(0xFF000000).copy(alpha = 0.4f),
                ),
            ),
            size = size,
        )
        // Glass tube left-edge specular highlight (always visible)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.07f),
                    Color.Transparent,
                ),
                startX = w * 0.08f,
                endX = w * 0.35f,
            ),
            size = size,
        )

        // --- Slime fill (only when memory is committed) ---
        if (fill > 0f) {
            val fillHeight = h * fill
            val fillTop = h - fillHeight

            // Main cylindrical slime: horizontal gradient for 3D tube
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        darkColor,
                        lerp(darkColor, baseColor, 0.7f),
                        lerp(baseColor, glowColor, 0.4f),
                        glowColor,
                        lerp(baseColor, glowColor, 0.4f),
                        lerp(darkColor, baseColor, 0.7f),
                        darkColor,
                    ),
                ),
                topLeft = Offset(0f, fillTop),
                size = Size(w, fillHeight),
            )

            // Vertical depth: brighter at meniscus, darker at bottom
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.35f),
                        Color.Transparent,
                        darkColor.copy(alpha = 0.25f),
                    ),
                    startY = fillTop,
                    endY = h,
                ),
                topLeft = Offset(0f, fillTop),
                size = Size(w, fillHeight),
            )

            // Meniscus glow line at the fill level
            drawRect(
                color = glowColor.copy(alpha = 0.85f),
                topLeft = Offset(0f, fillTop),
                size = Size(w, 2f.coerceAtMost(fillHeight)),
            )
            // Softer glow below meniscus
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        glowColor.copy(alpha = 0.3f),
                        Color.Transparent,
                    ),
                    startY = fillTop + 2f,
                    endY = fillTop + 8f,
                ),
                topLeft = Offset(0f, fillTop + 2f),
                size = Size(w, 6f.coerceAtMost(fillHeight - 2f).coerceAtLeast(0f)),
            )

            // Specular highlight on glass over the slime (left edge)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.22f),
                        Color.White.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                    startX = w * 0.05f,
                    endX = w * 0.4f,
                ),
                topLeft = Offset(0f, fillTop),
                size = Size(w * 0.4f, fillHeight),
            )

            // Bubble highlights scattered in the slime
            val bubbleRadius = w * 0.07f
            val bubblePositions = listOf(0.15f, 0.35f, 0.55f, 0.75f, 0.9f)
            for (pos in bubblePositions) {
                val bubbleY = h - (h * pos * fill)
                if (bubbleY >= fillTop && bubbleY <= h) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.16f),
                        radius = bubbleRadius,
                        center = Offset(w * (0.55f + pos * 0.2f), bubbleY),
                    )
                }
            }
        }

        // --- Glass rim highlights (top and bottom tube caps) ---
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.1f),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = 4f,
            ),
            cornerRadius = cornerR,
            size = Size(w, 4f),
        )
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.White.copy(alpha = 0.06f),
                ),
                startY = h - 4f,
                endY = h,
            ),
            cornerRadius = cornerR,
            topLeft = Offset(0f, h - 4f),
            size = Size(w, 4f),
        )
    }
}
