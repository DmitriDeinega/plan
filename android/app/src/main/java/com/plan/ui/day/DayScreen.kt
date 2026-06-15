package com.plan.ui.day

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.plan.ui.theme.Cyan
import com.plan.ui.theme.DangerRed
import com.plan.ui.theme.OkGreen
import com.plan.ui.theme.Purple
import com.plan.ui.theme.Spacing
import com.plan.ui.theme.SurfaceSticky
import com.plan.ui.theme.Touch
import com.plan.ui.theme.formatNumber
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    viewModel: DayViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(state.snackMessage) {
        state.snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnack()
        }
    }

    // NOTE: no scroll-to-dismiss-keyboard here. The IME's bring-into-view scroll is reported as
    // user input by this Compose version, so any scroll-based focus clear slams the keyboard
    // shut the instant it opens. Tapping empty space (detectTapGestures below) still dismisses.

    // Pull fresh data from server every time the user returns to this screen
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Compute "derived locked" set once per state change (was inside item lambda, costly)
    val derivedLocked = remember(state.meals) {
        state.meals.filter { GROUP_NAMES.contains(it.name) }
            .filter { group ->
                state.meals.any { m ->
                    !GROUP_NAMES.contains(m.name) && m.mealClosed &&
                            m.foods.any { it.name.equals(group.name, ignoreCase = true) }
                }
            }
            .map { it.name }
            .toSet()
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                val msg = data.visuals.message
                // Green only for known successes; everything else (incl. validation) is red.
                val ok = msg.contains("success", true) || msg.contains("saved", true) ||
                        msg.contains("ended", true) || msg.contains("reverted", true)
                // Bright green (or red) background so the result is unmissable.
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
        if (state.isLoading && state.meals.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Cyan)
            }
        } else {
            // Pull-to-refresh wraps the WHOLE screen (sticky panel included) since a refresh
            // reloads everything — the spinner appears above the panel, not inside the list.
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.pullRefresh() },
                modifier = Modifier.fillMaxSize()
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = padding.calculateBottomPadding())
            ) {
                // Fixed top panel — never scrolls. Sits directly under the global "Plan" bar.
                DayStickyHeader(
                    state = state,
                    onPrevDay = viewModel::navigatePrevDay,
                    onNextDay = viewModel::navigateNextDay,
                    onDatePick = viewModel::setDate,
                    onWeightChange = viewModel::setWeight,
                    onSave = viewModel::saveDay,
                    onEndDay = viewModel::endDay,
                    onRevert = viewModel::revertDay
                )
                // A strip of the (non-scrolling) sticky region below the panel, so the scrolling
                // body always keeps a gap from the panel instead of sliding up against it.
                Spacer(Modifier.height(2.dp))
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        // Shrink the list above the keyboard so a focused last-row input scrolls
                        // into view instead of being hidden behind the IME.
                        .imePadding()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { focusManager.clearFocus() })
                        },
                    contentPadding = PaddingValues(
                        start = Spacing.md,
                        end = Spacing.md,
                        top = 0.dp,   // gap handled by the 1dp sticky spacer above
                        bottom = Spacing.lg
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    if (state.error != null) {
                    item(key = "err", contentType = "error") {
                        ErrorCard(state.error!!)
                    }
                }

                itemsIndexed(
                    items = state.meals,
                    key = { _, meal -> meal.name },
                    contentType = { _, _ -> "meal" }
                ) { mealIndex, meal ->
                    val isGroup = GROUP_NAMES.contains(meal.name)
                    MealCard(
                        meal = meal,
                        isGroup = isGroup,
                        isDerivedLocked = meal.name in derivedLocked,
                        dayClosed = state.dayClosed,
                        onToggleCollapse = { viewModel.toggleMealCollapsed(mealIndex) },
                        onSignMeal = { viewModel.signMeal(mealIndex) },
                        onFoodNameChange = { fi, name -> viewModel.updateFoodName(mealIndex, fi, name) },
                        onFoodWeightChange = { fi, w -> viewModel.updateFoodWeight(mealIndex, fi, w) },
                        onRemoveFood = { fi -> viewModel.removeFoodRow(mealIndex, fi) },
                        getSuggestions = { query -> viewModel.getSuggestionsForMeal(meal.name, query) }
                    )
                }
                }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(Spacing.md),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

// ---------- Sticky header ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayStickyHeader(
    state: DayUiState,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onDatePick: (String) -> Unit,
    onWeightChange: (String) -> Unit,
    onSave: () -> Unit,
    onEndDay: () -> Unit,
    onRevert: () -> Unit,
    topInset: androidx.compose.ui.unit.Dp = 0.dp
) {
    // The summary grid's natural height defines the panel; the date/weight column and
    // the actions bubble are pinned to that exact height so all three line up.
    var panelHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val panelHeight = if (panelHeightPx > 0) with(density) { panelHeightPx.toDp() } else 90.dp

    // Opaque rounded card with a hairline border. Inset horizontally by the same amount
    // as the meal cards (LazyColumn contentPadding = Spacing.md) so it lines up with them
    // instead of running edge-to-edge.
    Surface(
        modifier = Modifier
            .padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm)
            .fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = SurfaceSticky,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, top = topInset + Spacing.sm, bottom = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Left column — date and weight bubbles each fill half the panel (small gap).
            // Kept narrow + close to the edge so the middle summary grid gets more width.
            Column(
                modifier = Modifier.width(110.dp).height(panelHeight),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                DateBubble(
                    date = state.date,
                    // Can move forward up to (and including) the open day.
                    canGoNext = state.openDate.isNotEmpty() &&
                            compareDdMmYyyy(state.date, state.openDate) < 0,
                    onPrevDay = onPrevDay,
                    onNextDay = onNextDay,
                    onDatePick = onDatePick,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                WeightBubble(
                    weight = state.weight,
                    onChange = onWeightChange,
                    enabled = !state.dayClosed,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
            // Middle — summary grid fills the remaining width; its height drives the panel.
            CompactNutritionGrid(
                state.nutrition,
                modifier = Modifier
                    .weight(1f)
                    .onSizeChanged { if (it.height > 0) panelHeightPx = it.height }
            )
            // Right — actions bubble. The open day is the only one not closed, so
            // !dayClosed == active. When the open day is tomorrow (today was just ended),
            // the second button is Revert instead of End. Closed days keep an empty card.
            val isActiveDay = !state.dayClosed
            val openIsTomorrow = state.openDate.isNotEmpty() &&
                    state.openDate == addDays(todayDdMmYyyy(), 1)
            DayActions(
                isActiveDay = isActiveDay,
                showRevert = isActiveDay && openIsTomorrow,
                isSaving = state.isSaving,
                onSave = onSave,
                onEndDay = onEndDay,
                onRevert = onRevert,
                modifier = Modifier.height(panelHeight)
            )
        }
    }
}

// A rounded "bubble" card matching the web's .bubble (subtle fill + hairline border).
@Composable
private fun Bubble(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateBubble(
    date: String,
    canGoNext: Boolean,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onDatePick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Bubble(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevDay, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Filled.ChevronLeft, "Previous day", tint = Cyan, modifier = Modifier.size(14.dp))
            }
            // Inner date box (own rounded field, like the web's .dateInput).
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 5.dp)
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                    .clickable {
                        val sdf = SimpleDateFormat("ddMMyyyy", Locale.getDefault())
                        val cal = Calendar.getInstance()
                        try { cal.time = sdf.parse(date)!! } catch (_: Exception) {}
                        DatePickerDialog(
                            context,
                            { _, y, m, d -> onDatePick(String.format(Locale.US, "%02d%02d%04d", d, m + 1, y)) },
                            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
                        ).show()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatDateShort(date),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false
                )
            }
            IconButton(onClick = onNextDay, enabled = canGoNext, modifier = Modifier.size(20.dp)) {
                Icon(
                    Icons.Filled.ChevronRight, "Next day",
                    tint = if (canGoNext) Cyan else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun WeightBubble(
    weight: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) Cyan else Color.White.copy(alpha = 0.14f)
    Bubble(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Weight",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Inner weight field box (number only).
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(vertical = 5.dp)
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                if (enabled) {
                    BasicTextField(
                        value = weight,
                        onValueChange = onChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { fs ->
                                focused = fs.isFocused
                                if (fs.isFocused && weight == "0") onChange("")
                                else if (!fs.isFocused && weight.isBlank()) onChange("0")
                            },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Cyan),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done
                        )
                    )
                } else {
                    // Closed day: plain text (a disabled BasicTextField was rendering blank/shifted).
                    Text(
                        text = weight,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
            // "kg" outside the input, to its right.
            Text("kg", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDateShort(ddMMyyyy: String): String {
    return try {
        val src = SimpleDateFormat("ddMMyyyy", Locale.US)
        val dst = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        dst.format(src.parse(ddMMyyyy)!!)
    } catch (_: Exception) { ddMMyyyy }
}

// ---------- Compact 3×3 Daily / Needed / Left ----------

@Composable
private fun CompactNutritionGrid(n: NutritionSummary, modifier: Modifier = Modifier) {
    val proteinOk = n.proteinNeeded > 0f && (n.proteinNeeded - n.protein) <= 0f
    val fatOk = n.fatCaloriesNeeded > 0f && (n.fatCaloriesNeeded - n.fatCalories) >= 0f
    val caloriesLeft = n.caloriesNeeded - n.calories
    val caloriesOk = when {
        n.caloriesNeeded <= 0f -> false
        n.calorieType.equals("deficit", ignoreCase = true) -> caloriesLeft >= 0f
        else -> caloriesLeft <= 0f
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.04f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            // Column headers row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Spacer(Modifier.weight(1.3f))
                GridColHeader("Daily", Modifier.weight(1f))
                GridColHeader("Needed", Modifier.weight(1f))
                GridColHeader("Left", Modifier.weight(1f))
            }
            CompactNutritionRow(
                label = "Protein",
                daily = n.protein,
                needed = n.proteinNeeded,
                decimals = 2,
                leftOk = proteinOk
            )
            CompactNutritionRow(
                label = "Fat Cal",
                daily = n.fatCalories,
                needed = n.fatCaloriesNeeded,
                decimals = 2,
                leftOk = fatOk
            )
            CompactNutritionRow(
                label = "Calories",
                daily = n.calories,
                needed = n.caloriesNeeded,
                decimals = 2,
                leftOk = caloriesOk
            )
        }
    }
}

@Composable
private fun GridColHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false
    )
}

@Composable
private fun CompactNutritionRow(
    label: String,
    daily: Float,
    needed: Float,
    decimals: Int,
    leftOk: Boolean
) {
    val left = needed - daily
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label.uppercase(),
            modifier = Modifier.weight(1.3f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip
        )
        // No thousands separators here — keeps wide 2-decimal values from clipping in
        // the narrow summary, and matches the web (which shows e.g. 1064.13).
        CompactCell(
            text = formatNumber(daily, decimals).replace(",", ""),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface
        )
        CompactCell(
            text = formatNumber(needed, decimals).replace(",", ""),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CompactCell(
            text = formatSignedNumber(left, decimals).replace(",", ""),
            modifier = Modifier.weight(1f),
            color = if (leftOk) OkGreen else DangerRed,
            bold = true
        )
    }
}

@Composable
private fun CompactCell(
    text: String,
    modifier: Modifier,
    color: Color,
    bold: Boolean = false
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 9.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false
    )
}

private fun formatSignedNumber(value: Float, decimals: Int): String {
    return when {
        value == 0f -> "0"
        value > 0f -> formatNumber(value, decimals)
        else -> "-${formatNumber(-value, decimals)}"
    }
}

// ---------- Day actions: a card (bubble) holding the buttons, matching the summary height ----------

@Composable
private fun DayActions(
    isActiveDay: Boolean,
    showRevert: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
    onEndDay: () -> Unit,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The card is always present (keeps the 3-column layout); only the buttons are hidden
    // on closed days.
    Bubble(modifier = modifier.width(64.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (isActiveDay) {
                GradientButton(
                    text = if (isSaving) "…" else "Save",
                    enabled = !isSaving,
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Spacer(Modifier.height(5.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                Spacer(Modifier.height(5.dp))
                GradientButton(
                    text = if (showRevert) "Revert" else "End",
                    enabled = !isSaving,
                    onClick = if (showRevert) onRevert else onEndDay,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GradientButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradient = Brush.horizontalGradient(listOf(OkGreen, Cyan))
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (enabled) gradient else Brush.horizontalGradient(listOf(Color.Gray, Color.Gray))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = Color(0xFF001821),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

// ---------- Meal card ----------

@Composable
private fun MealCard(
    meal: MealUiState,
    isGroup: Boolean,
    isDerivedLocked: Boolean,
    dayClosed: Boolean,
    onToggleCollapse: () -> Unit,
    onSignMeal: () -> Unit,
    onFoodNameChange: (Int, String) -> Unit,
    onFoodWeightChange: (Int, String) -> Unit,
    onRemoveFood: (Int) -> Unit,
    getSuggestions: (String) -> List<String>
) {
    val border = when {
        // On a closed day every meal is read-only, so the green/cyan accents are redundant.
        dayClosed -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        meal.mealClosed -> OkGreen.copy(alpha = 0.45f)
        isDerivedLocked -> Cyan.copy(alpha = 0.35f)
        isGroup -> Cyan.copy(alpha = 0.25f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,   // opaque -> less scroll overdraw
        border = BorderStroke(1.dp, border)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MealHeader(
                meal = meal,
                isGroup = isGroup,
                isDerivedLocked = isDerivedLocked,
                dayClosed = dayClosed,
                onToggleCollapse = onToggleCollapse,
                onSignMeal = onSignMeal
            )

            AnimatedVisibility(
                visible = !meal.collapsed,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val mealReadOnly = dayClosed || meal.mealClosed || isDerivedLocked
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FoodHeaderRow()
                    meal.foods.forEachIndexed { foodIndex, food ->
                        key(foodIndex) {
                            FoodRow(
                                food = food,
                                readOnly = mealReadOnly,
                                onNameChange = { onFoodNameChange(foodIndex, it) },
                                onWeightChange = { onFoodWeightChange(foodIndex, it) },
                                onRemove = { onRemoveFood(foodIndex) },
                                getSuggestions = getSuggestions
                            )
                        }
                    }
                    // SUM always shown (even with no foods).
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MealTotalsRow(foods = meal.foods)
                }
            }
        }
    }
}

@Composable
private fun MealHeader(
    meal: MealUiState,
    isGroup: Boolean,
    isDerivedLocked: Boolean,
    dayClosed: Boolean,
    onToggleCollapse: () -> Unit,
    onSignMeal: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.Min)
            .clickable { onToggleCollapse() }
            .padding(start = Spacing.md, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = meal.name.uppercase(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (isDerivedLocked) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Locked by signed meal",
                tint = Cyan.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        if (!isGroup && !dayClosed && !isDerivedLocked) {
            IconButton(onClick = onSignMeal, modifier = Modifier.size(Touch.Min)) {
                Icon(
                    if (meal.mealClosed) Icons.Filled.CheckCircle else Icons.Filled.CheckCircleOutline,
                    contentDescription = if (meal.mealClosed) "Unsign meal" else "Sign meal",
                    tint = if (meal.mealClosed) OkGreen else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        IconButton(onClick = onToggleCollapse, modifier = Modifier.size(Touch.Min)) {
            Icon(
                if (meal.collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                contentDescription = if (meal.collapsed) "Expand" else "Collapse",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// Shared column widths so header, food rows, and the sum row line up like a table.
private object FoodCols {
    val weight = 40.dp
    val p = 48.dp
    val f = 34.dp
    val cal = 52.dp
    val remove = 22.dp
    val gap = 4.dp
}

@Composable
private fun FoodHeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(FoodCols.gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HCol("FOOD", Modifier.weight(1f), TextAlign.Start)
        HCol("WEIGHT", Modifier.width(FoodCols.weight), TextAlign.Start)
        HCol("PROTEIN", Modifier.width(FoodCols.p), TextAlign.Start)
        HCol("FAT", Modifier.width(FoodCols.f), TextAlign.Start)
        HCol("CALORIES", Modifier.width(FoodCols.cal), TextAlign.Start)
        Spacer(Modifier.width(FoodCols.remove))   // ✕ column, always reserved
    }
}

@Composable
private fun HCol(text: String, modifier: Modifier, align: TextAlign) {
    Text(
        text,
        modifier = modifier,
        fontSize = 8.sp,
        letterSpacing = 0.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible
    )
}

@Composable
private fun ValueCol(value: Float, decimals: Int, modifier: Modifier, emphasize: Boolean = false) {
    Text(
        text = formatNumber(value, decimals),
        modifier = modifier,
        fontSize = 12.sp,
        fontWeight = if (emphasize) FontWeight.Bold else FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Start,
        maxLines = 1,
        softWrap = false
    )
}

@Composable
private fun MealTotalsRow(foods: List<FoodUiState>) {
    val totalP = foods.fold(0f) { a, f -> a + f.protein }
    val totalF = foods.fold(0f) { a, f -> a + f.fat }
    val totalC = foods.fold(0f) { a, f -> a + f.calories }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(FoodCols.gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "SUM",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(FoodCols.weight))
        ValueCol(totalP, 2, Modifier.width(FoodCols.p), emphasize = true)
        ValueCol(totalF, 2, Modifier.width(FoodCols.f), emphasize = true)
        ValueCol(totalC, 2, Modifier.width(FoodCols.cal), emphasize = true)
        Spacer(Modifier.width(FoodCols.remove))   // ✕ column, always reserved
    }
}

// ---------- Food row (compact table) ----------
// Same layout whether editable or signed — only `enabled` (and the ✕ icon) changes, so
// signing/unsigning doesn't shift the rows. The autocomplete uses a lightweight
// focusable=false DropdownMenu (no per-row SubcomposeLayout) for smoother scrolling.
@Composable
private fun FoodRow(
    food: FoodUiState,
    readOnly: Boolean,
    onNameChange: (String) -> Unit,
    onWeightChange: (String) -> Unit,
    onRemove: () -> Unit,
    getSuggestions: (String) -> List<String>
) {
    var expanded by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    val isGroupRef = isGroupReference(food.name)
    val hasContent = food.name.isNotBlank() || food.weight.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(FoodCols.gap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Name — editable box + dropdown when the meal is open; plain text when signed/locked.
        if (readOnly) {
            Text(
                text = food.name,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Box(modifier = Modifier.weight(1f)) {
                CompactInputCell(
                    value = food.name,
                    onValueChange = { newName ->
                        onNameChange(newName)
                        suggestions = getSuggestions(newName)
                        expanded = newName.isNotBlank() && suggestions.isNotEmpty()
                    },
                    enabled = true,
                    textAlign = TextAlign.Start,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    onFocusChanged = { focused ->
                        if (!focused) expanded = false
                        else if (food.name.isNotBlank()) {
                            suggestions = getSuggestions(food.name)
                            expanded = suggestions.isNotEmpty()
                        }
                    }
                )
                DropdownMenu(
                    expanded = expanded && suggestions.isNotEmpty(),
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = false),
                    modifier = Modifier.heightIn(max = 260.dp).width(200.dp),
                    shape = RoundedCornerShape(12.dp),
                    containerColor = Color(0xFF26324C),   // lighter than cards so the menu stands out
                    border = BorderStroke(1.dp, Cyan.copy(alpha = 0.35f)),
                    shadowElevation = 12.dp
                ) {
                    suggestions.forEachIndexed { i, s ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    s,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            modifier = Modifier.height(40.dp),
                            onClick = {
                                onNameChange(s)
                                expanded = false
                            }
                        )
                        if (i < suggestions.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }

        // Weight — plain text when locked or a group reference; editable box otherwise.
        if (readOnly || isGroupRef) {
            Text(
                text = if (isGroupRef) "" else food.weight,
                modifier = Modifier.width(FoodCols.weight).padding(start = 4.dp),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = 1,
                softWrap = false
            )
        } else {
            CompactInputCell(
                value = food.weight,
                onValueChange = onWeightChange,
                modifier = Modifier.width(FoodCols.weight),
                enabled = true,
                textAlign = TextAlign.Start,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                onFocusChanged = { focused ->
                    if (focused && food.weight == "0") onWeightChange("")
                    else if (!focused && food.weight.isBlank() && food.name.isNotBlank()) onWeightChange("0")
                }
            )
        }

        // Macros: show numbers (incl. 0) for real rows; blank on the empty trailing row.
        if (food.name.isNotBlank()) {
            ValueCol(food.protein, 2, Modifier.width(FoodCols.p))
            ValueCol(food.fat, 2, Modifier.width(FoodCols.f))
            ValueCol(food.calories, 2, Modifier.width(FoodCols.cal))
        } else {
            Spacer(Modifier.width(FoodCols.p))
            Spacer(Modifier.width(FoodCols.f))
            Spacer(Modifier.width(FoodCols.cal))
        }

        // ✕ column width always reserved so signing/unsigning never shifts the layout.
        Box(modifier = Modifier.width(FoodCols.remove), contentAlignment = Alignment.Center) {
            if (!readOnly && hasContent) {
                IconButton(onClick = onRemove, modifier = Modifier.size(FoodCols.remove)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove food",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// Compact bordered text cell (BasicTextField) — much shorter than OutlinedTextField,
// so a whole food fits on one row like the web table.
@Composable
private fun CompactInputCell(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textAlign: TextAlign = TextAlign.Start,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onFocusChanged: (Boolean) -> Unit = {}
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
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { fs -> focused = fs.isFocused; onFocusChanged(fs.isFocused) },
            enabled = enabled,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = textAlign
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Cyan),
            keyboardOptions = keyboardOptions,
            decorationBox = { inner ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (textAlign == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
                    }
                    inner()
                }
            }
        )
    }
}
