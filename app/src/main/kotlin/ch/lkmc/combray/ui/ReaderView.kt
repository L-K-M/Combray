package ch.lkmc.combray.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
                    Box(Modifier.fillMaxSize()) {
                        ArticleText(state.article, onOpenLink)
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp)
                                .widthIn(min = 48.dp).heightIn(min = 48.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_close),
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleText(article: Article, onOpenLink: (String) -> Unit) {
    // Selectable, so a passage can be copied; links stay tappable inside the selection.
    SelectionContainer {
        LazyColumn(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ArticleHeader(article) }
            items(article.blocks) { block -> Block(block, onOpenLink) }
            if (article.truncated) {
                item { Paragraph(stringResource(R.string.reader_truncated), MaterialTheme.typography.bodySmall, Modifier.padding(bottom = 24.dp)) }
            }
            item { Box(Modifier.padding(bottom = 24.dp)) }
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
            Text(it, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
    }
}

@Composable
private fun Block(block: ArticleBlock, onOpenLink: (String) -> Unit) {
    val text = annotated(block, onOpenLink)
    val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp)
    when (block.kind) {
        BlockKind.HEADING -> Text(
            text,
            style = if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            modifier = Modifier.articleWidth().padding(top = 8.dp).semantics { heading() },
        )
        BlockKind.PARAGRAPH -> Paragraph(text, body)
        BlockKind.LIST_ITEM -> Row(Modifier.articleWidth().padding(start = (16 * (block.level - 1)).dp)) {
            Text("•", style = body, modifier = Modifier.padding(end = 8.dp))
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
