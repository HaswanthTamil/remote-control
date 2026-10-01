package com.remotecontrol.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMapTest {

    @Test
    fun `aliases resolve to evdev names`() {
        assertEquals("LEFTCTRL", KeyMap.normalize("ctrl"))
        assertEquals("LEFTCTRL", KeyMap.normalize("CONTROL"))
        assertEquals("LEFTALT", KeyMap.normalize("Alt"))
        assertEquals("LEFTSHIFT", KeyMap.normalize("SHIFT"))
        assertEquals("LEFTMETA", KeyMap.normalize("super"))
        assertEquals("LEFTMETA", KeyMap.normalize("Win"))
        assertEquals("A", KeyMap.normalize("KEY_A"))
    }

    @Test
    fun `combo plans hold modifiers and tap the final key`() {
        val plan = KeyMap.plan("CTRL+ALT+T")
        assertTrue(plan is KeyMap.Plan.Combination)
        plan as KeyMap.Plan.Combination
        assertEquals(listOf("LEFTCTRL", "LEFTALT"), plan.combo.modifiers)
        assertEquals("T", plan.combo.key)
    }

    @Test
    fun `single key names become taps`() {
        val plan = KeyMap.plan("esc")
        assertTrue(plan is KeyMap.Plan.Tap)
        assertEquals("ESC", (plan as KeyMap.Plan.Tap).key)
    }

    @Test
    fun `anything else is typed character by character`() {
        val plan = KeyMap.plan("Hi!")
        assertTrue(plan is KeyMap.Plan.Typing)
        val keys = (plan as KeyMap.Plan.Typing).keys
        assertEquals(3, keys.size)
        assertEquals(KeyMap.TypedKey("KEY_H", shift = true), keys[0])
        assertEquals(KeyMap.TypedKey("KEY_I", shift = false), keys[1])
        assertEquals(KeyMap.TypedKey("KEY_1", shift = true), keys[2])
    }

    @Test
    fun `shifted punctuation maps to the base key with shift`() {
        assertEquals(KeyMap.TypedKey("KEY_MINUS", shift = true), KeyMap.typeChar('_'))
        assertEquals(KeyMap.TypedKey("KEY_MINUS", shift = false), KeyMap.typeChar('-'))
        assertEquals(KeyMap.TypedKey("KEY_SEMICOLON", shift = true), KeyMap.typeChar(':'))
        assertEquals(KeyMap.TypedKey("KEY_SLASH", shift = true), KeyMap.typeChar('?'))
    }

    @Test
    fun `whitespace maps to named keys`() {
        assertEquals("KEY_SPACE", KeyMap.typeChar(' ')?.key)
        assertEquals("KEY_TAB", KeyMap.typeChar('\t')?.key)
        assertEquals("KEY_ENTER", KeyMap.typeChar('\n')?.key)
    }

    @Test
    fun `unmappable characters are skipped`() {
        assertNull(KeyMap.typeChar('\u2603'))
        assertNull(KeyMap.plan("   "))
    }

    @Test
    fun `modifiers are recognised as keys`() {
        assertTrue(KeyMap.isKnownKey("LEFTCTRL"))
        assertTrue(KeyMap.isKnownKey("F12"))
        assertTrue(!KeyMap.isKnownKey("KEY_NOPE"))
    }
}