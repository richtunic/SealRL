package com.junkfood.seal.ui.component.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.junkfood.seal.R
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

private val accent = Color(0xFFE11D48)
private val capsuleShape = RoundedCornerShape(percent = 50)

/** Kyant Backdrop glass: refractive capsule, frosted moving selection, sharp controls. */
@Composable
fun LiquidBottomNav(selectedIndex: Int, backdrop: LayerBackdrop, onSelect: (Int) -> Unit) {
    val destinations = listOf(
        Triple("Inicio", Icons.Default.Home, 0),
        Triple(stringResource(R.string.tab_downloads).replaceFirstChar { it.uppercase() }, Icons.Default.Download, 1),
        Triple(stringResource(R.string.settings), Icons.Default.Settings, 2),
    )
    val interactions = remember { List(3) { MutableInteractionSource() } }
    val selected = selectedIndex.coerceIn(0, destinations.lastIndex)
    val pressed by interactions[selected].collectIsPressedAsState()
    val pillScale by animateFloatAsState(
        targetValue = if (pressed) 76f / 56f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 300f),
        label = "glass press",
    )

    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
            val itemWidth = maxWidth / destinations.size
            val pillOffset by animateDpAsState(
                targetValue = itemWidth * selected + 4.dp,
                animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
                label = "selected destination",
            )

            Box(
                Modifier.matchParentSize().drawBackdrop(
                    backdrop = backdrop,
                    shape = { capsuleShape },
                    effects = {
                        colorControls(brightness = 0.05f, saturation = 1.5f)
                        blur(12.dp.toPx())
                        // The top and bottom refractions must not meet at the medial axis.
                        lens(size.minDimension / 4f, size.minDimension / 2f)
                    },
                    highlight = { Highlight.Default },
                    shadow = { Shadow(radius = 6.dp, alpha = 0.4f) },
                    onDrawSurface = { drawRect(Color(0xFF17171B).copy(alpha = 0.35f)) },
                ),
            )

            Box(
                Modifier.offset(x = pillOffset, y = 4.dp)
                    .width(itemWidth - 8.dp)
                    .height(56.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { capsuleShape },
                        effects = {
                            colorControls(brightness = 0.05f, saturation = 1.5f)
                            blur(28.dp.toPx())
                            lens(10.dp.toPx(), 14.dp.toPx(), chromaticAberration = pressed)
                        },
                        layerBlock = {
                            scaleX = pillScale
                            scaleY = pillScale
                        },
                        highlight = { Highlight.Default.copy(alpha = 0.6f) },
                        shadow = { Shadow(radius = 4.dp, alpha = 0.4f) },
                        onDrawSurface = { drawRect(Color(0xFF352129).copy(alpha = 0.3f)) },
                    ),
            )

            Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                destinations.forEach { (label, icon, index) ->
                    Destination(label, icon, selected == index, Modifier.width(itemWidth), interactions[index]) {
                        onSelect(index)
                    }
                }
            }
        }
    }
}

@Composable
private fun Destination(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
    val tint = if (selected) accent else Color(0xFFE2E2E5)
    Column(
        modifier = modifier.height(64.dp).clickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Tab,
            onClick = onClick,
        ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}
