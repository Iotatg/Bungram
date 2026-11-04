package app.bungram.feature.auth

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bungram.core.design.BungramBlue
import app.bungram.core.design.BungramBlueSoft
import app.bungram.core.design.Canvas
import app.bungram.core.design.Danger
import app.bungram.core.design.SurfaceMuted
import app.bungram.core.design.TextOnBlue
import app.bungram.core.design.TextPrimary
import app.bungram.core.design.TextSecondary
import app.bungram.core.design.TextTertiary
import kotlinx.coroutines.delay

/**
 * Sign-in, two steps.
 *
 * Layout follows the reference: a big left-aligned title, one field, one full-width button, and
 * nothing else competing for attention. The step change is a horizontal slide so the direction of
 * travel matches the back gesture, and a wrong code shakes the boxes instead of showing a dialog.
 */
@Composable
fun LoginScreen(
    state: AuthUiState,
    onDetectCountry: (Context) -> Unit,
    onSelectCountry: (Country) -> Unit,
    onNationalChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onToggleServerField: () -> Unit,
    onRequestOtp: () -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
    onUseDevCode: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { onDetectCountry(context) }

    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                if (targetState == AuthStep.CODE) {
                    (slideInHorizontally(tween(320)) { it / 4 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(160)))
                } else {
                    (slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(320)) { it / 4 } + fadeOut(tween(160)))
                }
            },
            label = "authStep",
        ) { step ->
            when (step) {
                AuthStep.PHONE -> PhoneStep(
                    state = state,
                    onOpenPicker = { pickerOpen = true },
                    onNationalChange = onNationalChange,
                    onServerUrlChange = onServerUrlChange,
                    onToggleServerField = onToggleServerField,
                    onContinue = onRequestOtp,
                )

                AuthStep.CODE -> CodeStep(
                    state = state,
                    onCodeChange = onCodeChange,
                    onVerify = onVerify,
                    onResend = onResend,
                    onBack = onBack,
                    onUseDevCode = onUseDevCode,
                )
            }
        }
    }

    if (pickerOpen) {
        CountryPickerSheet(
            selected = state.country,
            onPick = {
                onSelectCountry(it)
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

/* ------------------------------------------------------------------------------ phone step */

@Composable
private fun PhoneStep(
    state: AuthUiState,
    onOpenPicker: () -> Unit,
    onNationalChange: (String) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onToggleServerField: () -> Unit,
    onContinue: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { delay(350); focus.requestFocus() }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(28.dp))
        BrandMark()
        Spacer(Modifier.height(28.dp))

        Text(
            "Your phone number",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Bungram uses your number to reach you. We never show it to anyone you have not written to.",
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            color = TextSecondary,
        )

        Spacer(Modifier.height(26.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceMuted)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenPicker)
                    .padding(vertical = 12.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.country?.flag ?: "🌐",
                    fontSize = 20.sp,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "+${state.country?.dial ?: ""}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                )
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = "Choose country",
                    modifier = Modifier.size(16.dp),
                    tint = TextTertiary,
                )
            }
            Box(Modifier.width(1.dp).height(22.dp).background(Color(0xFFDDE1EA)))
            Spacer(Modifier.width(12.dp))
            BasicTextField(
                value = Countries.formatNational(state.national),
                onValueChange = onNationalChange,
                modifier = Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                textStyle = TextStyle(fontSize = 17.sp, color = TextPrimary, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(BungramBlue),
                decorationBox = { inner ->
                    if (state.national.isEmpty()) {
                        Text("Phone number", fontSize = 17.sp, color = TextTertiary)
                    }
                    inner()
                },
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "We will send a 6-digit code by SMS.",
            fontSize = 11.sp,
            color = TextTertiary,
        )

        Spacer(Modifier.weight(1f))

        ErrorBanner(state.error)

        PrimaryButton(
            label = "Continue",
            enabled = state.canContinue && !state.busy,
            busy = state.busy,
            onClick = {
                keyboard?.hide()
                onContinue()
            },
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = if (state.showServerField) "Server · tap to hide" else "Server · ${state.serverUrl}",
            fontSize = 10.5.sp,
            color = TextTertiary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleServerField)
                .padding(vertical = 10.dp),
        )
        AnimatedVisibility(visible = state.showServerField) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceMuted)
                        .padding(horizontal = 14.dp, vertical = 4.dp)
                ) {
                    BasicTextField(
                        value = state.serverUrl,
                        onValueChange = onServerUrlChange,
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 12.5.sp, color = TextPrimary),
                        cursorBrush = SolidColor(BungramBlue),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/* ------------------------------------------------------------------------------- code step */

@Composable
private fun CodeStep(
    state: AuthUiState,
    onCodeChange: (String) -> Unit,
    onVerify: () -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
    onUseDevCode: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shake = remember { Animatable(0f) }

    LaunchedEffect(Unit) { delay(250); focus.requestFocus() }

    LaunchedEffect(state.shakeTick) {
        if (state.shakeTick > 0) {
            // Three decaying swings: enough to read as "no", short enough to type again.
            listOf(16f, -14f, 10f, -6f, 0f).forEach { target ->
                shake.animateTo(target, tween(55))
            }
        }
    }

    // A full code is submitted automatically — one fewer tap, and the common case.
    LaunchedEffect(state.code) {
        if (state.code.length == 6 && !state.busy) onVerify()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SurfaceMuted)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowBack, "Back", Modifier.size(19.dp), tint = TextPrimary)
            }
        }
        Spacer(Modifier.height(26.dp))
        Text(
            "Enter your code",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
            color = TextPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Sent to ${state.e164}",
            fontSize = 12.5.sp,
            color = TextSecondary,
        )
        Spacer(Modifier.height(26.dp))

        Box(Modifier.offset(x = shake.value.dp)) {
            CodeBoxes(
                code = state.code,
                onCodeChange = onCodeChange,
                focusRequester = focus,
                enabled = !state.busy,
                onComplete = {
                    keyboard?.hide()
                },
            )
        }

        Spacer(Modifier.height(18.dp))
        ErrorBanner(state.error)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.resendInSec > 0) "Resend in ${state.resendInSec}s" else "Didn't get the code?",
                fontSize = 11.5.sp,
                color = TextSecondary,
            )
            if (state.resendInSec == 0) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "Resend",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = BungramBlue,
                    modifier = Modifier.clickable(enabled = !state.busy, onClick = onResend),
                )
            }
        }

        if (state.devCode != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(BungramBlueSoft)
                    .clickable(onClick = onUseDevCode)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Dev build · code ${state.devCode}", fontSize = 10.5.sp, color = BungramBlue)
                Spacer(Modifier.width(6.dp))
                Text("use", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = BungramBlue)
            }
        }

        Spacer(Modifier.weight(1f))

        PrimaryButton(
            label = "Verify",
            enabled = state.code.length == 6 && !state.busy,
            busy = state.busy,
            onClick = {
                keyboard?.hide()
                onVerify()
            },
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CodeBoxes(
    code: String,
    onCodeChange: (String) -> Unit,
    focusRequester: FocusRequester,
    enabled: Boolean,
    onComplete: () -> Unit,
) {
    Box {
        // One real field behind six drawn boxes: no focus juggling, paste works, backspace works.
        BasicTextField(
            value = code,
            onValueChange = onCodeChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .focusRequester(focusRequester),
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            cursorBrush = SolidColor(Color.Transparent),
            decorationBox = { inner -> Box(Modifier.fillMaxSize()) { inner() } },
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            repeat(6) { i ->
                val filled = i < code.length
                val active = i == code.length
                Box(
                    Modifier
                        .weight(1f)
                        .height(58.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (filled) BungramBlueSoft else SurfaceMuted)
                        .then(
                            if (active) Modifier.background(BungramBlue.copy(alpha = 0.08f)) else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (filled) {
                        Text(
                            code[i].toString(),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BungramBlue,
                        )
                    } else if (active) {
                        // Blinking caret so the eye lands on the next box.
                        Box(
                            Modifier
                                .size(width = 2.dp, height = 22.dp)
                                .background(BungramBlue.copy(alpha = 0.6f))
                        )
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------- country picker */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CountryPickerSheet(
    selected: Country?,
    onPick: (Country) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }

    val filtered = remember(query) {
        if (query.isBlank()) emptyList()
        else Countries.all.filter { c ->
            c.name.contains(query, ignoreCase = true) ||
                c.iso.equals(query, ignoreCase = true) ||
                c.dial.contains(query.removePrefix("+"))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Canvas,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            Text(
                "Choose a country",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = TextPrimary,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceMuted)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, Modifier.size(16.dp), tint = TextTertiary)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary),
                    cursorBrush = SolidColor(BungramBlue),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("Country, code or +dial", fontSize = 13.sp, color = TextTertiary)
                        inner()
                    },
                )
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Close, "Clear", Modifier.size(16.dp).clickable { query = "" },
                        tint = TextTertiary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            LazyColumn(Modifier.fillMaxWidth()) {
                if (query.isBlank()) {
                    item { SectionLabel("Frequently used") }
                    items(Countries.common) { c -> CountryRow(c, c.iso == selected?.iso, onPick) }
                    item { SectionLabel("All countries") }
                    items(Countries.all) { c -> CountryRow(c, c.iso == selected?.iso, onPick) }
                } else {
                    items(filtered) { c -> CountryRow(c, c.iso == selected?.iso, onPick) }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "No country matches \"$query\"",
                                fontSize = 12.sp,
                                color = TextTertiary,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextTertiary,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun CountryRow(country: Country, selected: Boolean, onPick: (Country) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onPick(country) }
            .background(if (selected) BungramBlueSoft else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(country.flag, fontSize = 18.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            country.name,
            fontSize = 13.5.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("+${country.dial}", fontSize = 12.5.sp, color = TextSecondary)
    }
}

/* --------------------------------------------------------------------------------- pieces */

/**
 * Shown only while the stored token is being checked. Deliberately short: the splash exists to
 * avoid a white flash, not to be looked at.
 */
@Composable
fun SplashScreen() {
    val scale = remember { Animatable(0.85f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 380f))
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Canvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.scale(scale.value)) { BrandMark() }
            Spacer(Modifier.height(18.dp))
            Text(
                "Bungram",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(BungramBlue, Color(0xFF7B8BFF)))),
        contentAlignment = Alignment.Center,
    ) {
        Text("B", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextOnBlue)
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) BungramBlue else BungramBlue.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = TextOnBlue,
                strokeWidth = 2.dp,
            )
        } else {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextOnBlue)
        }
    }
}

@Composable
private fun ErrorBanner(message: String?) {
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(120)),
    ) {
        Text(
            message.orEmpty(),
            fontSize = 11.5.sp,
            color = Danger,
            modifier = Modifier.padding(bottom = 10.dp),
        )
    }
}
