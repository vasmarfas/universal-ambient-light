package com.vasmarfas.UniversalAmbientLight.common.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** Текст с телефона режется на куски: латиница уходит событиями клавиатуры, остальное вставкой. */
class TvInputTest {

    @Test
    fun `latin text stays in one piece`() {
        assertEquals(listOf("hello, world!"), TvInput.chunks("hello, world!"))
    }

    @Test
    fun `cyrillic is split from latin`() {
        assertEquals(listOf("где", " abc"), TvInput.chunks("где abc"))
    }

    @Test
    fun `a line break becomes its own piece`() {
        assertEquals(listOf("a", "\n", "b"), TvInput.chunks("a\r\nb"))
    }

    @Test
    fun `spaces stay with the latin text`() {
        assertEquals(listOf("мир", " 42 "), TvInput.chunks("мир 42 "))
    }
}
