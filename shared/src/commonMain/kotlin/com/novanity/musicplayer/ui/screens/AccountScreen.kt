package com.novanity.musicplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.AccountState
import com.novanity.musicplayer.ui.LibraryViewModel
import com.novanity.musicplayer.ui.components.GradientArt
import com.novanity.musicplayer.ui.components.ShuffleGradient

/** Sign in or create an account. Shown until someone is signed in. */
@Composable
fun AccountScreen(vm: LibraryViewModel) {
    var creating by rememberSaveable { mutableStateOf(true) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Checked here too, so most mistakes are caught before asking the server.
    val problem = when {
        username.isBlank() || password.isEmpty() -> ""
        creating && !Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,23}$").matches(username.trim()) ->
            "Usernames are 3 to 24 letters, numbers, dots, dashes or underscores."
        creating && password.length < 8 -> "Passwords need at least 8 characters."
        creating && confirm.isNotEmpty() && confirm != password -> "The passwords don't match."
        creating && confirm.isEmpty() -> ""
        else -> null
    }
    val canSubmit = !busy && problem == null
    val submit = {
        if (canSubmit) {
            busy = true
            error = null
            vm.authenticate(creating, username, password) { message ->
                busy = false
                error = message
            }
        }
    }

    val accentWash = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(accentWash, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)))
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 440.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GradientArt(ShuffleGradient, Icons.Rounded.MusicNote, Modifier.size(96.dp), RoundedCornerShape(26.dp), iconFraction = 0.55f)
            Spacer(Modifier.height(24.dp))
            Text(if (creating) "Create your account" else "Welcome back", style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your liked songs, playlists and listening history are saved to your account.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(24); error = null },
                label = { Text("Username") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            val passwordLook = if (showPassword) VisualTransformation.None else PasswordVisualTransformation()
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(200); error = null },
                label = { Text("Password") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = passwordLook,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = if (creating) ImeAction.Next else ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (showPassword) "Hide password" else "Show password")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (creating) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.take(200); error = null },
                    label = { Text("Confirm password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = passwordLook,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val message = error ?: problem?.takeIf { it.isNotEmpty() }
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = submit,
                enabled = canSubmit,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(if (creating) "Create account" else "Sign in", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { creating = !creating; error = null; confirm = "" }, enabled = !busy) {
                Text(if (creating) "I already have an account" else "Create a new account")
            }
        }
    }
}

/** Settings: who's signed in, with sign out and delete account. */
@Composable
internal fun AccountSettingsRow(vm: LibraryViewModel, toast: (String) -> Unit) {
    val state by vm.accountState.collectAsStateWithLifecycle()
    val user = (state as? AccountState.SignedIn)?.user ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp)) {
        Text("Signed in as", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(user.username, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Liked songs, playlists and listening history belong to this account and follow it to any device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.signOut() }) { Text("Sign out") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { confirmDelete = true }) { Text("Delete account", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("Delete your account?") },
            text = { Text("This permanently deletes “${user.username}” with its liked songs, playlists and listening history. It can't be undone.") },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        vm.deleteAccount { message ->
                            deleting = false
                            confirmDelete = false
                            if (message != null) toast(message)
                        }
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
