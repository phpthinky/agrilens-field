package com.agrilens.field.network

import com.agrilens.field.model.AssignmentListResponse
import com.agrilens.field.model.AssignmentResponse
import com.agrilens.field.model.BarangayListResponse
import com.agrilens.field.model.ColorimetricCaptureResponse
import com.agrilens.field.model.ColorimetricResetResponse
import com.agrilens.field.model.ColorimetricStatus
import com.agrilens.field.model.CompleteAssignmentRequest
import com.agrilens.field.model.CompleteAssignmentResponse
import com.agrilens.field.model.CreateAnalysisRequest
import com.agrilens.field.model.CreateAnalysisResponse
import com.agrilens.field.model.CreateColorimetricSampleRequest
import com.agrilens.field.model.DashboardResponse
import com.agrilens.field.model.FarmListResponse
import com.agrilens.field.model.FarmerListResponse
import com.agrilens.field.model.LoginRequest
import com.agrilens.field.model.LoginResponse
import com.agrilens.field.model.ResultLinkResponse
import com.agrilens.field.model.ScheduleAssignmentRequest
import com.agrilens.field.model.ScheduleAssignmentResponse
import com.agrilens.field.model.TaskListResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @POST("login")
    suspend fun login(@Body body: LoginRequest): Response<LoginResponse>

    @POST("logout")
    suspend fun logout(): Response<Unit>

    @GET("assignments")
    suspend fun listAssignments(): Response<AssignmentListResponse>

    @GET("assignments/{id}")
    suspend fun getAssignment(@Path("id") id: Long): Response<AssignmentResponse>

    @POST("assignments/{id}/start")
    suspend fun startAssignment(@Path("id") id: Long): Response<AssignmentResponse>

    @POST("assignments/{id}/complete")
    suspend fun completeAssignment(
        @Path("id") id: Long,
        @Body body: CompleteAssignmentRequest,
    ): Response<CompleteAssignmentResponse>

    @GET("dashboard")
    suspend fun getDashboard(): Response<DashboardResponse>

    @GET("barangays")
    suspend fun listBarangays(): Response<BarangayListResponse>

    @GET("barangays/{id}/farmers")
    suspend fun listFarmers(@Path("id") barangayId: Long): Response<FarmerListResponse>

    @GET("farmers/{id}/farms")
    suspend fun listFarms(@Path("id") farmerId: Long): Response<FarmListResponse>

    @POST("farms/{id}/analyses")
    suspend fun createAnalysis(
        @Path("id") farmId: Long,
        @Body body: CreateAnalysisRequest,
    ): Response<CreateAnalysisResponse>

    @POST("farms/{id}/assignments")
    suspend fun scheduleAssignment(
        @Path("id") farmId: Long,
        @Body body: ScheduleAssignmentRequest,
    ): Response<ScheduleAssignmentResponse>

    @POST("farms/{id}/colorimetric-samples")
    suspend fun createColorimetricSample(
        @Path("id") farmId: Long,
        @Body body: CreateColorimetricSampleRequest,
    ): Response<ColorimetricStatus>

    @GET("samples/{id}/colorimetric")
    suspend fun getColorimetricStatus(@Path("id") sampleId: Long): Response<ColorimetricStatus>

    @Multipart
    @POST("samples/{id}/colorimetric/captures")
    suspend fun uploadColorimetricCapture(
        @Path("id") sampleId: Long,
        @Part("parameter") parameter: RequestBody,
        @Part photo: MultipartBody.Part,
    ): Response<ColorimetricCaptureResponse>

    @DELETE("samples/{id}/colorimetric/{parameter}")
    suspend fun resetColorimetricParameter(
        @Path("id") sampleId: Long,
        @Path("parameter") parameter: String,
    ): Response<ColorimetricResetResponse>

    @POST("samples/{id}/result-link")
    suspend fun getResultLink(@Path("id") sampleId: Long): Response<ResultLinkResponse>

    @GET("tasks")
    suspend fun listTasks(@Query("status") status: String): Response<TaskListResponse>
}
