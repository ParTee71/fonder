package se.partee71.fonder.ui.components

import android.content.ClipData
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.customActions
import kotlinx.coroutines.launch
import se.partee71.fonder.R

/**
 * Gör ett fondnamn kopierbart (UI-16, issue #245): ett **långtryck** lägger [name] i urklipp med
 * haptisk återkoppling, och TalkBack får åtgärden "Kopiera fondnamn". Android visar själv att något
 * kopierats från Android 13; appen förutsätter Android 15+ (Mats beslut i #245), så ingen egen
 * bekräftelse – på Android 11–12 kopieras namnet med bara haptik. Bara namnet kopieras, aldrig
 * radens övriga text.
 *
 * Sätts på namnets text eller på en rad/ett kort som bara visar en fond. Långtrycket läses i det
 * första passet ([PointerEventPass.Initial]), alltså före både klickbara barn och en klickbar förälder:
 * när trycket blivit ett långtryck förbrukas resten av gesten, så att raden eller kortet inte också
 * öppnas eller fälls ut. Ett vanligt tryck och en rullning släpps igenom orörda. Använd inte på en
 * rad där långtryck redan betyder något annat (Transaktioner: ta bort).
 *
 * Enda stället i appen som rör urklipp (`ui-forbidden.txt`, [component-only]).
 */
fun Modifier.copyableFundName(name: String): Modifier = this then CopyableFundNameElement(name)

private data class CopyableFundNameElement(val name: String) : ModifierNodeElement<CopyableFundNameNode>() {
    override fun create() = CopyableFundNameNode(name)

    override fun update(node: CopyableFundNameNode) {
        node.name = name
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "copyableFundName"
        properties["name"] = this@CopyableFundNameElement.name
    }
}

private class CopyableFundNameNode(var name: String) :
    DelegatingNode(),
    CompositionLocalConsumerModifierNode,
    SemanticsModifierNode {

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                detectLongPressConsumingRest {
                    currentValueOf(LocalHapticFeedback).performHapticFeedback(HapticFeedbackType.LongPress)
                    copy()
                }
            }
        )
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        val label = currentValueOf(LocalContext).getString(R.string.copy_fund_name)
        customActions = listOf(
            CustomAccessibilityAction(label) {
                copy()
                true
            }
        )
    }

    private fun copy() {
        val clipboard = currentValueOf(LocalClipboard)
        val label = currentValueOf(LocalContext).getString(R.string.fund_name_clip_label)
        val entry = ClipEntry(ClipData.newPlainText(label, name))
        coroutineScope.launch { clipboard.setClipEntry(entry) }
    }
}

/**
 * Väntar på ett långtryck i [PointerEventPass.Initial]. Släpps fingret, rör det sig mer än
 * `touchSlop` eller kommer ett andra finger före `longPressTimeoutMillis` lämnas gesten orörd. Annars
 * körs [onLongPress] och resten av gesten förbrukas fram till att alla fingrar släppts.
 */
private suspend fun PointerInputScope.detectLongPressConsumingRest(onLongPress: () -> Unit) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            val moved = change != null && (change.position - down.position).getDistance() > viewConfiguration.touchSlop
        } while (change != null && change.pressed && !moved && event.changes.size == 1)
        true
    }
    if (interrupted == null) {
        onLongPress()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}
