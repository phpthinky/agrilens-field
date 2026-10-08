package com.agrilens.field.network

import com.agrilens.field.model.AssignmentListResponse
import com.agrilens.field.model.AssignmentResponse
import com.agrilens.field.model.CompleteAssignmentRequest
import com.agrilens.field.model.CompleteAssignmentResponse
import com.agrilens.field.model.LoginRequest
import com.agrilens.field.model.LoginResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {

    @POST("login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("logout")
    suspend fun logout(): Response<Unit>

    @GET("assignments")
    suspend fun listAssignments(): Response<AssignmentListResponse>

    @GET("assignments/{id}")
    suspend fun getAssignment(@Path("id") id: Long): Response<AssignmentResponse>

    @POST("assignments/{id}/complete")
    suspend fun completeAssignment(
        @Path("id") id: Long,
        @Body body: CompleteAssignmentRequest,
    ): Response<CompleteAssignmentResponse>
}
