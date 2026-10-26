package com.novanity.musicplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.data.nowMillis
import com.novanity.musicplayer.ui.PlayerViewModel
import com.novanity.musicplayer.ui.SleepTimer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** "12:34 left" / "End of song" for an active timer, ticking once a second. */
@Composable
fun rememberSleepLabel(timer: SleepTimer?): String? {
    var now by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(timer) {
        while (timer?.endsAtMillis != null) {
            now = nowMillis()
            delay(1_000)
        }
    }
    return when {
        timer == null -> null
        timer.endOfTrack -> "End of song"
        else -> formatDuration((timer.endsAtMillis!! - now).coerceAtLeast(0)) + " left"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepTimerDialog(player: PlayerViewModel, onDismiss: () -> Unit) {
    val timer by player.sleepTimer.collectAsStateWithLifecycle()
    val label = rememberSleepLabel(timer)
    var custom by remember { mutableFloatStateOf(20f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Bedtime, contentDescription = null) },
        title = { Text("Sleep timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (label != null) {
                    Text("Music stops: $label", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 30, 45, 60, 90).forEach { m ->
                        FilterChip(
                            selected = false,
                            onClick = { player.startSleepTimer(m); onDismiss() },
                            label = { Text(if (m < 60) "$m min" else "${m / 60}${if (m % 60 == 0) "" else ".5"} hr") },
                            shape = CircleShape,
                        )
                    }
                    FilterChip(
                        selected = timer?.endOfTrack == true,
                        onClick = { player.sleepAtEndOfTrack(); onDismiss() },
                        label = { Text("End of song") },
                        shape = CircleShape,
                    )
                }
                Text("Custom: ${custom.roundToInt()} min", style = MaterialTheme.typography.bodyMedium)
                Slider(value = custom, onValueChange = { custom = it }, valueRange = 1f..180f)
                TextButton(onClick = { player.startSleepTimer(custom.roundToInt()); onDismiss() }) { Text("Start ${custom.roundToInt()} min timer") }
                Text(
                    "The music fades out gently over the last 20 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (timer != null) TextButton(onClick = { player.cancelSleepTimer(); onDismiss() }) { Text("Turn off") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedDialog(player: PlayerViewModel, onDismiss: () -> Unit) {
    val speed by player.speed.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Speed, contentDescription = null) },
        title = { Text("Playback speed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${(speed * 100).roundToInt() / 100.0}×", style = MaterialTheme.typography.headlineMedium)
                Slider(value = speed, onValueChange = { player.setSpeed((it * 20).roundToInt() / 20f) }, valueRange = 0.5f..2f, steps = 29)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { v ->
                        FilterChip(
                            selected = speed == v,
                            onClick = { player.setSpeed(v) },
                            label = { Text(if (v == 1f) "Normal" else "${v}×") },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
