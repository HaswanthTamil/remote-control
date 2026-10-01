package com.remotecontrol.ui.lock

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrol.config.AppConfig
import com.remotecontrol.ui.components.PrimaryButton
import com.remotecontrol.ui.theme.Palette

/**
 * The app's front door. Nothing else in the app renders until the user approves
 * with biometrics (or types the passcode fallback), which is the native
 * equivalent of the web client's `rc_unlocked` gate.
 */
@Composable
fun LockScreen(
    config: AppConfig,
    gate: BiometricGate?,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = gate?.status?.collectAsStateWithLifecycle()?.value ?: BiometricStatus.UNAVAILABLE
    val authenticating = gate?.authenticating?.collectAsStateWithLifecycle()?.value == true
    val event = gate?.events?.collectAsStateWithLifecycle()?.value

    var passcode by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var promptOnEntry by rememberSaveable { mutableStateOf(false) }

    val biometricsUsable = config.biometricLockEnabled && status == BiometricStatus.AVAILABLE

    // Re-arm the fingerprint/face prompt every time the lock screen appears, so
    // opening the app always offers biometrics first instead of a bare password.
    LaunchedEffect(gate, status, config.biometricLockEnabled) {
        if (!promptOnEntry && config.biometricLockEnabled && status == BiometricStatus.AVAILABLE) {
            promptOnEntry = true
            gate?.authenticate()
        }
    }

    LaunchedEffect(event) {
        when (val current = event) {
            is GateEvent.Unlocked -> onUnlocked()
            is GateEvent.Failed -> error = current.message
            GateEvent.Fallback -> error = null
            null -> Unit
        }
        if (event != null) gate?.consumeEvent()
    }

    val statusLine = when {
        !config.biometricLockEnabled -> "Biometric lock is off - passcode only"
        status == BiometricStatus.AVAILABLE -> "Biometrics ready - look at your device or touch the sensor"
        status == BiometricStatus.NONE_ENROLLED -> "No biometrics enrolled on this device - using the passcode"
        status == BiometricStatus.NO_HARDWARE -> "No biometric hardware - using the passcode"
        else -> "Biometrics unavailable - using the passcode"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Palette.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Palette.Surface)
                    .border(1.dp, Palette.BorderSoft, RoundedCornerShape(26.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (biometricsUsable) Icons.Rounded.Fingerprint else Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = if (biometricsUsable) Palette.AccentBlue else Palette.TextMuted,
                    modifier = Modifier.size(34.dp),
                )
            }

            Spacer(Modifier.height(22.dp))
            Text("Locked", style = MaterialTheme.typography.displaySmall, color = Palette.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Approve with biometrics to open Remote Control",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(18.dp))
            StatusChip(statusLine, biometricsUsable)

            Spacer(Modifier.height(28.dp))

            if (biometricsUsable) {
                PrimaryButton(
                    text = if (authenticating) "Waiting for approval…" else "Unlock with biometrics",
                    icon = Icons.Rounded.Fingerprint,
                    enabled = !authenticating,
                    container = Palette.AccentBlue,
                    onClick = { gate?.authenticate() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(18.dp))
                FallbackDivider()
                Spacer(Modifier.height(18.dp))
            }

            OutlinedTextField(
                value = passcode,
                onValueChange = {
                    passcode = it.filter { char -> char.isLetterOrDigit() }.take(16)
                    error = null
                },
                label = { Text("Passcode") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (passcode == config.passcode) {
                            passcode = ""
                            onUnlocked()
                        } else {
                            error = "Wrong passcode"
                            passcode = ""
                        }
                    },
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.AccentBlue,
                    unfocusedBorderColor = Palette.Border,
                    focusedContainerColor = Palette.BackgroundDeep,
                    unfocusedContainerColor = Palette.BackgroundDeep,
                    focusedTextColor = Palette.TextPrimary,
                    unfocusedTextColor = Palette.TextPrimary,
                    cursorColor = Palette.AccentBlue,
                    focusedLabelColor = Palette.TextMuted,
                    unfocusedLabelColor = Palette.TextGhost,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = "Unlock with passcode",
                onClick = {
                    if (passcode == config.passcode) {
                        passcode = ""
                        error = null
                        onUnlocked()
                    } else {
                        error = "Wrong passcode"
                        passcode = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))
            Text(
                text = error ?: " ",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Danger,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, highlight: Boolean) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (highlight) Palette.AccentBlue.copy(alpha = 0.10f) else Palette.Surface)
            .border(
                1.dp,
                if (highlight) Palette.AccentBlue.copy(alpha = 0.35f) else Palette.BorderSoft,
                CircleShape,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (highlight) Palette.AccentBlue else Palette.TextGhost),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (highlight) Palette.AccentBlue else Palette.TextMuted,
        )
    }
}

@Composable
private fun FallbackDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Palette.BorderSoft),
        )
        Text("or use passcode", style = MaterialTheme.typography.labelSmall, color = Color(0xFF59616B))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(Palette.BorderSoft),
        )
    }
}

/** Local convenience so callers don't need `LocalContext` plumbing. */
@Composable
fun rememberBiometricGate(): BiometricGate? {
    val context = LocalContext.current
    return remember(context) { BiometricGate.fragmentActivity(context)?.let { BiometricGate(it) } }
}