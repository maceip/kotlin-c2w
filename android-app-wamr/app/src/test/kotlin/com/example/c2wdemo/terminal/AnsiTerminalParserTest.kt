package com.example.c2wdemo.terminal

import android.text.style.ForegroundColorSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnsiTerminalParserTest {

    private lateinit var parser: AnsiTerminalParser

    @Before
    fun setUp() {
        parser = AnsiTerminalParser()
    }

    @Test
    fun `plain text passes through unchanged`() {
        val result = parser.parse("hello world")
        assertEquals("hello world", result.toString())
    }

    @Test
    fun `empty string produces empty output`() {
        val result = parser.parse("")
        assertEquals("", result.toString())
    }

    @Test
    fun `newlines are preserved`() {
        val result = parser.parse("line1\nline2\n")
        assertEquals("line1\nline2\n", result.toString())
    }

    @Test
    fun `cursor position query ESC 6n is stripped`() {
        val result = parser.parse("\u001B[6n")
        assertEquals("", result.toString())
    }

    @Test
    fun `SGR reset ESC 0m produces plain text`() {
        val result = parser.parse("\u001B[0mhello")
        assertEquals("hello", result.toString())
    }

    @Test
    fun `colored text has escape codes stripped from output string`() {
        // ESC[1;34m = bold blue, ESC[m = reset
        val result = parser.parse("\u001B[1;34mbin\u001B[m")
        assertEquals("bin", result.toString())
    }

    @Test
    fun `colored text produces ForegroundColorSpan`() {
        // ESC[31m = red
        val result = parser.parse("\u001B[31mERROR\u001B[0m")
        assertEquals("ERROR", result.toString())
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertTrue("Expected at least one color span", spans.isNotEmpty())
    }

    @Test
    fun `multiple colors in one string`() {
        val result = parser.parse("\u001B[32mgreen\u001B[31mred\u001B[0m")
        assertEquals("greenred", result.toString())
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertTrue("Expected multiple color spans", spans.size >= 2)
    }

    @Test
    fun `clear screen ESC 2J is stripped`() {
        val result = parser.parse("\u001B[2Jhello")
        assertEquals("hello", result.toString())
    }

    @Test
    fun `cursor home ESC H is stripped`() {
        val result = parser.parse("\u001B[Hhello")
        assertEquals("hello", result.toString())
    }

    @Test
    fun `erase line ESC K is stripped`() {
        val result = parser.parse("\u001B[Khello")
        assertEquals("hello", result.toString())
    }

    @Test
    fun `private mode sequences are stripped`() {
        // ESC[?25h = show cursor
        val result = parser.parse("\u001B[?25hhello")
        assertEquals("hello", result.toString())
    }

    @Test
    fun `carriage return is stripped`() {
        val result = parser.parse("hello\rworld")
        assertEquals("helloworld", result.toString())
    }

    @Test
    fun `parser state persists across calls`() {
        // Set color in first chunk
        parser.parse("\u001B[31m")
        // Text in second chunk should have the color
        val result = parser.parse("colored")
        assertEquals("colored", result.toString())
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertTrue("Color should persist across parse calls", spans.isNotEmpty())
    }

    @Test
    fun `reset clears parser state`() {
        parser.parse("\u001B[31m")
        parser.reset()
        val result = parser.parse("plain")
        assertEquals("plain", result.toString())
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertEquals("After reset, no color spans expected", 0, spans.size)
    }

    @Test
    fun `incomplete escape sequence at end of chunk`() {
        // Send ESC without the [ — parser should be in ESCAPE state
        val result1 = parser.parse("before\u001B")
        assertEquals("before", result1.toString())
        // Next chunk completes it
        val result2 = parser.parse("[31mafter")
        assertEquals("after", result2.toString())
    }

    @Test
    fun `bright colors 90 to 97 are handled`() {
        val result = parser.parse("\u001B[91mbright red\u001B[0m")
        assertEquals("bright red", result.toString())
        val spans = result.getSpans(0, result.length, ForegroundColorSpan::class.java)
        assertTrue("Expected span for bright color", spans.isNotEmpty())
    }

    @Test
    fun `bold flag is handled`() {
        val result = parser.parse("\u001B[1;32mbold green\u001B[0m")
        assertEquals("bold green", result.toString())
    }

    @Test
    fun `typical ls output parses correctly`() {
        // Real ls --color output: ESC[1;34mbin ESC[m
        val input = "\u001B[1;34mbin\u001B[m  \u001B[1;34metc\u001B[m  \u001B[1;32mrun.sh\u001B[m\n"
        val result = parser.parse(input)
        assertEquals("bin  etc  run.sh\n", result.toString())
    }
}
