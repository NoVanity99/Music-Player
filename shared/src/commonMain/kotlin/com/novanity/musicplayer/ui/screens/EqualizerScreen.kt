package com.novanity.musicplayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novanity.musicplayer.platform.LocalPlatform
import com.novanity.musicplayer.ui.SongActions
import kotlin.math.roundToInt

/** Android system equalizer: on/off, presets, and per-band levels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerScreen(actions: SongActions, contentPadding: PaddingValues) {
    val vm = actions.library
    val info = LocalPlatform.current.equalizer
    val enabled by vm.eqEnabled.collectAsStateWithLifecycle()
    val preset by vm.eqPreset.collectAsStateWithLifecycle()
    val levels by vm.eqBands.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
        item {
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Equalizer", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = vm::setEqEnabled)
            }
        }
        if (info == null) {
            item { Text("This device doesn't offer an equalizer.", modifier = Modifier.padding(24.dp)) }
            return@LazyColumn
        }
        item {
            Text("PRESETS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = preset < 0, onClick = { vm.setEqBands(levels.ifEmpty { info.bands.map { 0 } }) }, label = { Text("Custom") },
                    shape = CircleShape, enabled = enabled,
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                info.presets.forEachIndexed { i, name ->
                    FilterChip(selected = preset == i, onClick = { vm.setEqPreset(i) }, label = { Text(name) }, shape = CircleShape, enabled = enabled,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary))
                }
            }
        }
        item {
            Text("BANDS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp))
        }
        info.bands.forEachIndexed { i, band ->
            item {
                val current = levels.getOrNull(i) ?: 0
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (band.centerHz >= 1000) "${band.centerHz / 1000} kHz" else "${band.centerHz} Hz",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(64.dp),
                    )
                    Slider(
                        value = current.toFloat(),
                        onValueChange = { v ->
                            val next = info.bands.indices.map { levels.getOrNull(it) ?: 0 }.toMutableList()
                            next[i] = v.roundToInt()
                            vm.setEqBands(next)
                        },
                        valueRange = band.minLevel.toFloat()..band.maxLevel.toFloat(),
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("${if (current > 0) "+" else ""}${current / 100} dB", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(52.dp))
                }
            }
        }
    }
}
