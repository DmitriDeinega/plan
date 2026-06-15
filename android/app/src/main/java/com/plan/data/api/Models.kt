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

data class Day(
    val date: String,
    val weight: Float,
    val day_closed: Boolean,
    val meals: List<Meal>,
    val nutrition: Nutrition?
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
    val timezone_name: String
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
