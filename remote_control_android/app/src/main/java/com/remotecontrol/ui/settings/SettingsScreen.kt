package com.remotecontrol.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotecontrol.config.AppConfig
import com.remotecontrol.data.ConnectionState
import com.remotecontrol.data.RemoteSession
import com.remotecontrol.ui.components.ActionRow
import com.remotecontrol.ui.components.ConnectionPill
import com.remotecontrol.ui.components.HairLine
import com.remotecontrol.ui.components.IconAction as AppIconAction
import com.remotecontrol.ui.components.InfoRow
import com.remotecontrol.ui.components.PrimaryButton
import com.remotecontrol.ui.components.SectionCard
import com.remotecontrol.ui.components.SectionHeader
import com.remotecontrol.ui.components.TopBar
import com.remotecontrol.ui.components.orPlaceholder
import com.remotecontrol.ui.lock.BiometricGate
import com.remotecontrol.ui.lock.BiometricStatus
import com.remotecontrol.ui.theme.MonoTextStyle
import com.remotecontrol.ui.theme.Palette
import com.remotecontrol.ui.theme.Radii
import com.remotecontrol.ui.theme.Space

@Composable
fun SettingsScreen(session: RemoteSession, onBack: () -> Unit) {
    val config by session.settings.config.collectAsStateWithLifecycle()
    val connection by session.connection.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var serverUrl by rememberSaveable(config) { mutableStateOf(config.serverUrl) }
    var pairToken by rememberSaveable(config) { mutableStateOf(config.pairToken) }
    var passcode by rememberSaveable(config) { mutableStateOf(config.passcode) }
    var showPairToken by rememberSaveable { mutableStateOf(false) }
    var showPasscode by rememberSaveable { mutableStateOf(false) }
    var biometricLock by rememberSaveable(config) { mutableStateOf(config.biometricLockEnabled) }
    var lockOnBackground by rememberSaveable(config) { mutableStateOf(config.lockOnBackground) }
    var confirmRegenerate by rememberSaveable { mutableStateOf(false) }

    val biometricStatus = remember { BiometricGate.statusOf(context) }
    val biometricReady = biometricStatus == BiometricStatus.AVAILABLE

    val trimmedUrl = serverUrl.trim()
    val trimmedToken = pairToken.trim()
    val dirty = trimmedUrl != config.serverUrl ||
        trimmedToken != config.pairToken ||
        passcode != config.passcode ||
        biometricLock != config.biometricLockEnabled ||
        lockOnBackground != config.lockOnBackground

    val connected = connection == ConnectionState.READY

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        TopBar(
            title = "Settings",
            subtitle = when (connection) {
                ConnectionState.READY -> "Connected to ${config.relayHost}"
                ConnectionState.SUPERSEDED -> "Slot taken by another client"
                else -> "Not connected"
            },
            compact = true,
            onBack = onBack,
            modifier = Modifier.padding(horizontal = Space.md),
            trailing = { ConnectionPill(state = connection) },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.md),
        ) {
            /* ---------------- relay ---------------- */

            SectionCard {
                SectionHeader(
                    title = "Relay",
                    trailing = {
                        Icon(
                            Icons.Rounded.Link,
                            contentDescription = null,
                            tint = Palette.AccentBlue,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )

                FieldLabel("Relay URL")
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    singleLine = true,
                    textStyle = MonoTextStyle,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        imeAction = ImeAction.Next,
                    ),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("https:// and http:// are upgraded to wss:// and ws:// automatically")

                Spacer(Modifier.height(Space.md))
                FieldLabel("Pairing token")
                OutlinedTextField(
                    value = pairToken,
                    onValueChange = { pairToken = it },
                    singleLine = true,
                    textStyle = MonoTextStyle,
                    visualTransformation = if (showPairToken) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        RevealToggle(
                            revealed = showPairToken,
                            onToggle = { showPairToken = !showPairToken },
                        )
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("Only needed the first time this phone pairs; the relay keeps the public key afterwards")
            }

            Spacer(Modifier.height(Space.lg))

            /* ---------------- app lock ---------------- */

            SectionCard {
                SectionHeader(
                    title = "App lock",
                    trailing = {
                        Icon(
                            Icons.Rounded.Fingerprint,
                            contentDescription = null,
                            tint = if (biometricLock) Palette.AccentBlue else Palette.TextGhost,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )

                ToggleRow(
                    title = "Require biometrics",
                    subtitle = when {
                        !biometricReady -> "Unavailable on this device — the passcode stays as the gate"
                        biometricLock -> "Fingerprint or face unlocks the app; passcode is the fallback"
                        else -> "Off — the passcode alone opens the app"
                    },
                    checked = biometricLock,
                    enabled = biometricReady,
                    onChange = { biometricLock = it },
                )
                HairLine(inset = Space.lg)
                ToggleRow(
                    title = "Lock when leaving the app",
                    subtitle = "Re-lock as soon as Remote Control goes to the background",
                    checked = lockOnBackground,
                    onChange = { lockOnBackground = it },
                )

                Spacer(Modifier.height(Space.md))
                FieldLabel(
                    if (biometricReady) "Passcode (fallback)" else "Passcode",
                )
                OutlinedTextField(
                    value = passcode,
                    onValueChange = { passcode = it.filter { char -> char.isLetterOrDigit() }.take(24) },
                    singleLine = true,
                    textStyle = MonoTextStyle,
                    visualTransformation = if (showPasscode) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        RevealToggle(
                            revealed = showPasscode,
                            onToggle = { showPasscode = !showPasscode },
                        )
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("Stored on this device only. The relay never sees it.")
            }

            Spacer(Modifier.height(Space.lg))

            /* ---------------- identity ---------------- */

            SectionCard {
                SectionHeader(
                    title = "Device identity",
                    trailing = {
                        Icon(
                            Icons.Rounded.Key,
                            contentDescription = null,
                            tint = Palette.Accent,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                InfoRow("Device id", session.deviceId.orPlaceholder(), monospace = true)
                HairLine(inset = Space.lg)
                InfoRow("Public key", session.publicKeyHex.orPlaceholder(), monospace = true)
                HairLine(inset = Space.lg)
                InfoRow("Paired with", config.relayHost)
                HairLine(inset = Space.lg)
                InfoRow("Relay URL", config.relayUrl, monospace = true)

                Spacer(Modifier.height(Space.sm))
                ActionRow(
                    icon = Icons.Rounded.ContentCopy,
                    title = "Copy device id",
                    subtitle = "Paste it on the laptop when it asks who is in control",
                    accent = Palette.AccentBlue,
                    onClick = { copy(context, "Device id", session.deviceId) },
                )
                HairLine(inset = Space.lg)
                ActionRow(
                    icon = Icons.Rounded.Refresh,
                    title = "Reconnect",
                    subtitle = "Close and reopen the relay connection",
                    accent = Palette.AccentBlue,
                    onClick = { session.reconnect() },
                )
            }

            Spacer(Modifier.height(Space.lg))

            /* ---------------- danger zone ---------------- */

            SectionCard {
                SectionHeader(title = "Pairing")
                ActionRow(
                    icon = Icons.Rounded.DeleteForever,
                    title = "Forget this pairing",
                    subtitle = "Keeps the keypair, so the next connect re-pairs silently",
                    accent = Palette.Warning,
                    onClick = {
                        session.clearPairing()
                        toast(context, "Pairing cleared — the next connect re-pairs")
                    },
                )
                HairLine(inset = Space.lg)
                ActionRow(
                    icon = Icons.Rounded.Key,
                    title = "Generate a new identity key",
                    subtitle = if (confirmRegenerate) {
                        "Tap again to confirm — the relay will reject this device until it re-pairs"
                    } else {
                        "Rotates the keypair; the laptop must pair again"
                    },
                    accent = Palette.Danger,
                    onClick = {
                        if (confirmRegenerate) {
                            session.regenerateIdentity()
                            confirmRegenerate = false
                            toast(context, "New keypair generated — re-pair with the relay")
                        } else {
                            confirmRegenerate = true
                        }
                    },
                    trailingText = if (confirmRegenerate) "Confirm" else null,
                )
            }

            Spacer(Modifier.height(Space.lg))
        }

        /* ---------------- pinned save bar ---------------- */

        AnimatedVisibility(visible = dirty) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Palette.Surface.copy(alpha = 0.98f))
                    .padding(horizontal = Space.md, vertical = Space.md),
            ) {
                PrimaryButton(
                    text = "Save & reconnect",
                    icon = Icons.Rounded.Refresh,
                    onClick = {
                        session.applyConfig(
                            config.copy(
                                serverUrl = trimmedUrl,
                                pairToken = trimmedToken,
                                passcode = passcode,
                                biometricLockEnabled = biometricLock,
                                lockOnBackground = lockOnBackground,
                            ),
                        )
                        toast(context, "Reconnecting to ${AppConfig.toWebSocketUrl(trimmedUrl)}")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Palette.TextMuted,
        modifier = Modifier.padding(bottom = Space.xs),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextGhost,
        modifier = Modifier.padding(top = Space.xs),
    )
}

@Composable
private fun RevealToggle(revealed: Boolean, onToggle: () -> Unit) {
    AppIconAction(
        icon = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
        contentDescription = if (revealed) "Hide value" else "Show value",
        onClick = onToggle,
        container = Palette.SurfaceOverlay,
        borderColor = Palette.BorderSoft,
        size = 34.dp,
        modifier = Modifier.padding(end = Space.xs),
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = onChange,
            )
            .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Palette.TextPrimary else Palette.TextGhost,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextGhost,
            )
        }
        Spacer(Modifier.width(Space.md))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Background,
                checkedTrackColor = Palette.AccentBlue,
                checkedBorderColor = Palette.AccentBlue,
                uncheckedThumbColor = Palette.TextGhost,
                uncheckedTrackColor = Palette.Surface,
                uncheckedBorderColor = Palette.Border,
            ),
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Border,
    unfocusedBorderColor = Palette.Border,
    focusedContainerColor = Palette.BackgroundDeep,
    unfocusedContainerColor = Palette.BackgroundDeep,
    focusedTextColor = Palette.TextPrimary,
    unfocusedTextColor = Palette.TextPrimary,
    cursorColor = Palette.Accent,
    focusedLabelColor = Palette.TextMuted,
    unfocusedLabelColor = Palette.TextGhost,
)

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun copy(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
    toast(context, "$label copied")
}
