package com.plan.ui.foods

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plan.ui.theme.Cyan
import com.plan.ui.theme.DangerRed
import com.plan.ui.theme.OkGreen
import com.plan.ui.theme.Spacing
import com.plan.ui.theme.SurfaceSticky
import com.plan.ui.theme.Touch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodsScreen(
    viewModel: FoodsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.snackMessage) {
        state.snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnack()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                val msg = data.visuals.message
                val ok = msg.contains("saved", true) || msg.contains("success", true)
                val bg = if (ok) OkGreen else DangerRed
                val fg = if (ok) Color(0xFF002112) else Color.White
                Snackbar(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    containerColor = bg,
                    contentColor = fg,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = fg,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(msg, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Cyan)
            }
        } else {
            // Pull-to-refresh wraps the whole tab (sticky Save bar included), like the Day tab.
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.pullRefresh() },
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
            // Small sticky panel that always shows the Save button (stays put while scrolling).
            FoodsSaveBar(isSaving = state.isSaving, onSave = { viewModel.saveFoods() })
            // A strip of the (non-scrolling) sticky region below the card, so the scrolling body
            // always keeps a gap from the card instead of sliding up against it.
            Spacer(Modifier.height(2.dp))
            // Each food is its own lazy item so only on-screen rows compose (a single type can
            // hold ~40 foods). The per-type card look is preserved by drawing the card in pieces
            // (Top / Middle / Bottom) across the items, with explicit gap items between cards.
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    // Shrink the list above the keyboard so a focused last-row input scrolls
                    // into view instead of being hidden behind the IME.
                    .imePadding(),
                contentPadding = PaddingValues(
                    start = Spacing.md,
                    end = Spacing.md,
                    top = 0.dp,   // gap handled by the 1dp sticky spacer above
                    bottom = Spacing.lg
                )
            ) {
                FOOD_TYPES.forEachIndexed { typeIndex, type ->
                    val foods = state.foodsByType[type] ?: emptyList()
                    val collapsed = state.collapsedTypes.contains(type)

                    // Gap between cards (collapsed cards stay separate, not merged).
                    if (typeIndex > 0) {
                        item(key = "gap_$type", contentType = "gap") {
                            Spacer(Modifier.height(Spacing.sm))
                        }
                    }

                    item(key = "header_$type", contentType = "type_header") {
                        FoodCardPart(part = if (collapsed) CardPart.Single else CardPart.Top) {
                            FoodTypeHeader(
                                type = type,
                                collapsed = collapsed,
                                onToggle = { viewModel.toggleTypeCollapsed(type) }
                            )
                        }
                    }

                    if (!collapsed) {
                        item(key = "thead_$type", contentType = "table_header") {
                            FoodCardPart(part = CardPart.Middle) {
                                Column {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    FoodTableHeader()
                                }
                            }
                        }

                        val lastIndex = foods.lastIndex
                        itemsIndexed(
                            items = foods,
                            // Index-based key is stable while typing (the name can change without
                            // re-keying the row) and there is no row removal/reorder here.
                            key = { index, _ -> "$type:$index" },
                            contentType = { _, _ -> "food_row" }
                        ) { index, food ->
                            // The trailing blank row is always last, so it rounds off the card.
                            FoodCardPart(part = if (index == lastIndex) CardPart.Bottom else CardPart.Middle) {
                                FoodEditRow(
                                    food = food,
                                    onNameChange = { viewModel.updateFoodField(type, index) { copy(name = it) } },
                                    onProteinChange = { viewModel.updateFoodField(type, index) { copy(protein = it) } },
                                    onFatChange = { viewModel.updateFoodField(type, index) { copy(fat = it) } },
                                    onCaloriesChange = { viewModel.updateFoodField(type, index) { copy(calories = it) } },
                                    onInnerTypeChange = { viewModel.updateFoodField(type, index) { copy(innerType = it) } },
                                    onAvailableChange = { viewModel.updateFoodField(type, index) { copy(available = it) } }
                                )
                            }
                        }
                    }
                }
            }
            } // Column
            } // PullToRefreshBox
        }
    }
}

// Sticky Save panel at the top — wrapped in a card like the Day tab's panel, holding a
// compact gradient Save button (Day-tab width), right-aligned.
@Composable
private fun FoodsSaveBar(isSaving: Boolean, onSave: () -> Unit) {
    val gradient = Brush.horizontalGradient(listOf(OkGreen, Cyan))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm),
        shape = RoundedCornerShape(18.dp),
        color = SurfaceSticky,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSaving) Brush.horizontalGradient(listOf(Color.Gray, Color.Gray)) else gradient)
                    .clickable(enabled = !isSaving, onClick = onSave),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isSaving) "…" else "Save",
                    color = Color(0xFF001821),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

// ---------- Per-type card, drawn in pieces across lazy items ----------

private enum class CardPart { Single, Top, Middle, Bottom }

@Composable
private fun FoodCardPart(
    part: CardPart,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val radius = 18.dp
    val shape = when (part) {
        CardPart.Single -> RoundedCornerShape(radius)
        CardPart.Top -> RoundedCornerShape(topStart = radius, topEnd = radius)
        CardPart.Bottom -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
        CardPart.Middle -> RectangleShape
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .drawBehind {
                val sw = 1.dp.toPx()
                val rad = radius.toPx()
                val w = size.width
                val h = size.height
                val half = sw / 2f
                val stroke = Stroke(sw)
                val arc = Size(2 * rad - sw, 2 * rad - sw)
                val top = part == CardPart.Top || part == CardPart.Single
                val bottom = part == CardPart.Bottom || part == CardPart.Single

                // Vertical sides — inset at the rounded ends.
                drawLine(borderColor, Offset(half, if (top) rad else 0f), Offset(half, if (bottom) h - rad else h), sw)
                drawLine(borderColor, Offset(w - half, if (top) rad else 0f), Offset(w - half, if (bottom) h - rad else h), sw)

                if (top) {
                    drawLine(borderColor, Offset(rad, half), Offset(w - rad, half), sw)
                    drawArc(borderColor, 180f, 90f, false, Offset(half, half), arc, style = stroke)
                    drawArc(borderColor, 270f, 90f, false, Offset(w - 2 * rad + half, half), arc, style = stroke)
                }
                if (bottom) {
                    drawLine(borderColor, Offset(rad, h - half), Offset(w - rad, h - half), sw)
                    drawArc(borderColor, 90f, 90f, false, Offset(half, h - 2 * rad + half), arc, style = stroke)
                    drawArc(borderColor, 0f, 90f, false, Offset(w - 2 * rad + half, h - 2 * rad + half), arc, style = stroke)
                }
            }
    ) {
        content()
    }
}

@Composable
private fun FoodTypeHeader(
    type: String,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.Min)
            .clickable { onToggle() }
            .padding(start = Spacing.md, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = type.uppercase(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onToggle, modifier = Modifier.size(Touch.Min)) {
            Icon(
                if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// Shared column widths so the header and every food row line up like a table.
private object FCols {
    val protein = 44.dp
    val fat = 44.dp
    val cal = 50.dp
    val type = 46.dp
    val avail = 30.dp
    val gap = 3.dp
}

@Composable
private fun FoodTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(FCols.gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HCol("FOOD", Modifier.weight(1f))
        HCol("PROT", Modifier.width(FCols.protein))
        HCol("FAT", Modifier.width(FCols.fat))
        HCol("CAL", Modifier.width(FCols.cal))
        HCol("TYPE", Modifier.width(FCols.type))
        HCol("AVL", Modifier.width(FCols.avail))
    }
}

@Composable
private fun HCol(text: String, modifier: Modifier) {
    Text(
        text,
        modifier = modifier,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible
    )
}

@Composable
private fun FoodEditRow(
    food: FoodItemUiState,
    onNameChange: (String) -> Unit,
    onProteinChange: (String) -> Unit,
    onFatChange: (String) -> Unit,
    onCaloriesChange: (String) -> Unit,
    onInnerTypeChange: (String) -> Unit,
    onAvailableChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(FCols.gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FoodCell(food.name, onNameChange, Modifier.weight(1f))
        FoodCell(food.protein, onProteinChange, Modifier.width(FCols.protein), numeric = true, fontSize = 12)
        FoodCell(food.fat, onFatChange, Modifier.width(FCols.fat), numeric = true, fontSize = 12)
        FoodCell(food.calories, onCaloriesChange, Modifier.width(FCols.cal), numeric = true, fontSize = 12)
        FoodCell(food.innerType, onInnerTypeChange, Modifier.width(FCols.type), fontSize = 12)
        // Availability is free text (Y / N / blank), exactly like the web — editable, never
        // rewritten on save.
        FoodCell(food.available, onAvailableChange, Modifier.width(FCols.avail), fontSize = 12)
    }
}

// Compact bordered cell — renders as cheap Text and swaps to a BasicTextField only while it's
// being edited (tap to edit). This keeps a viewport full of rows from composing dozens of text
// fields at once, which is what caused the freeze when a new table scrolled into view.
@Composable
private fun FoodCell(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    fontSize: Int = 14
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(40.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (focused) Cyan else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { fs ->
                    focused = fs.isFocused
                    // Numeric cells: clear a lone "0" on focus, restore "0" when left blank.
                    if (numeric) {
                        if (fs.isFocused && value == "0") onValueChange("")
                        else if (!fs.isFocused && value.isBlank()) onValueChange("0")
                    }
                },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.SemiBold
            ),
            cursorBrush = SolidColor(Cyan),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                imeAction = ImeAction.Next
            )
        )
    }
}
