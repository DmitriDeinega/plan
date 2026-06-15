package com.plan.data.repository

import com.plan.data.api.*
import javax.inject.Inject
import javax.inject.Singleton

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String) : Result<Nothing>()
}

@Singleton
class PlanRepository @Inject constructor(
    private val api: PlanApi
) {
    suspend fun getOpenDay(): Result<Day> = runCatching {
        val resp = api.getOpenDay()
        if (resp.status == "SUCCESS" && resp.day != null) {
            Result.Success(resp.day)
        } else {
            Result.Error(resp.errorMessage.ifBlank { "Failed to load day" })
        }
    }.getOrElse { Result.Error(it.message ?: "Network error") }

    suspend fun getDay(date: String): Result<Day> = runCatching {
        val resp = api.getDay(date)
        if (resp.status == "SUCCESS" && resp.day != null) {
            Result.Success(resp.day)
        } else {
            Result.Error(resp.errorMessage.ifBlank { "Failed to load day" })
        }
    }.getOrElse { Result.Error(it.message ?: "Network error") }

    suspend fun patchDay(date: String, weight: Float, meals: List<Meal>): Result<Unit> = runCatching {
        val resp = api.patchDay(date, PatchDayRequest(weight, meals))
        if (resp.status == "SUCCESS") Result.Success(Unit)
        else Result.Error(resp.errorMessage.ifBlank { "Failed to save day" })
    }.getOrElse { Result.Error(it.message ?: "Network error") }

    suspend fun endDay(date: String): Result<Unit> = runCatching {
        val resp = api.endDay(date)
        if (resp.status == "SUCCESS") Result.Success(Unit)
        else Result.Error(resp.errorMessage.ifBlank { "Failed to end day" })
    }.getOrElse { Result.Error(it.message ?: "Network error") }

    suspend fun revertDay(date: String): Result<Unit> = runCatching {
        val resp = api.revertDay(date)
        if (resp.status == "SUCCESS") Result.Success(Unit)
        else Result.Error(resp.errorMessage.ifBlank { "Failed to revert day" })
    }.getOrElse { Result.Error(it.message ?: "Network error") }

    suspend fun getFoods(): Result<List<FoodItem>> = runCatching {
        val resp = api.getFoods()
        if (resp.status == "SUCCESS" && resp.foods != null) {
            Result.Success(resp.foods)
        } else {
            Result.Error(resp.errorMessage.ifBlank { "Failed to load foods" })
        }
    }.getOrElse { Result.Error(it.message ?: "Network error") }

    suspend fun saveFoods(foods: List<FoodItem>): Result<Unit> = runCatching {
        val resp = api.saveFoods(SaveFoodsRequest(foods))
        if (resp.status == "SUCCESS") Result.Success(Unit)
        else Result.Error(resp.errorMessage.ifBlank { "Failed to save foods" })
    }.getOrElse { Result.Error(it.message ?: "Network error") }

    suspend fun getSettings(): Result<Settings> = runCatching {
        val resp = api.getSettings()
        if (resp.status == "SUCCESS" && resp.settings != null) {
            Result.Success(resp.settings)
        } else {
            Result.Error(resp.errorMessage.ifBlank { "Failed to load settings" })
        }
    }.getOrElse { Result.Error(it.message ?: "Network error") }

    suspend fun getHistory(): Result<List<Day>> = runCatching {
        val resp = api.getHistory()
        if (resp.status == "SUCCESS" && resp.days != null) {
            Result.Success(resp.days)
        } else {
            Result.Error(resp.errorMessage.ifBlank { "Failed to load history" })
        }
    }.getOrElse { Result.Error(it.message ?: "Network error") }

    suspend fun getWeights(): Result<List<Day>> = runCatching {
        val resp = api.getWeights()
        if (resp.status == "SUCCESS" && resp.days != null) {
            Result.Success(resp.days)
        } else {
            Result.Error(resp.errorMessage.ifBlank { "Failed to load weights" })
        }
    }.getOrElse { Result.Error(it.message ?: "Network error") }
}
