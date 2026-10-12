package se.partee71.fonder.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bakåtstacken per flik (NAV-7): Översikt underst, flikbyte som minns flikens stack, bakåt via
 * Översikt, ingen dubblett överst och att stackarna överlever att sparas och återställas (rotation
 * och processdöd). Ren JVM – inget Compose behövs.
 */
class AppBackStackTest {

    @Test
    fun `appen startar på Översikt`() {
        val stack = AppBackStack()

        assertEquals(listOf<AppKey>(OversiktKey), stack.entries)
        assertEquals(Screen.Oversikt, stack.currentTab)
        assertEquals(OversiktKey, stack.current)
    }

    @Test
    fun `en annan flik ligger ovanpå Översikt`() {
        val stack = AppBackStack()

        stack.select(Screen.Innehav)

        assertEquals(listOf(OversiktKey, InnehavKey), stack.entries)
        assertEquals(Screen.Innehav, stack.currentTab)
    }

    @Test
    fun `varje flik minns sin stack när man byter flik`() {
        val stack = AppBackStack()
        stack.select(Screen.Innehav)
        stack.push(FondDetaljKey("SHB0000442"))

        stack.select(Screen.Beslut)
        assertEquals(listOf(OversiktKey, BeslutKey), stack.entries)
        stack.select(Screen.Innehav)

        assertEquals(listOf(OversiktKey, InnehavKey, FondDetaljKey("SHB0000442")), stack.entries)
    }

    @Test
    fun `bakåt från en annan flik går till Översikt och fliken börjar om`() {
        val stack = AppBackStack()
        stack.select(Screen.Handelser)
        stack.push(SwitchWatchKey(3))

        assertTrue(stack.pop())
        assertEquals(listOf(OversiktKey, HandelserKey), stack.entries)
        assertTrue(stack.pop())
        assertEquals(listOf<AppKey>(OversiktKey), stack.entries)
        assertEquals(Screen.Oversikt, stack.currentTab)
        assertEquals(emptyList<AppKey>(), stack.stack(Screen.Handelser))

        stack.select(Screen.Handelser)
        assertEquals(listOf(OversiktKey, HandelserKey), stack.entries)
    }

    @Test
    fun `bakåt i Översikts rot lämnar appen`() {
        val stack = AppBackStack()

        assertFalse(stack.pop())
        assertEquals(listOf<AppKey>(OversiktKey), stack.entries)
    }

    @Test
    fun `att välja fliken man står på gör ingenting`() {
        val stack = AppBackStack()
        stack.select(Screen.Beslut)
        stack.push(FacitKey)

        stack.select(Screen.Beslut)

        assertEquals(listOf(OversiktKey, BeslutKey, FacitKey), stack.entries)
    }

    @Test
    fun `Översikt lämnas bara med sin rot under en annan flik`() {
        val stack = AppBackStack()
        stack.push(SettingsKey)

        stack.select(Screen.Beslut)

        assertEquals(listOf(OversiktKey, BeslutKey), stack.entries)
    }

    @Test
    fun `ett dubbeltryck öppnar skärmen en gång`() {
        val stack = AppBackStack()

        stack.push(SettingsKey)
        stack.push(SettingsKey)

        assertEquals(listOf(OversiktKey, SettingsKey), stack.entries)
    }

    @Test
    fun `en skärm som stänger sig två gånger stänger inte skärmen under`() {
        val stack = AppBackStack()
        stack.push(SettingsKey)
        stack.push(RiskProfileKey)

        stack.popIfTop(RiskProfileKey)
        stack.popIfTop(RiskProfileKey)

        assertEquals(listOf(OversiktKey, SettingsKey), stack.entries)
    }

    @Test
    fun `flikar väljs, de läggs inte på stacken`() {
        assertThrows(IllegalArgumentException::class.java) { AppBackStack().push(InnehavKey) }
    }

    @Test
    fun `stackarna överlever att sparas och återställas`() {
        val stack = AppBackStack()
        stack.select(Screen.Beslut)
        stack.push(SwitchWatchKey(5))
        stack.push(SwitchWatchPickerKey(5))
        stack.select(Screen.Innehav)
        stack.push(FondDetaljKey("SHB0000442"))

        val restored = AppBackStack.restore(stack.save())

        assertEquals(stack.entries, restored.entries)
        assertEquals(Screen.Innehav, restored.currentTab)
        restored.select(Screen.Beslut)
        assertEquals(listOf(OversiktKey, BeslutKey, SwitchWatchKey(5), SwitchWatchPickerKey(5)), restored.entries)
        assertEquals(emptyList<AppKey>(), restored.stack(Screen.Handelser))
    }

    @Test
    fun `en trasig eller äldre sparad stack ger en ny stack på Översikt`() {
        val fresh = listOf<AppKey>(OversiktKey)
        val valid = AppBackStack().apply { select(Screen.Innehav) }.save()
        listOf(
            "",
            "inte json",
            "{}",
            // En skärm som inte finns längre (äldre version).
            valid.replace("InnehavKey", "BorttagenKey"),
            // Fel antal flikar.
            """{"current":"Oversikt","stacks":[["se.partee71.fonder.ui.navigation.OversiktKey"]]}""",
            // Översikt utan rot, och en vald flik med tom stack.
            """{"current":"Oversikt","stacks":[[],[],[],[]]}""",
            """{"current":"Beslut","stacks":[[{"type":"se.partee71.fonder.ui.navigation.OversiktKey"}],[],[],[]]}""",
        ).forEach { saved ->
            val restored = AppBackStack.restore(saved)
            assertEquals("återställt från: $saved", fresh, restored.entries)
            assertEquals(Screen.Oversikt, restored.currentTab)
        }
    }

    @Test
    fun `sparad stack med en flik mitt i stacken avvisas`() {
        val stack = AppBackStack()
        stack.push(SettingsKey)
        val saved = stack.save().replace(SettingsKey::class.qualifiedName!!, BeslutKey::class.qualifiedName!!)

        assertEquals(listOf<AppKey>(OversiktKey), AppBackStack.restore(saved).entries)
    }
}
