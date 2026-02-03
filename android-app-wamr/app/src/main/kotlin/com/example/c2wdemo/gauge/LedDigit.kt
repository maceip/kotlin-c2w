package com.example.c2wdemo.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 7-segment layout:
//  aaa
// f   b
//  ggg
// e   c
//  ddd

private val SEGMENT_MAP = mapOf(
    '0' to booleanArrayOf(true, true, true, true, true, true, false),
    '1' to booleanArrayOf(false, true, true, false, false, false, false),
    '2' to booleanArrayOf(true, true, false, true, true, false, true),
    '3' to booleanArrayOf(true, true, true, true, false, false, true),
    '4' to booleanArrayOf(false, true, true, false, false, true, true),
    '5' to booleanArrayOf(true, false, true, true, false, true, true),
    '6' to booleanArrayOf(true, false, true, true, true, true, true),
    '7' to booleanArrayOf(true, true, true, false, false, false, false),
    '8' to booleanArrayOf(true, true, true, true, true, true, true),
    '9' to booleanArrayOf(true, true, true, true, false, true, true),
    '-' to booleanArrayOf(false, false, false, false, false, false, true),
    ' ' to booleanArrayOf(false, false, false, false, false, false, false),
)

/**
 * Draw a single 7-segment LED digit.
 */
@Composable
fun LedDigit(
    char: Char,
    modifier: Modifier = Modifier,
    width: Dp = 14.dp,
    height: Dp = 22.dp,
    onColor: Color = Color(0xFF00FF88),
    offColor: Color = Color(0xFF0A1A0A),
) {
    val segments = SEGMENT_MAP[char] ?: SEGMENT_MAP[' ']!!
    Canvas(modifier = modifier.size(width, height)) {
        val w = size.width
        val h = size.height
        val seg = w * 0.18f     // segment thickness
        val gap = seg * 0.25f   // gap between segments
        val halfH = h / 2f

        // a: top horizontal
        drawSegmentH(
            x = gap, y = 0f, len = w - 2 * gap, thick = seg,
            color = if (segments[0]) onColor else offColor
        )
        // b: top-right vertical
        drawSegmentV(
            x = w - seg, y = gap, len = halfH - 1.5f * gap, thick = seg,
            color = if (segments[1]) onColor else offColor
        )
        // c: bottom-right vertical
        drawSegmentV(
            x = w - seg, y = halfH + 0.5f * gap, len = halfH - 1.5f * gap, thick = seg,
            color = if (segments[2]) onColor else offColor
        )
        // d: bottom horizontal
        drawSegmentH(
            x = gap, y = h - seg, len = w - 2 * gap, thick = seg,
            color = if (segments[3]) onColor else offColor
        )
        // e: bottom-left vertical
        drawSegmentV(
            x = 0f, y = halfH + 0.5f * gap, len = halfH - 1.5f * gap, thick = seg,
            color = if (segments[4]) onColor else offColor
        )
        // f: top-left vertical
        drawSegmentV(
            x = 0f, y = gap, len = halfH - 1.5f * gap, thick = seg,
            color = if (segments[5]) onColor else offColor
        )
        // g: middle horizontal
        drawSegmentH(
            x = gap, y = halfH - seg / 2, len = w - 2 * gap, thick = seg,
            color = if (segments[6]) onColor else offColor
        )
    }
}

private fun DrawScope.drawSegmentH(x: Float, y: Float, len: Float, thick: Float, color: Color) {
    drawRect(color = color, topLeft = Offset(x, y), size = Size(len, thick))
}

private fun DrawScope.drawSegmentV(x: Float, y: Float, len: Float, thick: Float, color: Color) {
    drawRect(color = color, topLeft = Offset(x, y), size = Size(thick, len))
}

@Preview(name = "Digit 8", widthDp = 20, heightDp = 30, showBackground = true, backgroundColor = 0xFF0A0A14)
@Composable
private fun PreviewLedDigit() {
    LedDigit(char = '8', onColor = Color(0xFF00FF88), offColor = Color(0xFF0A1A0A))
}

@Preview(name = "Number 42", widthDp = 50, heightDp = 30, showBackground = true, backgroundColor = 0xFF0A0A14)
@Composable
private fun PreviewLedNumber() {
    LedNumber(text = "42", onColor = Color(0xFF00FFFF), offColor = Color(0xFF0A1A0A))
}

/**
 * Draw a multi-digit LED number string (e.g. "128", "60", "12").
 */
@Composable
fun LedNumber(
    text: String,
    modifier: Modifier = Modifier,
    digitWidth: Dp = 14.dp,
    digitHeight: Dp = 22.dp,
    onColor: Color = Color(0xFF00FF88),
    offColor: Color = Color(0xFF0A1A0A),
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        text.forEach { ch ->
            LedDigit(
                char = ch,
                width = digitWidth,
                height = digitHeight,
                onColor = onColor,
                offColor = offColor,
            )
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.size(2.dp)
            )
        }
    }
}
