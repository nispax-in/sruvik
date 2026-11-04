package `in`.nispax.sruvik.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import `in`.nispax.sruvik.ui.theme.SruvikColors

/**
 * Shared Obsidian Liquid Glass surfaces.
 *
 * The material is intentionally built from translucent tint, a restrained
 * reflective rim and a soft shadow. It does not blur the content underneath,
 * so text and controls remain crisp on every supported Android version.
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
            .shadow(15.dp, shape, clip = false)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0x781D2636),
                        Color(0x68121925),
                        Color(0x8010141E)
                    )
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = .42f),
                            accent.copy(alpha = .22f),
                            Color.White.copy(alpha = .08f)
                        )
                    )
                ),
                shape
            )
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = .10f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width * .92f, 0f),
                        radius = size.maxDimension * .85f
                    )
                )
                drawRect(
                    Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = .055f), Color.Transparent),
                        startY = 0f,
                        endY = 30.dp.toPx()
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
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .shadow(12.dp, shape, clip = false)
            .clip(shape)
            .background(
                fill ?: Brush.linearGradient(
                    listOf(Color(0x77202B3C), Color(0x66141B28), Color(0x800D121B))
                )
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = .35f), accent.copy(alpha = .22f), Color.White.copy(alpha = .07f))
                    )
                ),
                shape
            )
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = .09f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width * .95f, 0f),
                        radius = size.maxDimension
                    )
                )
            },
        content = content
    )
}

/** Primary action used across the app; retains Material semantics and states. */
@Composable
fun ObsidianPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = .66f), SruvikColors.Primary.copy(alpha = .55f))
            )
        ),
        colors = ButtonDefaults.buttonColors(
            containerColor = SruvikColors.Primary,
            contentColor = SruvikColors.OnPrimary,
            disabledContainerColor = Color(0xFF303947),
            disabledContentColor = SruvikColors.TextMuted
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 3.dp,
            pressedElevation = 1.dp,
            focusedElevation = 3.dp,
            hoveredElevation = 4.dp,
            disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        content = content
    )
}

/** Secondary outlined glass action with a visible, accessible hit target. */
@Composable
fun ObsidianSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = .34f), SruvikColors.SecondaryAccent.copy(alpha = .28f), Color.White.copy(alpha = .12f))
            )
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0x33212B3C),
            contentColor = SruvikColors.TextPrimary,
            disabledContainerColor = Color(0x221B2230),
            disabledContentColor = SruvikColors.TextMuted
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
        content = content
    )
}
