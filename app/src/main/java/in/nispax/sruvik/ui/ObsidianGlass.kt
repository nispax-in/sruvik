package `in`.nispax.sruvik.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.nispax.sruvik.ui.theme.SruvikColors

private val GlassInk = Color(0xFF11172D)
private val GlassInkMuted = Color(0xFF3D4965)

/**
 * A light, frosted transmission surface inspired by the SruGen home CTA.
 * It keeps the scene visible beneath a cool milky tint, with a crisp reflective rim.
 * Text and child controls remain sharp; only the background artwork should be blurred.
 */
@Composable
fun ObsidianFrostedSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(25.dp),
    fillAlpha: Float = .54f,
    borderAlpha: Float = .84f,
    content: @Composable BoxScope.() -> Unit
) {
    val alpha = fillAlpha.coerceIn(.12f, .82f)
    Box(
        modifier = modifier
            .shadow(17.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFFF3F5FF).copy(alpha = alpha),
                        Color(0xFFDCE5FF).copy(alpha = alpha * .78f),
                        Color(0xFFC9C8F3).copy(alpha = alpha * .70f)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.35.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = borderAlpha),
                            Color(0xFFE9EEFF).copy(alpha = borderAlpha * .70f),
                            Color(0xFFB8C7EE).copy(alpha = borderAlpha * .48f),
                            Color.White.copy(alpha = borderAlpha * .78f)
                        )
                    )
                ),
                shape
            )
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = .28f), Color.Transparent),
                        startY = 0f,
                        endY = 34.dp.toPx()
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = .19f), Color.Transparent),
                        center = Offset(size.width * .88f, size.height * .05f),
                        radius = size.maxDimension * .90f
                    )
                )
            },
        content = content
    )
}

/** Light glass card for text, summaries and focused task content. */
@Composable
fun ObsidianFrostedPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(25.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    fillAlpha: Float = .52f,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    ObsidianFrostedSurface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        fillAlpha = fillAlpha
    ) {
        Column(
            Modifier.fillMaxWidth().padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

/** Large frosted action with the bright rim, dark ink and generous hit target from the reference. */
@Composable
fun ObsidianGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(30.dp),
    fillAlpha: Float = .58f,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
    content: @Composable RowScope.() -> Unit
) {
    val alpha = if (enabled) fillAlpha else .18f
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .shadow(if (enabled) 12.dp else 0.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = alpha),
                        Color(0xFFE4E9FF).copy(alpha = alpha * .82f),
                        Color(0xFFC6C6EE).copy(alpha = alpha * .72f)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.6.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = if (enabled) .91f else .28f),
                            Color(0xFFE7EDFF).copy(alpha = if (enabled) .78f else .22f),
                            Color(0xFFBCC7E8).copy(alpha = if (enabled) .66f else .18f)
                        )
                    )
                ),
                shape
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = Color.Transparent,
            contentColor = if (enabled) GlassInk else GlassInkMuted.copy(alpha = .72f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            // Surface's content container is a Box. Keep the caller's RowScope
            // children in an actual Row so labels, icons and weighted spacers
            // are measured and placed horizontally instead of overlapping.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                content = content
            )
        }
    }
}

/** Compact glass chip for modes, filters and selected states. */
@Composable
fun ObsidianGlassPill(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = CircleShape,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .heightIn(min = 42.dp)
            .shadow(if (selected) 7.dp else 0.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    if (selected) listOf(
                        Color.White.copy(alpha = .76f),
                        Color(0xFFE3E9FF).copy(alpha = .62f),
                        Color(0xFFC6C9F1).copy(alpha = .50f)
                    ) else listOf(
                        Color.White.copy(alpha = .34f),
                        Color(0xFFDCE5FF).copy(alpha = .22f),
                        Color(0xFFC9C8F3).copy(alpha = .18f)
                    )
                )
            )
            .border(
                1.dp,
                Color.White.copy(alpha = if (selected) .82f else .48f),
                shape
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = Color.Transparent,
            contentColor = if (enabled) GlassInk else GlassInkMuted.copy(alpha = .52f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
}

/** Circular glass button for icon-only actions; callers must provide an accessible label. */
@Composable
fun ObsidianGlassIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .shadow(if (enabled) 8.dp else 0.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = if (enabled) .58f else .18f),
                        Color(0xFFE3E9FF).copy(alpha = if (enabled) .42f else .14f),
                        Color(0xFFC7C8F0).copy(alpha = if (enabled) .32f else .10f)
                    )
                )
            )
            .border(1.1.dp, Color.White.copy(alpha = if (enabled) .86f else .28f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color.Transparent,
            contentColor = if (enabled) GlassInk else GlassInkMuted.copy(alpha = .45f),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            content()
        }
    }
}

/**
 * Dark companion material for high-density content cards.
 * Lower-opacity tint and a brighter rim preserve the existing light text hierarchy.
 */
@Composable
fun ObsidianGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    accent: Color = SruvikColors.Primary,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(17.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0x842A3852),
                        Color(0x74182438),
                        Color(0x7B0D1523)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.3.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = .58f),
                            accent.copy(alpha = .34f),
                            Color.White.copy(alpha = .13f)
                        )
                    )
                ),
                shape
            )
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = .17f), Color.Transparent),
                        center = Offset(size.width * .94f, size.height * .02f),
                        radius = size.maxDimension * .92f
                    )
                )
                drawRect(
                    Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = .14f), Color.Transparent),
                        startY = 0f,
                        endY = 42.dp.toPx()
                    )
                )
                drawRect(
                    Brush.horizontalGradient(
                        colors = listOf(Color.White.copy(alpha = .10f), Color.Transparent),
                        startX = 0f,
                        endX = 1.2.dp.toPx()
                    )
                )
            }
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content
    )
}

/** Glass box for image previews, status strips and other non-column layouts. */
@Composable
fun ObsidianGlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    accent: Color = SruvikColors.Primary,
    fill: Brush? = null,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(13.dp, shape, clip = false)
            .clip(shape)
            .background(
                fill ?: Brush.linearGradient(
                    listOf(Color(0x88202B3C), Color(0x76141B28), Color(0x820D121B))
                )
            )
            .border(
                BorderStroke(
                    1.1.dp,
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = .49f), accent.copy(alpha = .27f), Color.White.copy(alpha = .11f))
                    )
                ),
                shape
            )
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = .11f), Color.Transparent),
                        center = Offset(size.width * .95f, 0f),
                        radius = size.maxDimension
                    )
                )
            },
        content = content
    )
}

/** Primary action used across the app. Keeps the familiar button API and accessible semantics. */
@Composable
fun ObsidianPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    ObsidianGlassButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        fillAlpha = .60f,
        content = content
    )
}

/** Secondary action: same frosted-glass language with less surface emphasis. */
@Composable
fun ObsidianSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    ObsidianGlassButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        fillAlpha = .34f,
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 17.dp, vertical = 12.dp),
        content = content
    )
}
