package com.plan.data.api

import retrofit2.http.*

interface PlanApi {

    @GET("days/open")
    suspend fun getOpenDay(): DayResponse

    @GET("days/{ddmmyyyy}")
    suspend fun getDay(@Path("ddmmyyyy") date: String): DayResponse

    @PATCH("days/{ddmmyyyy}")
    suspend fun patchDay(
        @Path("ddmmyyyy") date: String,
        @Body body: PatchDayRequest
    ): BaseResponse

    @POST("days/{ddmmyyyy}/end")
    suspend fun endDay(@Path("ddmmyyyy") date: String): BaseResponse

    @POST("days/{ddmmyyyy}/revert")
    suspend fun revertDay(@Path("ddmmyyyy") date: String): BaseResponse

    @GET("foods")
    suspend fun getFoods(): FoodsResponse

    @PUT("foods")
    suspend fun saveFoods(@Body body: SaveFoodsRequest): BaseResponse

    @GET("settings")
    suspend fun getSettings(): SettingsResponse

    @GET("history")
    suspend fun getHistory(): HistoryResponse

    @GET("weights")
    suspend fun getWeights(): WeightsResponse
}
