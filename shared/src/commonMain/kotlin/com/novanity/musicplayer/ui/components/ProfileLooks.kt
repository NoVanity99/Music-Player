package com.novanity.musicplayer.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novanity.musicplayer.data.ProfileLook

/** One choice in a profile look: its ID (what's saved and sent), its name, and its colours. */
class LookOption(val id: String, val label: String, val colors: List<Color> = emptyList(), val animated: Boolean = false)

private val Rainbow = listOf(Color(0xFFEF4444), Color(0xFFF59E0B), Color(0xFF22C55E), Color(0xFF06B6D4), Color(0xFF6366F1), Color(0xFFD946EF), Color(0xFFEF4444))
private val Gold = listOf(Color(0xFFB8860B), Color(0xFFF5C33B), Color(0xFFFFF3C4), Color(0xFFF5C33B), Color(0xFFB8860B))

/** The profile looks people can choose from. The IDs are what the server stores. */
object ProfileLooks {
    val banners = listOf(
        LookOption("", "Classic", listOf(Color(0xFF7C3AED), Color(0xFF1E1B4B))),
        LookOption("aurora", "Aurora", listOf(Color(0xFF0F766E), Color(0xFF22D3EE), Color(0xFFA855F7), Color(0xFF312E81))),
        LookOption("sunset", "Sunset", listOf(Color(0xFFF97316), Color(0xFFDB2777), Color(0xFF4C1D95))),
        LookOption("ocean", "Deep ocean", listOf(Color(0xFF38BDF8), Color(0xFF1D4ED8), Color(0xFF0B1026))),
        LookOption("galaxy", "Galaxy", listOf(Color(0xFF7C3AED), Color(0xFF0B0720), Color(0xFFDB2777))),
        LookOption("ember", "Ember", listOf(Color(0xFF1C0A0A), Color(0xFFB91C1C), Color(0xFFF97316), Color(0xFFFDE047))),
        LookOption("toxic", "Toxic", listOf(Color(0xFFBEF264), Color(0xFF16A34A), Color(0xFF052E16))),
        LookOption("candy", "Candy", listOf(Color(0xFFF9A8D4), Color(0xFFC084FC), Color(0xFF67E8F9))),
        LookOption("gold", "Gold", Gold),
        LookOption("mono", "Monochrome", listOf(Color(0xFF52525B), Color(0xFF09090B))),
        LookOption("rainbow", "Rainbow", Rainbow),
        LookOption("rose", "Rose", listOf(Color(0xFFFF375F), Color(0xFF7F1D1D))),
    )
    val frames = listOf(
        LookOption("", "None"),
        LookOption("accent", "Accent"),
        LookOption("gold", "Gold", Gold),
        LookOption("rainbow", "Rainbow spin", Rainbow, animated = true),
        LookOption("fire", "Fire", listOf(Color(0xFFFDE047), Color(0xFFF97316), Color(0xFFDC2626), Color(0xFFFDE047))),
        LookOption("ice", "Ice", listOf(Color(0xFFE0F2FE), Color(0xFF38BDF8), Color(0xFF1E40AF), Color(0xFFE0F2FE))),
        LookOption("neon", "Neon", listOf(Color(0xFFFF2BD6), Color(0xFF00E5FF), Color(0xFFFF2BD6)), animated = true),
        LookOption("emerald", "Emerald", listOf(Color(0xFF6EE7B7), Color(0xFF059669), Color(0xFF6EE7B7))),
    )
    val names = listOf(
        LookOption("", "Plain"),
        LookOption("accent", "Accent"),
        LookOption("gold", "Gold shine", Gold, animated = true),
        LookOption("rainbow", "Rainbow", Rainbow, animated = true),
        LookOption("sunset", "Sunset", listOf(Color(0xFFFDE047), Color(0xFFF97316), Color(0xFFDB2777))),
        LookOption("ice", "Ice", listOf(Color(0xFFE0F2FE), Color(0xFF38BDF8), Color(0xFF818CF8))),
        LookOption("glow", "Glow"),
    )
    val flairs = listOf("", "🎧", "🔥", "👑", "⚡", "💎", "🌙", "🚀", "🎵", "🎸", "🎹", "🥁", "🌈", "☠️", "🍀", "❤️", "🧊", "🏆")

    fun banner(look: ProfileLook?) = banners.firstOrNull { it.id == look?.banner } ?: banners.first()
    fun frame(look: ProfileLook?) = frames.firstOrNull { it.id == look?.frame } ?: frames.first()
    fun name(look: ProfileLook?) = names.firstOrNull { it.id == look?.name } ?: names.first()
    fun flair(look: ProfileLook?): String = look?.flair?.takeIf { it in flairs }.orEmpty()
}

/** The gradient behind a profile's header. */
fun bannerBrush(look: ProfileLook?): Brush = Brush.linearGradient(ProfileLooks.banner(look).colors)

/** 0→1, over and over: drives the looks that move. */
@Composable
private fun rememberSweep(durationMs: Int): Float {
    val t by rememberInfiniteTransition(label = "look").animateFloat(0f, 1f, infiniteRepeatable(tween(durationMs, easing = LinearEasing)), label = "sweep")
    return t
}

/** A profile picture inside the frame its owner chose. */
@Composable
fun FramedAvatar(name: String, url: String?, look: ProfileLook?, size: Dp, modifier: Modifier = Modifier) {
    val frame = ProfileLooks.frame(look)
    if (frame.id.isEmpty()) return Avatar(name, url, size, modifier)
    val ring = (size.value * 0.07f).coerceIn(2f, 5f).dp
    val accent = MaterialTheme.colorScheme.primary
    val colors = frame.colors.ifEmpty { listOf(accent, accent) }
    val spin = if (frame.animated) rememberSweep(4000) * 360f else 0f
    Box(
        modifier
            .clip(CircleShape)
            .drawBehind {
                // A ring of colour around the picture; the animated ones turn slowly.
                rotate(spin) { drawCircle(Brush.sweepGradient(colors), radius = this.size.minDimension) }
            }
            .padding(ring),
        contentAlignment = Alignment.Center,
    ) {
        Avatar(name, url, size - ring * 2)
    }
}

/** A name in its owner's chosen style, with their flair emoji after it. */
@Composable
fun StyledName(name: String, look: ProfileLook?, style: TextStyle, modifier: Modifier = Modifier) {
    val option = ProfileLooks.name(look)
    val accent = MaterialTheme.colorScheme.primary
    val moving = if (option.animated) rememberSweep(3200) else 0f
    val styled = when {
        option.id == "accent" -> style.copy(color = accent)
        option.id == "glow" -> style.copy(color = Color.White, shadow = Shadow(accent, Offset.Zero, blurRadius = 22f))
        option.colors.isNotEmpty() -> {
            // The gradient is laid out wider than the text and slid along it for the moving styles.
            val shift = moving * 600f
            style.copy(brush = Brush.linearGradient(option.colors, start = Offset(shift - 300f, 0f), end = Offset(shift + 300f, 60f), tileMode = androidx.compose.ui.graphics.TileMode.Mirror))
        }
        else -> style
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = styled, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        val flair = ProfileLooks.flair(look)
        if (flair.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(flair, style = style.copy(brush = null, shadow = null))
        }
    }
}

/** A small rounded swatch of a banner, for pickers. */
@Composable
fun BannerSwatch(option: LookOption, modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.linearGradient(option.colors)))
}
