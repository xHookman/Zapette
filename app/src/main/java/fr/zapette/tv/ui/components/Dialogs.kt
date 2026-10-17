package fr.zapette.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import fr.zapette.tv.engine.PythonHost
import fr.zapette.tv.ui.AppViewModel
import fr.zapette.tv.ui.theme.Z
import fr.zapette.tv.ui.theme.ZType
import kotlinx.coroutines.delay

/* Dialogs: lists to pick from, yes/no, messages and text input. */


/** Dialogs opened by the add-on (through PythonHost) and by the app itself. */
@Composable
fun AppDialogs(vm: AppViewModel) {
    val request by vm.engine.host.request.collectAsState()
    val choice by vm.messages.choice.collectAsState()
    val textAsk by vm.messages.textAsk.collectAsState()

    // key(): a new dialog gets fresh state (focus, scroll, checks)
    key(request, choice, textAsk) {
        when (val r = request) {
            is PythonHost.Request.Select -> SelectDialog(
                title = r.heading,
                options = r.options.map { it.label + if (it.label2.isNotBlank()) "  ·  ${it.label2}" else "" },
                preselect = r.preselect,
                multi = r.multi,
                onDismiss = { r.answer.complete(if (r.multi) "[]" else "-1") },
                onPick = { r.answer.complete(it.toString()) },
                onMulti = { list -> r.answer.complete(list.joinToString(",", "[", "]")) },
            )
            is PythonHost.Request.YesNo -> ConfirmDialog(
                title = r.heading, message = r.message,
                yes = r.yesLabel.ifBlank { "Oui" }, no = r.noLabel.ifBlank { "Non" },
                onAnswer = { r.answer.complete(it) },
            )
            is PythonHost.Request.Message -> MessageDialog(r.heading, r.message) { r.answer.complete(Unit) }
            is PythonHost.Request.Input -> InputDialog(
                title = r.heading.ifBlank { "Saisie" }, initial = r.default, hidden = r.hidden, kind = r.kind,
                onDone = { r.answer.complete(it) },
            )
            null -> {
                val ask = textAsk
                val c = choice
                when {
                    ask != null -> InputDialog(
                        title = ask.title, initial = ask.initial, hidden = ask.hidden, kind = "text",
                        onDone = { value -> vm.messages.askText(null); if (value != null) ask.onDone(value) },
                    )
                    c != null -> SelectDialog(
                        title = c.title, options = c.options, preselect = c.selected, multi = c.multi,
                        onDismiss = { vm.messages.dismissChoice() },
                        onPick = { vm.messages.dismissChoice(); if (it >= 0) c.onPick(it) },
                        onMulti = { vm.messages.dismissChoice(); c.onMulti(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogFrame(onDismiss: () -> Unit, title: String, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Z.Scrim), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(640.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Z.Surface)
                    .border(1.dp, Z.Line, RoundedCornerShape(20.dp))
                    .padding(28.dp)
            ) {
                if (title.isNotBlank()) {
                    Text(title, style = ZType.Headline.copy(fontSize = 24.sp), color = Z.Text, maxLines = 2)
                    Spacer(Modifier.height(18.dp))
                }
                content()
            }
        }
    }
}

@Composable
fun SelectDialog(
    title: String,
    options: List<String>,
    preselect: Int,
    multi: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onMulti: (List<Int>) -> Unit,
) {
    val checked = remember { mutableStateListOf<Int>() }
    val first = remember { FocusRequester() }
    val start = preselect.coerceIn(0, (options.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    DialogFrame(onDismiss, title) {
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = 460.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(options) { index, option ->
                FocusSurface(
                    onClick = {
                        if (multi) { if (index in checked) checked.remove(index) else checked.add(index) }
                        else onPick(index)
                    },
                    modifier = Modifier.fillMaxWidth().then(if (index == start) Modifier.focusRequester(first) else Modifier),
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.02f,
                    container = Color.Transparent,
                    focusedContainer = Color.White,
                    ring = false,
                ) { focused ->
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val c = if (focused) Color.Black else Z.Text
                        val isOn = if (multi) index in checked else index == preselect
                        Text(option, style = ZType.Body.copy(fontSize = 17.sp), color = c, modifier = Modifier.weight(1f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (isOn) Icon(Icons.Filled.Check, null, tint = if (focused) Color.Black else Z.Live)
                    }
                }
            }
        }
        if (multi) {
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ZButton("Valider", onClick = { onMulti(checked.sorted()) }, primary = true)
                ZButton("Annuler", onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, yes: String, no: String, onAnswer: (Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    DialogFrame({ onAnswer(false) }, title) {
        if (message.isNotBlank()) {
            Text(message, style = ZType.Body.copy(fontSize = 17.sp), color = Z.Muted)
            Spacer(Modifier.height(24.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZButton(yes, onClick = { onAnswer(true) }, primary = true, modifier = Modifier.focusRequester(focus))
            ZButton(no, onClick = { onAnswer(false) })
        }
    }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
}

@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    DialogFrame(onDismiss, title) {
        Text(message, style = ZType.Body.copy(fontSize = 17.sp), color = Z.Muted, maxLines = 14, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(24.dp))
        ZButton("OK", onClick = onDismiss, primary = true, modifier = Modifier.focusRequester(focus))
    }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
}

@Composable
fun InputDialog(title: String, initial: String, hidden: Boolean, kind: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    DialogFrame({ onDone(null) }, title) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Z.Surface2)
                .border(2.dp, Z.Line, RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Z.Text, fontSize = 20.sp),
                cursorBrush = SolidColor(Z.Live),
                visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when {
                        kind == "number" -> KeyboardType.Number
                        hidden -> KeyboardType.Password
                        title.contains("mail", true) || title.contains("identifiant", true) -> KeyboardType.Email
                        else -> KeyboardType.Text
                    },
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onDone(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ZButton("Valider", onClick = { onDone(text) }, primary = true)
            ZButton("Annuler", onClick = { onDone(null) })
        }
    }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
}
