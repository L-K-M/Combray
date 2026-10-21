package ch.lkmc.combray.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.lkmc.combray.R
import ch.lkmc.combray.core.browser.Article
import ch.lkmc.combray.core.browser.ArticleBlock
import ch.lkmc.combray.core.browser.BlockKind
import ch.lkmc.combray.core.browser.ReaderState

/** Readable line length for body text. */
private val MAX_TEXT_WIDTH = 680.dp
private val QUOTE_BAR_WIDTH = 3.dp
/** A numbered item's number and the gap after it, like a browser's 40px list indent at 16px. */
private const val ORDINAL_GUTTER_EMS = 2.5f
private val ORDINAL_GAP = 8.dp
/** OpenType tabular figures: every digit has one width, so the numbers of a list line up. */
private const val TABULAR_FIGURES = "tnum"

/**
 * Reader view's type scale. The article's own words (title, headings, body, quotes and list
 * items) are set in the system serif, Noto Serif, so nothing is bundled; site, byline, captions
 * and code keep the app's type. Headings never fall below the body size.
 */
internal object ReaderType {
    val title = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp)
    val body = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 18.sp,
        lineHeight = 29.sp,
        hyphens = Hyphens.Auto,
        lineBreak = LineBreak.Paragraph,
    )
    private val majorHeading = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp)
    private val sectionHeading = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
    private val minorHeading = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 26.sp)

    /** The style of a heading of [level] 1..6. */
    fun heading(level: Int): TextStyle = when {
        level <= 2 -> majorHeading
        level == 3 -> sectionHeading
        else -> minorHeading
    }
}

/**
 * Reader view over the page area: the article as plain text with links, in the app's own type.
 * Page content is never rendered as markup here; links go through [onOpenLink].
 */
@Composable
fun ReaderView(state: ReaderState, onOpenLink: (String) -> Unit, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            is ReaderState.Loading -> ReaderMessage(stringResource(R.string.reader_loading)) { CircularProgressIndicator() }
            is ReaderState.Unavailable -> ReaderMessage(stringResource(R.string.reader_unavailable)) {
                Button(onClick = onClose) { Text(stringResource(R.string.action_show_page)) }
            }
            is ReaderState.Ready -> {
                val direction = if (state.article.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    ArticleText(state.article, onOpenLink, onClose)
                }
            }
        }
    }
}

@Composable
private fun ArticleText(article: Article, onOpenLink: (String) -> Unit, onClose: () -> Unit) {
    // Selectable, so a passage can be copied; links stay tappable inside the selection.
    SelectionContainer {
        LazyColumn(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ShowPageBar(onClose) }
            item { ArticleHeader(article) }
            items(article.blocks) { block -> Block(block, onOpenLink) }
            if (article.truncated) {
                item { Paragraph(stringResource(R.string.reader_truncated), MaterialTheme.typography.bodySmall, Modifier.padding(bottom = 24.dp)) }
            }
            item { Box(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

/** A visible way back to the page at the top of the article, besides Back and More › Leave reader view. */
@Composable
private fun ShowPageBar(onClose: () -> Unit) {
    // Not part of the article, so a selection to copy leaves it out.
    DisableSelection {
        Box(Modifier.articleWidth().padding(top = 8.dp)) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.action_show_page))
            }
        }
    }
}

@Composable
private fun ArticleHeader(article: Article) {
    Column(Modifier.articleWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOfNotNull(article.site, article.byline).distinct().takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        article.title?.let {
            Text(it, style = ReaderType.title, modifier = Modifier.semantics { heading() })
        }
    }
}

@Composable
private fun Block(block: ArticleBlock, onOpenLink: (String) -> Unit) {
    val text = annotated(block, onOpenLink)
    val body = ReaderType.body
    when (block.kind) {
        BlockKind.HEADING -> Text(
            text,
            style = ReaderType.heading(block.level),
            modifier = Modifier.articleWidth().padding(top = 8.dp).semantics { heading() },
        )
        BlockKind.PARAGRAPH -> Paragraph(text, body)
        BlockKind.LIST_ITEM -> Row(Modifier.articleWidth().padding(start = (16 * (block.level - 1)).dp)) {
            val ordinal = block.ordinal
            if (ordinal != null) Ordinal(ordinal, body) else Text("•", style = body, modifier = Modifier.padding(end = 8.dp))
            Text(text, style = body)
        }
        BlockKind.QUOTE -> {
            // A bar on the quote's start side, the left one in left-to-right text.
            val bar = MaterialTheme.colorScheme.outlineVariant
            val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
            Text(
                text,
                style = body.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.articleWidth()
                    .drawBehind {
                        val width = QUOTE_BAR_WIDTH.toPx()
                        drawRect(bar, topLeft = Offset(if (rightToLeft) size.width - width else 0f, 0f), size = Size(width, size.height))
                    }
                    .padding(start = 16.dp),
            )
        }
        BlockKind.PREFORMATTED -> Text(
            text,
            style = body.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp),
            softWrap = false,
            modifier = Modifier.articleWidth().horizontalScroll(rememberScrollState()),
        )
        BlockKind.CAPTION -> Paragraph(text, MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic))
    }
}

/**
 * A numbered item's "3.", right-aligned in a gutter with room for three digits, so the items of a
 * list line up; a longer number widens only its own gutter. The gutter follows the font size.
 */
@Composable
private fun Ordinal(ordinal: Int, body: TextStyle) {
    // The font size's own scaled width, multiplied: Android scales large sp sizes less than body text.
    val gutter = with(LocalDensity.current) { body.fontSize.toDp() } * ORDINAL_GUTTER_EMS
    Text(
        "$ordinal.",
        style = body.copy(textAlign = TextAlign.End, fontFeatureSettings = TABULAR_FIGURES),
        modifier = Modifier.widthIn(min = gutter).padding(end = ORDINAL_GAP),
    )
}

@Composable
private fun Paragraph(text: String, style: TextStyle, modifier: Modifier = Modifier) = Paragraph(AnnotatedString(text), style, modifier)

@Composable
private fun Paragraph(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    Text(text, style = style, modifier = Modifier.articleWidth().then(modifier))
}

/** The block's runs, links underlined and handed to [onOpenLink] when tapped. */
@Composable
private fun annotated(block: ArticleBlock, onOpenLink: (String) -> Unit): AnnotatedString {
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        for (span in block.spans) {
            val href = span.href
            if (href == null) {
                append(span.text)
                continue
            }
            withLink(LinkAnnotation.Clickable(href, linkStyle) { onOpenLink(href) }) { append(span.text) }
        }
    }
}

@Composable
private fun ReaderMessage(message: String, action: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, textAlign = TextAlign.Center)
        action()
    }
}

private fun Modifier.articleWidth() = widthIn(max = MAX_TEXT_WIDTH).fillMaxWidth().padding(horizontal = 20.dp)

/** Material's "article" icon (Apache License 2.0, like the icon library), absent from the core set. */
val ReaderIcon: ImageVector = ImageVector.Builder("Article", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes(
            "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z" +
                "m-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z",
        ),
        fill = SolidColor(Color.Black),
    )
    .build()
