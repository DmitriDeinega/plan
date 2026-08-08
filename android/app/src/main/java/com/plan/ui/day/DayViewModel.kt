package com.plan.ui.day

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plan.data.api.*
import com.plan.data.repository.PlanRepository
import com.plan.data.repository.Result
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlin.math.roundToInt

@Immutable
data class FoodUiState(
    val name: String = "",
    val weight: String = "",
    val protein: Float = 0f,
    val fat: Float = 0f,
    val calories: Float = 0f
)

@Immutable
data class MealUiState(
    val name: String = "",
    val mealClosed: Boolean = false,
    val foods: List<FoodUiState> = emptyList(),
    val collapsed: Boolean = false
)

@Immutable
data class NutritionSummary(
    val protein: Float = 0f,
    val fatCalories: Float = 0f,
    val calories: Float = 0f,
    val proteinNeeded: Float = 0f,
    val fatCaloriesNeeded: Float = 0f,
    val caloriesNeeded: Float = 0f,
    val calorieType: String = ""
)

data class DayUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val date: String = todayDdMmYyyy(),
    val weight: String = "0",
    val dayClosed: Boolean = false,
    val meals: List<MealUiState> = emptyList(),
    val nutrition: NutritionSummary = NutritionSummary(),
    val settings: Settings? = null,
    val foodItems: List<FoodItem> = emptyList(),
    val snackMessage: String? = null,
    val isSaving: Boolean = false,
    val startDate: String = "",
    val openDate: String = "",
    /** Frozen goals/totals for a CLOSED day, straight from the server. Null on an open day,
     *  where everything is computed live. Always replaced (or cleared) in applyDay so a newly
     *  loaded day can never inherit the previous day's frozen values. */
    val frozenTargets: Targets? = null,
    val frozenSnapshot: SettingsSnapshot? = null,
    val frozenNutrition: Nutrition? = null,
    /** True when the user has unsaved edits; blocks the on-resume refresh from clobbering them. */
    val dirty: Boolean = false,
    val isRefreshing: Boolean = false
)

val GROUP_NAMES = setOf("Fruits", "Nuts", "Vegetables")
val GROUP_NAMES_UPPER = GROUP_NAMES.map { it.uppercase() }.toSet()

/** A food row in a regular meal can reference a group (by name); when it does, weight is meaningless. */
fun isGroupReference(name: String): Boolean = name.trim().uppercase() in GROUP_NAMES_UPPER

fun todayDdMmYyyy(): String {
    val sdf = SimpleDateFormat("ddMMyyyy", Locale.getDefault())
    return sdf.format(Date())
}

fun formatDateDisplay(ddMMyyyy: String): String {
    return try {
        val sdf = SimpleDateFormat("ddMMyyyy", Locale.getDefault())
        val display = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
        display.format(sdf.parse(ddMMyyyy)!!)
    } catch (e: Exception) {
        ddMMyyyy
    }
}

fun addDays(ddMMyyyy: String, days: Int): String {
    return try {
        val sdf = SimpleDateFormat("ddMMyyyy", Locale.getDefault())
        val cal = Calendar.getInstance()
        cal.time = sdf.parse(ddMMyyyy)!!
        cal.add(Calendar.DAY_OF_YEAR, days)
        sdf.format(cal.time)
    } catch (e: Exception) {
        ddMMyyyy
    }
}

fun isToday(ddMMyyyy: String): Boolean = ddMMyyyy == todayDdMmYyyy()

/** Compares two ddMMyyyy strings as dates. Returns <0, 0, >0. Falls back to lexical compare on parse failure. */
fun compareDdMmYyyy(a: String, b: String): Int {
    return try {
        val sdf = SimpleDateFormat("ddMMyyyy", Locale.US)
        val ta = sdf.parse(a)!!.time
        val tb = sdf.parse(b)!!.time
        ta.compareTo(tb)
    } catch (_: Exception) { a.compareTo(b) }
}

@HiltViewModel
class DayViewModel @Inject constructor(
    private val repository: PlanRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DayUiState())
    val uiState: StateFlow<DayUiState> = _uiState.asStateFlow()

    /** Incremented on every day load. A response whose generation is stale is discarded, so
     *  fast A -> B navigation can't let A land after B and restore A's frozen values. */
    private var dayLoadSeq = 0

    init {
        loadInitialData()
    }

    /** Re-fetches settings, foods, and the currently-shown day from the server. */
    fun refresh() {
        viewModelScope.launch {
            val settingsResult = repository.getSettings()
            val foodsResult = repository.getFoods()
            val settings = if (settingsResult is Result.Success) settingsResult.data else _uiState.value.settings
            val foods = if (foodsResult is Result.Success) foodsResult.data else _uiState.value.foodItems
            _uiState.update {
                it.copy(
                    settings = settings,
                    foodItems = foods,
                    // startDate also comes from settings; refreshing it here keeps the
                    // back-navigation bound correct if it ever changes server-side.
                    startDate = settings?.start_date ?: it.startDate
                )
            }
            // Keep the open-day bound current. It gates the ">" button and the date picker's
            // upper limit, and it moves whenever a day is ended server-side (including by the
            // web/Excel clients or the automatic midnight rollover).
            refreshOpenDate()
            // Re-load the current day to pick up server-side edits — but NOT while the user
            // has unsaved changes, or returning to the app would silently wipe their input.
            if (!_uiState.value.dirty) loadDayByDate(_uiState.value.date)
        }
    }

    /** Fetch just the open day's date. Unlike loadOpenDay() this does not touch the shown day,
     *  so it is safe to call while the user is looking at some other date. */
    private suspend fun refreshOpenDate() {
        when (val r = repository.getOpenDay()) {
            is Result.Success -> _uiState.update { it.copy(openDate = r.data.date) }
            is Result.Error -> Unit   // keep the previous bound; a transient failure shouldn't unlock nav
        }
    }

    /** Explicit pull-to-refresh: always reloads from server (discards local edits). */
    fun pullRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val settingsResult = repository.getSettings()
            val foodsResult = repository.getFoods()
            val settings = if (settingsResult is Result.Success) settingsResult.data else _uiState.value.settings
            val foods = if (foodsResult is Result.Success) foodsResult.data else _uiState.value.foodItems
            _uiState.update { it.copy(settings = settings, foodItems = foods) }
            val seq = ++dayLoadSeq
            when (val result = repository.getDay(_uiState.value.date)) {
                is Result.Success -> if (seq == dayLoadSeq) applyDay(result.data)
                is Result.Error -> _uiState.update { it.copy(snackMessage = result.message) }
            }
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val settingsResult = repository.getSettings()
            val foodsResult = repository.getFoods()
            val settings = if (settingsResult is Result.Success) settingsResult.data else null
            val foods = if (foodsResult is Result.Success) foodsResult.data else emptyList()
            _uiState.update {
                it.copy(
                    settings = settings,
                    foodItems = foods,
                    startDate = settings?.start_date ?: ""
                )
            }
            loadOpenDay()
        }
    }

    private fun loadOpenDay() {
        viewModelScope.launch {
            val seq = ++dayLoadSeq
            when (val result = repository.getOpenDay()) {
                is Result.Success -> {
                    // Record the open date even if a newer load has superseded this one: it is
                    // a property of the server, not of the day being shown, and it is the only
                    // thing gating ">" / the date picker's upper bound. Returning early here
                    // (as the stale-response guard used to) left it empty whenever the ON_RESUME
                    // refresh raced startup — which disabled ">" for the whole session.
                    _uiState.update { it.copy(openDate = result.data.date) }
                    if (seq != dayLoadSeq) return@launch
                    applyDay(result.data)
                }
                is Result.Error -> {
                    if (seq != dayLoadSeq) return@launch
                    _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
    }

    fun loadDayByDate(date: String) {
        viewModelScope.launch {
            val prevDate = _uiState.value.date
            val seq = ++dayLoadSeq
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = repository.getDay(date)) {
                is Result.Success -> {
                    // A newer load started while this one was in flight — dropping it stops an
                    // older day's frozen targets from overwriting the day now on screen.
                    if (seq != dayLoadSeq) return@launch
                    applyDay(result.data)
                }
                is Result.Error -> {
                    if (seq != dayLoadSeq) return@launch
                    _uiState.update {
                        // Roll back to the previous day (matches web behavior) instead of leaving the screen empty.
                        it.copy(isLoading = false, date = prevDate, snackMessage = result.message)
                    }
                }
            }
        }
    }

    private fun applyDay(day: Day) {
        val mealUiStates = day.meals.map { meal ->
            MealUiState(
                name = meal.name,
                mealClosed = meal.meal_closed,
                foods = meal.foods.map { food ->
                    FoodUiState(
                        name = food.name,
                        // Keep 0 as "0" (like the web) so group foods such as Peach show/validate;
                        // drop a trailing ".0" for whole numbers.
                        weight = food.weight?.let { w ->
                            if (w == w.toLong().toFloat()) w.toLong().toString() else w.toString()
                        } ?: "",
                        protein = food.protein,
                        fat = food.fat,
                        calories = food.calories
                    )
                }
            )
        }
        val weightStr = day.weight.let { w ->
            if (w == w.toLong().toFloat()) w.toLong().toString() else w.toString()  // 75.0 -> "75"
        }
        // On load, a meal that comes back signed starts collapsed (and a group locked by one
        // collapses with it) — the day opens showing only what still needs work.
        val locked = computeDerivedLockedGroups(mealUiStates)
        val collapsed = mealUiStates.map { m ->
            val isGroup = GROUP_NAMES.contains(m.name)
            m.copy(collapsed = if (isGroup) locked.contains(m.name) else m.mealClosed)
        }
        val withGroups = ensureTrailingEmptyRows(
            syncGroupClosedFlags(propagateGroupReferences(collapsed)), day.day_closed
        )
        _uiState.update { state ->
            state.copy(
                isLoading = false,
                date = day.date,
                weight = weightStr,
                dayClosed = day.day_closed,
                meals = withGroups,
                // Unconditional assignment (not `?:`) — a day without frozen values must CLEAR
                // whatever the previously shown day left behind.
                frozenTargets = day.targets,
                frozenSnapshot = day.settings_snapshot,
                frozenNutrition = day.nutrition,
                nutrition = computeNutrition(
                    withGroups, weightStr, state.settings, day.date,
                    day.targets, day.settings_snapshot, day.nutrition
                ),
                dirty = false
            )
        }
    }

    fun navigatePrevDay() {
        val state = _uiState.value
        val prev = addDays(state.date, -1)
        if (state.startDate.isNotEmpty() && compareDdMmYyyy(prev, state.startDate) < 0) return
        loadDayByDate(prev)
    }

    fun navigateNextDay() {
        val state = _uiState.value
        val next = addDays(state.date, 1)
        if (state.openDate.isNotEmpty() && compareDdMmYyyy(next, state.openDate) > 0) return
        loadDayByDate(next)
    }

    fun setDate(date: String) {
        val state = _uiState.value
        if (state.startDate.isNotEmpty() && compareDdMmYyyy(date, state.startDate) < 0) return
        if (state.openDate.isNotEmpty() && compareDdMmYyyy(date, state.openDate) > 0) return
        loadDayByDate(date)
    }

    fun setWeight(w: String) {
        _uiState.update { state ->
            state.copy(
                weight = w,
                dirty = true,
                nutrition = computeNutrition(state.meals, w, state.settings, state.date,
                    state.frozenTargets, state.frozenSnapshot, state.frozenNutrition)
            )
        }
    }

    fun toggleMealCollapsed(mealIndex: Int) {
        _uiState.update { state ->
            val meals = state.meals.toMutableList()
            if (mealIndex < meals.size) {
                meals[mealIndex] = meals[mealIndex].copy(collapsed = !meals[mealIndex].collapsed)
            }
            state.copy(meals = meals)
        }
    }

    fun updateFoodName(mealIndex: Int, foodIndex: Int, name: String) {
        _uiState.update { state ->
            val meals = state.meals.toMutableList()
            val meal = meals[mealIndex]
            val foods = meal.foods.toMutableList()
            if (foodIndex < foods.size) {
                val food = foods[foodIndex]
                if (isGroupReference(name)) {
                    // Group reference: weight is meaningless, P/F/Cal derived from group meal totals.
                    foods[foodIndex] = food.copy(name = name, weight = "")
                } else {
                    val matched = state.foodItems.find {
                        it.name.equals(name, ignoreCase = true) &&
                                isFoodAllowedInMeal(it, meal.name, state.settings)
                    }
                    foods[foodIndex] = if (matched != null) {
                        val weight = food.weight.toFloatOrNull() ?: 0f
                        food.copy(
                            name = name,
                            protein = roundTo2(weight * matched.protein / 100),
                            fat = roundTo2(weight * matched.fat / 100),
                            calories = roundTo2(weight * matched.calories / 100)
                        )
                    } else {
                        food.copy(name = name)
                    }
                }
            }
            val newMeals = meals.also { it[mealIndex] = meal.copy(foods = foods) }
            // Renaming a row can add or remove a group reference, which changes which groups
            // are derived-closed — re-sync so the next save matches the server's rule.
            val withGroups = ensureTrailingEmptyRows(
                syncGroupClosedFlags(propagateGroupReferences(newMeals)), state.dayClosed
            )
            state.copy(
                meals = withGroups,
                dirty = true,
                nutrition = computeNutrition(withGroups, state.weight, state.settings, state.date,
                    state.frozenTargets, state.frozenSnapshot, state.frozenNutrition)
            )
        }
    }

    fun updateFoodWeight(mealIndex: Int, foodIndex: Int, weightStr: String) {
        _uiState.update { state ->
            val meals = state.meals.toMutableList()
            val meal = meals[mealIndex]
            val foods = meal.foods.toMutableList()
            if (foodIndex < foods.size) {
                val food = foods[foodIndex]
                // Group reference rows can't have a manually edited weight.
                if (isGroupReference(food.name)) return@update state

                val weight = weightStr.toFloatOrNull() ?: 0f
                val matched = state.foodItems.find {
                    it.name.equals(food.name, ignoreCase = true) &&
                            isFoodAllowedInMeal(it, meal.name, state.settings)
                }
                foods[foodIndex] = if (matched != null) {
                    food.copy(
                        weight = weightStr,
                        protein = roundTo2(weight * matched.protein / 100),
                        fat = roundTo2(weight * matched.fat / 100),
                        calories = roundTo2(weight * matched.calories / 100)
                    )
                } else {
                    food.copy(weight = weightStr)
                }
            }
            val newMeals = meals.also { it[mealIndex] = meal.copy(foods = foods) }
            val withGroups = ensureTrailingEmptyRows(propagateGroupReferences(newMeals), state.dayClosed)
            state.copy(
                meals = withGroups,
                dirty = true,
                nutrition = computeNutrition(withGroups, state.weight, state.settings, state.date,
                    state.frozenTargets, state.frozenSnapshot, state.frozenNutrition)
            )
        }
    }

    /**
     * For every food row whose name matches a group (Fruits/Nuts/Vegetables),
     * set its P/F/Cal to the sum of the corresponding group meal's foods.
     * Matches the web's group-derivation behavior.
     */
    private fun propagateGroupReferences(meals: List<MealUiState>): List<MealUiState> {
        // Compute group totals up front
        val totalsByGroupUpper: Map<String, Triple<Float, Float, Float>> =
            meals.filter { GROUP_NAMES.contains(it.name) }
                .associate { groupMeal ->
                    var p = 0f; var f = 0f; var c = 0f
                    for (food in groupMeal.foods) {
                        p += food.protein; f += food.fat; c += food.calories
                    }
                    groupMeal.name.uppercase() to Triple(roundTo2(p), roundTo2(f), roundTo2(c))
                }

        return meals.map { meal ->
            if (GROUP_NAMES.contains(meal.name)) return@map meal
            val updated = meal.foods.map { food ->
                if (isGroupReference(food.name)) {
                    val (gp, gf, gc) = totalsByGroupUpper[food.name.trim().uppercase()]
                        ?: Triple(0f, 0f, 0f)
                    food.copy(weight = "", protein = gp, fat = gf, calories = gc)
                } else food
            }
            meal.copy(foods = updated)
        }
    }

    /**
     * Keep exactly one trailing empty row on every editable meal so the user can
     * always add another food just by typing — unlimited, matching the web. Group
     * meals, signed meals, and a closed day get no editable trailing row.
     */
    private fun ensureTrailingEmptyRows(meals: List<MealUiState>, dayClosed: Boolean): List<MealUiState> {
        if (dayClosed) return meals
        // Group meals (Fruits/Nuts/Vegetables) are editable too — they only become read-only
        // when locked by a signed meal that references them.
        val lockedGroups = computeDerivedLockedGroups(meals)
        return meals.map { meal ->
            val isGroup = GROUP_NAMES.contains(meal.name)
            if (meal.mealClosed || (isGroup && lockedGroups.contains(meal.name))) return@map meal
            val foods = meal.foods
            if (foods.isEmpty() || foods.last().name.isNotBlank()) {
                meal.copy(foods = foods + FoodUiState())
            } else meal
        }
    }

    fun removeFoodRow(mealIndex: Int, foodIndex: Int) {
        _uiState.update { state ->
            val meals = state.meals.toMutableList()
            val meal = meals[mealIndex]
            val newFoods = meal.foods.toMutableList().also { it.removeAt(foodIndex) }
            meals[mealIndex] = meal.copy(foods = newFoods)
            // Removing a group-reference row can unlock that group.
            val withGroups = ensureTrailingEmptyRows(
                syncGroupClosedFlags(propagateGroupReferences(meals)), state.dayClosed
            )
            state.copy(
                meals = withGroups,
                dirty = true,
                nutrition = computeNutrition(withGroups, state.weight, state.settings, state.date,
                    state.frozenTargets, state.frozenSnapshot, state.frozenNutrition)
            )
        }
    }

    fun signMeal(mealIndex: Int) {
        _uiState.update { state ->
            val meals = state.meals.toMutableList()
            val meal = meals[mealIndex]
            if (!GROUP_NAMES.contains(meal.name)) {
                val nowClosed = !meal.mealClosed
                // Drop blank rows when signing (closed meals are read-only, no input row).
                val foods = if (nowClosed) meal.foods.filter { it.name.isNotBlank() } else meal.foods
                // Auto-collapse on sign (still expandable via the chevron), expand on unsign.
                meals[mealIndex] = meal.copy(mealClosed = nowClosed, foods = foods, collapsed = nowClosed)
            }
            // Only the groups referenced by THIS meal follow its lock state: collapse when it
            // locks them, expand when it unlocks them. Groups it doesn't reference are untouched.
            val referenced = meals[mealIndex].foods.mapNotNull { f ->
                GROUP_NAMES.firstOrNull { it.equals(f.name.trim(), ignoreCase = true) }
            }.toSet()
            val locked = computeDerivedLockedGroups(meals)
            for (i in meals.indices) {
                val m = meals[i]
                if (GROUP_NAMES.contains(m.name) && referenced.contains(m.name)) {
                    meals[i] = m.copy(collapsed = locked.contains(m.name))
                }
            }
            val withGroups = ensureTrailingEmptyRows(syncGroupClosedFlags(meals), state.dayClosed)
            state.copy(meals = withGroups, dirty = true)
        }
    }

    /**
     * A group meal's `meal_closed` is DERIVED, never chosen: the server requires it to equal
     * "is this group referenced by some signed regular meal". Keeping the flag in sync here
     * (rather than only in the UI's collapsed state) is what stops the two save errors
     * "Group meal X must be signed because it appears in a signed meal" and
     * "Group meal X cannot be signed directly".
     */
    private fun syncGroupClosedFlags(meals: List<MealUiState>): List<MealUiState> {
        val locked = computeDerivedLockedGroups(meals)
        return meals.map { m ->
            if (!GROUP_NAMES.contains(m.name)) m
            else {
                val shouldBeClosed = locked.contains(m.name)
                if (m.mealClosed == shouldBeClosed) m else m.copy(mealClosed = shouldBeClosed)
            }
        }
    }

    /** Groups that are referenced (by name) inside any signed regular meal -> locked. */
    private fun computeDerivedLockedGroups(meals: List<MealUiState>): Set<String> =
        meals.filter { GROUP_NAMES.contains(it.name) }
            .filter { group ->
                meals.any { m ->
                    !GROUP_NAMES.contains(m.name) && m.mealClosed &&
                            m.foods.any { it.name.equals(group.name, ignoreCase = true) }
                }
            }
            .map { it.name }
            .toSet()

    /** Validate the day before saving (matches the web). Returns an error message or null. */
    private fun validateDay(state: DayUiState): String? {
        val known = state.foodItems.map { it.name.trim().uppercase() }.toSet()
        for (meal in state.meals) {
            for (food in meal.foods) {
                val name = food.name.trim()
                val w = food.weight.trim()
                if (name.isEmpty()) {
                    if (w.isNotEmpty()) return "A row has a weight but no food name"
                    continue
                }
                val up = name.uppercase()
                val isGroupRef = GROUP_NAMES_UPPER.contains(up)
                if (!isGroupRef && !known.contains(up)) return "Food doesn't exist: $name"
                if (!isGroupRef && w.isEmpty()) return "Missing weight for: $name"
                if (w.isNotEmpty() && w.toFloatOrNull() == null) return "Invalid weight for: $name"
            }
        }
        return null
    }

    fun saveDay() {
        val state = _uiState.value
        validateDay(state)?.let { error ->
            _uiState.update { it.copy(snackMessage = error) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val weight = state.weight.toFloatOrNull() ?: 0f
            // Final guard: the server rejects a payload whose group meal_closed flags don't
            // match "referenced by a signed meal", so derive them once more on the way out.
            val meals = syncGroupClosedFlags(state.meals).map { meal ->
                Meal(
                    name = meal.name,
                    meal_closed = meal.mealClosed,
                    foods = meal.foods.filter { it.name.isNotBlank() }.map { f ->
                        Food(
                            name = f.name,
                            // A group-reference row genuinely has no weight — send null, not
                            // 0. Sending 0 would store 0.00 in a column that is nullable
                            // precisely to keep "no weight" distinct from "weighs nothing".
                            weight = if (isGroupReference(f.name)) null
                                     else f.weight.toFloatOrNull() ?: 0f,
                            protein = f.protein,
                            fat = f.fat,
                            calories = f.calories
                        )
                    }
                )
            }
            when (val result = repository.patchDay(state.date, weight, meals)) {
                is Result.Success -> _uiState.update {
                    it.copy(isSaving = false, snackMessage = "Saved successfully", dirty = false)
                }
                is Result.Error -> _uiState.update {
                    it.copy(isSaving = false, snackMessage = result.message)
                }
            }
        }
    }

    fun endDay() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            when (val result = repository.endDay(state.date)) {
                is Result.Success -> {
                    // Like the web: jump to the freshly-opened next day (shows Save/Revert).
                    _uiState.update { it.copy(isSaving = false, dirty = false, snackMessage = "Day ended") }
                    loadOpenDay()
                }
                is Result.Error -> _uiState.update {
                    it.copy(isSaving = false, snackMessage = result.message)
                }
            }
        }
    }

    fun revertDay() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            when (val result = repository.revertDay(state.date)) {
                is Result.Success -> {
                    _uiState.update { it.copy(isSaving = false, dirty = false, snackMessage = "Day reverted") }
                    loadOpenDay()
                }
                is Result.Error -> _uiState.update {
                    it.copy(isSaving = false, snackMessage = result.message)
                }
            }
        }
    }

    fun clearSnack() {
        _uiState.update { it.copy(snackMessage = null) }
    }

    /**
     * Suggestions for a food-name field. Matches the web's combo lists:
     *  - group meal (Fruits/Nuts/Vegetables): foods whose inner_type is that group
     *  - regular meal: every food name + the group names (group references)
     * Never filtered by `available` (that flag only gates the new-day random pool).
     */
    fun getSuggestionsForMeal(mealName: String, query: String): List<String> {
        val state = _uiState.value
        val q = query.trim()
        val names: List<String> = if (GROUP_NAMES.contains(mealName)) {
            state.foodItems
                .filter { it.inner_type.equals(mealName, ignoreCase = true) }
                .map { it.name }
        } else {
            state.foodItems.map { it.name } + GROUP_NAMES.toList()
        }
        return names
            .filter { it.contains(q, ignoreCase = true) }
            .distinct()
            .sortedBy { it.lowercase() }
            .take(40)
    }

    private fun isFoodAllowedInMeal(food: FoodItem, mealName: String, settings: Settings?): Boolean {
        if (GROUP_NAMES.contains(mealName)) {
            return food.inner_type.equals(mealName, ignoreCase = true)
        }
        return true
    }

    private fun computeNutrition(
        meals: List<MealUiState>,
        weight: String,
        settings: Settings?,
        currentDate: String,
        frozenTargets: Targets? = null,
        frozenSnapshot: SettingsSnapshot? = null,
        frozenNutrition: Nutrition? = null
    ): NutritionSummary {
        var totalProtein = 0f
        var totalFat = 0f
        var totalCalories = 0f

        // Web logic: skip group meals when summing daily totals; they are derived from regular meals
        for (meal in meals) {
            if (GROUP_NAMES.contains(meal.name)) continue
            for (food in meal.foods) {
                totalProtein += food.protein
                totalFat += food.fat
                totalCalories += food.calories
            }
        }

        val bodyWeight = weight.toFloatOrNull() ?: 0f

        // A closed day shows what the server froze at End Day, not a fresh computation: that
        // is the whole point of the snapshot. Totals come from `nutrition`, goals from
        // `targets`, and the deficit/surplus reading from the snapshot's calorie_type — so
        // later edits to the live settings can change neither the numbers nor their colours.
        if (frozenTargets != null) {
            val fatCals = frozenNutrition?.let { roundTo2(it.fat * 9f) } ?: roundTo2(totalFat * 9f)
            return NutritionSummary(
                protein = roundTo2(frozenNutrition?.protein ?: totalProtein),
                fatCalories = fatCals,
                calories = roundTo2(frozenNutrition?.calories ?: totalCalories),
                proteinNeeded = frozenTargets.protein,
                fatCaloriesNeeded = frozenTargets.fat_calories,
                caloriesNeeded = frozenTargets.calories,
                calorieType = frozenSnapshot?.daily?.calorie_type
                    ?: settings?.daily?.calorie_type ?: ""
            )
        }

        val fatCalories = roundTo2(totalFat * 9f)

        // Compute "Needed" whenever settings are present — even at weight 0 (web behaviour).
        if (settings == null) {
            return NutritionSummary(
                protein = roundTo2(totalProtein),
                fatCalories = fatCalories,
                calories = roundTo2(totalCalories),
                calorieType = ""
            )
        }

        // Open day: compute live, mirroring backend app/core/targets.py exactly.
        //   bmr  = 10*w + 6.25*h - 5*age + (gender=="M" ? 5 : -161)
        //   cals = round2(surplus ? tdee + daily.calories : tdee - daily.calories)
        //   fatCals = round2(ROUNDED cals * daily.fat)
        val proteinNeeded = roundTo2(settings.daily.protein * bodyWeight)
        val age = computeAgeYears(settings.person.birth_day, currentDate)
        // M/F only. The backend refuses to freeze any other value, so rather than invent a
        // number the app would never be able to save, show totals with no target at all.
        val sexTerm = when (settings.person.gender.trim().uppercase()) {
            "M" -> 5f
            "F" -> -161f
            else -> return NutritionSummary(
                protein = roundTo2(totalProtein),
                fatCalories = fatCalories,
                calories = roundTo2(totalCalories),
                calorieType = settings.daily.calorie_type
            )
        }
        val tdee =
            (10f * bodyWeight + 6.25f * settings.person.height - 5f * age + sexTerm) *
                    settings.daily.tdee_multiplier
        val isSurplus = settings.daily.calorie_type.trim().lowercase() == "surplus"
        val caloriesNeeded = roundTo2(
            if (isSurplus) tdee + settings.daily.calories else tdee - settings.daily.calories
        )
        val fatCaloriesNeeded = roundTo2(caloriesNeeded * settings.daily.fat)

        return NutritionSummary(
            protein = roundTo2(totalProtein),
            fatCalories = fatCalories,
            calories = roundTo2(totalCalories),
            proteinNeeded = proteinNeeded,
            fatCaloriesNeeded = fatCaloriesNeeded,
            caloriesNeeded = caloriesNeeded,
            calorieType = settings.daily.calorie_type
        )
    }

    /** Whole elapsed years, calendar-exact — matches compute_age_years() in the backend.
     *  The old elapsed-ms/365.2425 approximation drifted by a year near birthdays. */
    private fun computeAgeYears(birthDdMmYyyy: String, currentDdMmYyyy: String): Int {
        return try {
            val sdf = SimpleDateFormat("ddMMyyyy", Locale.US)
            val bCal = Calendar.getInstance().apply { time = sdf.parse(birthDdMmYyyy)!! }
            val cCal = Calendar.getInstance().apply { time = sdf.parse(currentDdMmYyyy)!! }
            val bM = bCal.get(Calendar.MONTH) + 1
            val bD = bCal.get(Calendar.DAY_OF_MONTH)
            val cM = cCal.get(Calendar.MONTH) + 1
            val cD = cCal.get(Calendar.DAY_OF_MONTH)
            val cY = cCal.get(Calendar.YEAR)
            val isLeap = (cY % 4 == 0 && (cY % 100 != 0 || cY % 400 == 0))
            // A 29-Feb birthday has no anniversary in a common year; treat it as passed on 1 Mar.
            val (efM, efD) = if (bM == 2 && bD == 29 && !isLeap) 3 to 1 else bM to bD
            val hadBirthday = (cM > efM) || (cM == efM && cD >= efD)
            cY - bCal.get(Calendar.YEAR) - (if (hadBirthday) 0 else 1)
        } catch (e: Exception) { 0 }
    }

    private fun roundTo2(value: Float): Float =
        (value * 100f).roundToInt() / 100f
}
