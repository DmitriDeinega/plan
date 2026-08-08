package com.plan.data.api

data class BaseResponse(
    val status: String,
    val errorMessage: String = ""
)

data class Food(
    val name: String,
    val weight: Float?,
    val protein: Float,
    val fat: Float,
    val calories: Float
)

data class Meal(
    val name: String,
    val meal_closed: Boolean,
    val foods: List<Food>
)

data class Nutrition(
    val protein: Float,
    val fat: Float,
    val calories: Float
)

/** The "Needed" figures as frozen when the day was closed. Null on an open day. */
data class Targets(
    val protein: Float = 0f,
    val fat_calories: Float = 0f,
    val calories: Float = 0f
)

data class SnapshotDaily(
    val protein: Float = 0f,
    val fat: Float = 0f,
    val calories: Float = 0f,
    val calorie_type: String = "",
    val tdee_multiplier: Float = 0f
)

data class SnapshotPerson(
    val height: Float = 0f,
    val gender: String = "",
    val birth_day: String = ""
)

/** Settings as they were when the day was closed; its calorie_type keeps a closed day's
 *  deficit/surplus interpretation stable after the live setting changes. */
data class SettingsSnapshot(
    val daily: SnapshotDaily = SnapshotDaily(),
    val person: SnapshotPerson = SnapshotPerson()
)

data class Day(
    val date: String,
    val weight: Float,
    val day_closed: Boolean,
    val meals: List<Meal>,
    val nutrition: Nutrition?,
    // Present only on closed days. Older backends omit them entirely, hence nullable.
    val targets: Targets? = null,
    val settings_snapshot: SettingsSnapshot? = null
)

data class FoodItem(
    val name: String,
    val type: String,
    val inner_type: String,
    val protein: Float,
    val fat: Float,
    val calories: Float,
    val available: String
)

data class Group(
    val name: String,
    val new_day_amount: Int
)

data class Daily(
    val protein: Float,
    val fat: Float,
    val calories: Float,
    val calorie_type: String,
    val tdee_multiplier: Float
)

data class Person(
    val height: Int,
    val birth_day: String,
    val gender: String
)

data class Settings(
    val groups: List<Group>,
    val daily: Daily,
    val person: Person,
    val start_date: String,
    val timezone_name: String,
    // Server-computed dates (ddMMyyyy) in the configured timezone; used for End/Revert logic
    // instead of the device clock. Nullable for safety if an older backend omits them.
    val today: String? = null,
    val tomorrow: String? = null
)

// Request bodies
data class PatchDayRequest(
    val weight: Float,
    val meals: List<Meal>
)

data class SaveFoodsRequest(
    val foods: List<FoodItem>
)

// Responses
data class DayResponse(
    val status: String,
    val errorMessage: String = "",
    val day: Day?
)

data class FoodsResponse(
    val status: String,
    val errorMessage: String = "",
    val foods: List<FoodItem>?
)

data class SettingsResponse(
    val status: String,
    val errorMessage: String = "",
    val settings: Settings?
)

data class HistoryResponse(
    val status: String,
    val errorMessage: String = "",
    val days: List<Day>?
)

data class WeightsResponse(
    val status: String,
    val errorMessage: String = "",
    val days: List<Day>?
)
