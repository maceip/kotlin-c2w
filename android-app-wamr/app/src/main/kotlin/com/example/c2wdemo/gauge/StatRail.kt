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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val RailDarkBg = Color(0xFF0A0A14)
private val RailBorder = Color(0xFF333355)
private val RailGreen = Color(0xFF00FF88)
private val RailCyan = Color(0xFF00FFFF)
private val RailLabelColor = Color(0xFF9B4DCA)
private val RailSegmentOff = Color(0xFF0A1A0A)

/**
 * Vertical stat rail revealed as the "book spine" during predictive back gesture.
 * 48dp wide, full height. Shows live system metrics matching ZimStatusGauge.
 */
@Composable
fun StatRail(
    data: State<GaugeData>,
    modifier: Modifier = Modifier,
) {
    val gauge = data.value

    Column(
        modifier = modifier
            .width(48.dp)
            .fillMaxHeight()
            .background(RailDarkBg)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // --- RAM slime bar (fills bottom-to-top) ---
        RotatedLabel("RAM")
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, RailBorder.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
        ) {
            SpineSlimeBar(
                fillFraction = gauge.ramPercent,
                thermalFraction = gauge.thermalFraction,
                modifier = Modifier.matchParentSize(),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // --- RAM percent text ---
        val pct = (gauge.ramPercent * 100).toInt().coerceIn(0, 99)
        LedNumber(
            text = pct.toString().padStart(2, ' '),
            digitWidth = 8.dp,
            digitHeight = 13.dp,
            onColor = thermalColor(gauge.thermalFraction),
            offColor = RailSegmentOff,
        )

        Spacer(modifier = Modifier.height(6.dp))

        // --- Thermal indicator circle ---
        RotatedLabel("THM")
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(thermalColor(gauge.thermalFraction))
                .border(1.dp, RailBorder, CircleShape),
        )

        Spacer(modifier = Modifier.height(6.dp))

        // --- FPS ---
        RotatedLabel("FPS")
        val fpsText = gauge.fps.coerceIn(0, 99).toString().padStart(2, ' ')
        LedNumber(
            text = fpsText,
            digitWidth = 8.dp,
            digitHeight = 13.dp,
            onColor = RailGreen,
            offColor = RailSegmentOff,
        )

        Spacer(modifier = Modifier.height(6.dp))

        // --- Latency ---
        RotatedLabel("MS")
        val msText = gauge.latencyMs.coerceIn(0, 999).toString().padStart(3, ' ')
        LedNumber(
            text = msText,
            digitWidth = 7.dp,
            digitHeight = 11.dp,
            onColor = RailCyan,
            offColor = RailSegmentOff,
        )

        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun RotatedLabel(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            color = RailLabelColor,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        ),
        modifier = Modifier.rotate(-90f),
    )
}

/**
 * Simplified slime bar for the stat rail spine.
 * Same visual style as the main gauge SlimeBar.
 */
@Composable
private fun SpineSlimeBar(
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

        // Dark glass tube background
        drawRoundRect(
            color = Color(0xFF020808),
            cornerRadius = cornerR,
            size = size,
        )
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.4f),
                    Color(0xFF0A1A14).copy(alpha = 0.3f),
                    Color(0xFF0F2018).copy(alpha = 0.4f),
                    Color(0xFF0A1A14).copy(alpha = 0.3f),
                    Color.Black.copy(alpha = 0.4f),
                ),
            ),
            size = size,
        )

        if (fill > 0f) {
            val fillHeight = h * fill
            val fillTop = h - fillHeight

            // Cylindrical slime fill
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

            // Meniscus
            drawRect(
                color = glowColor.copy(alpha = 0.85f),
                topLeft = Offset(0f, fillTop),
                size = Size(w, 2f.coerceAtMost(fillHeight)),
            )
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

            // Specular highlight
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
        }

        // Glass rim highlights
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
