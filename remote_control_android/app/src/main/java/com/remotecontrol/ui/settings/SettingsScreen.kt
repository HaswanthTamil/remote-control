package com.remotecontrol.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
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
import com.remotecontrol.data.RemoteSession
import com.remotecontrol.ui.components.ConnectionPill
import com.remotecontrol.ui.components.Eyebrow
import com.remotecontrol.ui.components.GhostButton
import com.remotecontrol.ui.components.InfoRow
import com.remotecontrol.ui.components.PrimaryButton
import com.remotecontrol.ui.components.SectionCard
import com.remotecontrol.ui.components.TopBar
import com.remotecontrol.ui.components.orPlaceholder
import com.remotecontrol.ui.lock.BiometricGate
import com.remotecontrol.ui.lock.BiometricStatus
import com.remotecontrol.ui.theme.MonoTextStyle
import com.remotecontrol.ui.theme.Palette

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

    val biometricStatus = remember { BiometricGate.statusOf(context) }
    val biometricReady = biometricStatus == BiometricStatus.AVAILABLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        TopBar(
            title = "Settings",
            onBack = onBack,
            modifier = Modifier.padding(bottom = 12.dp),
            trailing = { ConnectionPill(state = connection) },
        )

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Link, contentDescription = null, tint = Palette.AccentBlue)
                Spacer(Modifier.width(8.dp))
                Eyebrow("Relay")
            }
            Spacer(Modifier.height(10.dp))

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
            Spacer(Modifier.height(4.dp))
            Text(
                text = "https:// and http:// are upgraded to wss:// and ws:// automatically",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextGhost,
            )

            Spacer(Modifier.height(14.dp))
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
                    Text(
                        text = if (showPairToken) "Hide" else "Show",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.AccentBlue,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable { showPairToken = !showPairToken },
                    )
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Only needed the first time this phone pairs; the relay keeps the public key afterwards.",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextGhost,
            )

            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                text = "Save & reconnect",
                onClick = {
                    session.applyConfig(
                        config.copy(
                            serverUrl = serverUrl.trim(),
                            pairToken = pairToken.trim(),
                            passcode = passcode,
                            biometricLockEnabled = biometricLock,
                            lockOnBackground = lockOnBackground,
                        ),
                    )
                    toast(context, "Reconnecting to ${AppConfig.toWebSocketUrl(serverUrl.trim())}")
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(18.dp))

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = Palette.AccentBlue)
                Spacer(Modifier.width(8.dp))
                Eyebrow("App lock")
            }
            Spacer(Modifier.height(10.dp))

            ToggleRow(
                title = "Require biometrics",
                subtitle = when {
                    !biometricReady -> "Unavailable on this device - the passcode stays as the gate"
                    biometricLock -> "Fingerprint or face unlocks the app; passcode is the fallback"
                    else -> "Off - the passcode alone opens the app"
                },
                checked = biometricLock,
                enabled = biometricReady,
                onChange = { biometricLock = it },
            )
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                title = "Lock when leaving the app",
                subtitle = "Re-lock as soon as Remote Control goes to the background",
                checked = lockOnBackground,
                onChange = { lockOnBackground = it },
            )

            Spacer(Modifier.height(12.dp))
            FieldLabel("Passcode (fallback / passcode-only)")
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
                    Text(
                        text = if (showPasscode) "Hide" else "Show",
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.AccentBlue,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable { showPasscode = !showPasscode },
                    )
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(18.dp))

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = Palette.Accent)
                Spacer(Modifier.width(8.dp))
                Eyebrow("Device identity")
            }
            Spacer(Modifier.height(10.dp))
            InfoRow("Device id", session.deviceId.orPlaceholder(), monospace = true)
            InfoRow("Public key", session.publicKeyHex.orPlaceholder(), monospace = true)
            InfoRow("Paired with", config.relayHost)
            InfoRow("Relay URL", config.relayUrl, monospace = true)

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    text = "Copy device id",
                    icon = Icons.Rounded.ContentCopy,
                    onClick = { copy(context, "Device id", session.deviceId) },
                )
                GhostButton(
                    text = "Reconnect",
                    icon = Icons.Rounded.Refresh,
                    onClick = { session.reconnect() },
                )
            }
            Spacer(Modifier.height(8.dp))
            GhostButton(
                text = "Forget pairing (re-pair on next connect)",
                onClick = { session.clearPairing() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            GhostButton(
                text = "Generate a new identity key",
                contentColor = Palette.Danger,
                onClick = {
                    session.regenerateIdentity()
                    toast(context, "New keypair generated - re-pair with the relay")
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Palette.TextMuted,
        modifier = Modifier.padding(bottom = 6.dp),
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
            .padding(vertical = 6.dp),
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
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Background,
                checkedTrackColor = Palette.AccentBlue,
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