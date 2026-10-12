package se.partee71.fonder.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bakåtstacken med en egen stack per flik (NAV-7):
 * - appen startar på Översikt ([Screen.START]), som alltid ligger underst – bakåt från en annan
 *   flik går dit, och bakåt i Översikts rot lämnar appen;
 * - en flik som lämnas via flikraden behåller sin stack (och därmed skärmarnas tillstånd och
 *   ViewModels) tills man kommer tillbaka; en flik som lämnas med bakåt börjar om nästa gång;
 * - att välja fliken man står på gör ingenting.
 *
 * [entries] är det som `NavDisplay` visar: Översikts stack och, om en annan flik är vald, dess stack.
 * En flik som inte är besökt (eller lämnad med bakåt) har en tom stack.
 */
@Stable
class AppBackStack internal constructor(current: Screen, stacks: Map<Screen, List<AppKey>>) {
    private val stacks: Map<Screen, SnapshotStateList<AppKey>> = Screen.entries.associateWith { tab ->
        mutableStateListOf<AppKey>().apply { addAll(stacks[tab] ?: if (tab == START) listOf(tab.key) else emptyList()) }
    }

    /** Den valda fliken i flikraden. */
    var currentTab: Screen by mutableStateOf(current)
        private set

    constructor() : this(START, emptyMap())

    /** Flikens egen stack – tom för en flik som inte är besökt. */
    fun stack(tab: Screen): List<AppKey> = stacks.getValue(tab)

    val entries: List<AppKey>
        get() = if (currentTab == START) stack(START).toList() else stack(START) + stack(currentTab)

    /** Skärmen som visas. */
    val current: AppKey
        get() = entries.last()

    /**
     * Byter flik (flikraden och Översikts "Att besluta"). Den valda flikens stack återställs som den
     * lämnades; Översikt under den lämnas bara med sin rot, som vid `popUpTo(start)`.
     */
    fun select(tab: Screen) {
        if (tab == currentTab) return
        mutable(START).trimToRoot()
        if (tab != START && mutable(tab).isEmpty()) mutable(tab).add(tab.key)
        currentTab = tab
    }

    /** Öppnar [key] ovanpå den aktuella fliken; ligger den redan överst (ett dubbeltryck) händer inget. */
    fun push(key: AppKey) {
        require(key !is TabKey) { "Flikar väljs med select, inte push" }
        val stack = mutable(currentTab)
        if (stack.last() != key) stack.add(key)
    }

    /**
     * Stänger [key] om den är den översta skärmen – det en skärm anropar när den stänger sig själv,
     * så att ett andra anrop inte stänger skärmen under.
     */
    fun popIfTop(key: AppKey) {
        if (current == key) pop()
    }

    /**
     * Går tillbaka ett steg; `false` om det inte finns något kvar (Översikts rot – appen stängs).
     * Från en annan fliks rot går det till Översikt, och fliken börjar om nästa gång den väljs.
     */
    fun pop(): Boolean {
        val stack = mutable(currentTab)
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            currentTab != START -> {
                stack.clear()
                currentTab = START
            }
            else -> return false
        }
        return true
    }

    private fun mutable(tab: Screen): SnapshotStateList<AppKey> = stacks.getValue(tab)

    private fun SnapshotStateList<AppKey>.trimToRoot() {
        if (size > 1) removeRange(1, size)
    }

    internal fun save(): String =
        json.encodeToString(Saved.serializer(), Saved(currentTab, Screen.entries.map { stack(it).toList() }))

    @Serializable
    internal class Saved(val current: Screen, val stacks: List<List<AppKey>>)

    companion object {
        private val START = Screen.START
        private val json = Json

        /**
         * Läser tillbaka en sparad stack. Tolerant: en trasig eller äldre sparad sträng (en skärm som
         * inte finns längre, fel antal flikar) eller en vald flik utan stack ger en ny stack på
         * Översikt i stället för en krasch vid start – en tappad navigeringshistorik är ingen data.
         */
        internal fun restore(saved: String): AppBackStack {
            val state = runCatching { json.decodeFromString(Saved.serializer(), saved) }.getOrNull()
                ?: return AppBackStack()
            val stacks = Screen.entries.zip(state.stacks).toMap()
            val valid = state.stacks.size == Screen.entries.size &&
                stacks.all { (tab, stack) -> stack.isEmpty() || stack.first() == tab.key } &&
                stacks.getValue(START).isNotEmpty() &&
                stacks.getValue(state.current).isNotEmpty() &&
                stacks.values.all { stack -> stack.drop(1).none { it is TabKey } }
            return if (valid) AppBackStack(state.current, stacks) else AppBackStack()
        }

        /** Sparas som JSON, så att flikarnas stackar överlever rotation och processdöd. */
        val Saver: Saver<AppBackStack, String> = Saver(save = { it.save() }, restore = { restore(it) })
    }
}

@Composable
fun rememberAppBackStack(): AppBackStack = rememberSaveable(saver = AppBackStack.Saver) { AppBackStack() }
