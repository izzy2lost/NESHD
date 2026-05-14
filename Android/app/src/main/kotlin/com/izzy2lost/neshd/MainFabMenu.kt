package com.izzy2lost.neshd

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainFabMenu(
    visible: Boolean,
    onOpenRom: () -> Unit,
    onScanFolder: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    NesHdFabMenuTheme {
        val focusRequester = remember { FocusRequester() }
        var expanded by rememberSaveable { mutableStateOf(false) }
        val items = listOf(
            FabMenuAction(R.drawable.ic_folder_open, "Scan ROM Folder", onScanFolder),
            FabMenuAction(R.drawable.ic_add, "Open ROM", onOpenRom),
            FabMenuAction(R.drawable.search_24px, "Search", onSearch)
        )

        BackHandler(expanded) { expanded = false }

        FloatingActionButtonMenu(
            modifier = modifier,
            expanded = expanded,
            button = {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                        if (expanded) TooltipAnchorPosition.Start else TooltipAnchorPosition.Above
                    ),
                    tooltip = { PlainTooltip { Text("Open ROM menu") } },
                    state = rememberTooltipState()
                ) {
                    ToggleFloatingActionButton(
                        modifier = Modifier
                            .semantics {
                                traversalIndex = -1f
                                stateDescription = if (expanded) "Expanded" else "Collapsed"
                                contentDescription = "Open ROM menu"
                            }
                            .animateFloatingActionButton(
                                visible = visible || expanded,
                                alignment = Alignment.BottomEnd
                            )
                            .focusRequester(focusRequester),
                        checked = expanded,
                        onCheckedChange = { expanded = !expanded }
                    ) {
                        val icon = if (checkedProgress > 0.5f) {
                            R.drawable.ic_close
                        } else {
                            R.drawable.ic_add
                        }
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            modifier = Modifier.animateIcon({ checkedProgress })
                        )
                    }
                }
            }
        ) {
            items.forEachIndexed { index, item ->
                FloatingActionButtonMenuItem(
                    modifier = Modifier
                        .semantics {
                            isTraversalGroup = true
                            if (index == items.lastIndex) {
                                customActions = listOf(
                                    CustomAccessibilityAction("Close menu") {
                                        expanded = false
                                        true
                                    }
                                )
                            }
                        }
                        .then(
                            if (index == 0) {
                                Modifier.onKeyEvent {
                                    if (
                                        it.type == KeyEventType.KeyDown &&
                                        (it.key == Key.DirectionUp || (it.isShiftPressed && it.key == Key.Tab))
                                    ) {
                                        focusRequester.requestFocus()
                                        true
                                    } else {
                                        false
                                    }
                                }
                            } else {
                                Modifier
                            }
                        ),
                    onClick = {
                        expanded = false
                        item.onClick()
                    },
                    icon = {
                        Icon(
                            painter = painterResource(item.icon),
                            contentDescription = null
                        )
                    },
                    text = { Text(item.label) }
                )
            }
        }
    }
}

private data class FabMenuAction(
    @DrawableRes val icon: Int,
    val label: String,
    val onClick: () -> Unit
)

@Composable
private fun NesHdFabMenuTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFFFFB4AB),
            onPrimary = Color(0xFF690004),
            primaryContainer = Color(0xFF930009),
            onPrimaryContainer = Color(0xFFFFDAD6),
            secondary = Color(0xFFE7BDB7),
            onSecondary = Color(0xFF442926),
            secondaryContainer = Color(0xFF5D3F3B),
            onSecondaryContainer = Color(0xFFFFDAD6),
            tertiary = Color(0xFFE5C18D),
            onTertiary = Color(0xFF3F2D04),
            tertiaryContainer = Color(0xFF5B4319),
            onTertiaryContainer = Color(0xFFFFDDB3),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410),
            errorContainer = Color(0xFF8C1D18),
            onErrorContainer = Color(0xFFF9DEDC),
            background = Color(0xFF0D0C0C),
            onBackground = Color(0xFFE5E5E5),
            surface = Color(0xFF0D0C0C),
            onSurface = Color(0xFFE5E5E5),
            surfaceVariant = Color(0xFF534341),
            onSurfaceVariant = Color(0xFFD8C2BF),
            outline = Color(0xFFA5A2A2),
            outlineVariant = Color(0xFF534341)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFFDD2020),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFDAD6),
            onPrimaryContainer = Color(0xFF410002),
            secondary = Color(0xFF775651),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFFFDAD6),
            onSecondaryContainer = Color(0xFF2C1512),
            tertiary = Color(0xFF755A2F),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFDDB3),
            onTertiaryContainer = Color(0xFF271900),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
            background = Color(0xFFFFFFFF),
            onBackground = Color(0xFF0D0C0C),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF0D0C0C),
            surfaceVariant = Color(0xFFE5E5E5),
            onSurfaceVariant = Color(0xFF534341),
            outline = Color(0xFFA5A2A2),
            outlineVariant = Color(0xFFD8C2BF)
        )
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
