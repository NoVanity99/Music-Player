package com.novanity.musicplayer.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import com.novanity.musicplayer.data.ArtistPart
import com.novanity.musicplayer.ui.LibraryViewModel

/**
 * An artist credit where each artist is its own link: "Drake & Future" opens Drake or Future.
 * Artists who aren't in the library say so instead of opening a page. [onOpen] runs first (e.g. to
 * close the full-screen player).
 */
@Composable
fun ArtistLinks(
    credit: String,
    library: LibraryViewModel,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
    onOpen: () -> Unit = {},
) {
    val lib by library.grouped.collectAsStateWithLifecycle()
    val parts = lib?.artistIndex?.resolve(credit) ?: listOf(ArtistPart(credit, credit, false))
    val text = buildAnnotatedString {
        val links = TextLinkStyles(hoveredStyle = SpanStyle(textDecoration = TextDecoration.Underline))
        var cursor = 0
        for (part in parts) {
            // Keep the credit's own separators (" & ", ", ", " feat. ") between the links.
            val at = credit.indexOf(part.text, cursor, ignoreCase = true).takeIf { it >= 0 }
            if (at != null && at > cursor) append(credit.substring(cursor, at)) else if (at == null && cursor > 0) append(", ")
            withLink(LinkAnnotation.Clickable("artist:${part.name}", links) { onOpen(); library.openArtist(part) }) {
                append(if (at != null) credit.substring(at, at + part.text.length) else part.text)
            }
            if (at != null) cursor = at + part.text.length
        }
        if (cursor in 1 until credit.length) append(credit.substring(cursor))
    }
    Text(text, modifier, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
}

/** "Go to artist" menu entries: one per artist in a collaboration. */
fun artistMenuLabels(parts: List<ArtistPart>): List<Pair<String, ArtistPart>> =
    if (parts.size == 1) listOf("Go to artist" to parts.first()) else parts.map { "Go to ${it.text}" to it }
