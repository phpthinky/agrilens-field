package com.agrilens.field.model

data class LoginRequest(
    val username: String,
    val password: String,
)

data class LoginResponse(
    val token: String,
    val user: UserInfo,
)

data class UserInfo(
    val id: Long,
    val username: String,
    val user_type: String,
)

data class AssignmentListResponse(
    val assignments: List<Assignment>,
)

data class AssignmentResponse(
    val assignment: Assignment,
)

data class Assignment(
    val id: Long,
    val scheduled_date: String,
    val status: String,
    val notes: String?,
    val farm: FarmInfo,
)

data class FarmInfo(
    val id: Long,
    val name: String,
    val address: String?,
    val farmer_name: String,
    val barangay: String,
    val latitude: String?,
    val longitude: String?,
    val farm_type: String? = null,
    val area_hectares: String? = null,
)

data class CompleteAssignmentRequest(
    val probe_id: String,
    val probe_model: String?,
    val ph_level: Double?,
    val nitrogen_level: Double,
    val phosphorus_level: Double,
    val potassium_level: Double,
    val sample_name: String? = null,
)

data class CompleteAssignmentResponse(
    val message: String,
    val sample_id: Long,
    val fertility_score: Int,
    val recommended_crop: String?,
)

/** A single decoded channel from the probe (e.g. pH, N, P, K, moisture...). */
data class ProbeReading(
    val ph: Double? = null,
    val nitrogen: Double? = null,
    val phosphorus: Double? = null,
    val potassium: Double? = null,
    val moisture: Double? = null,
    val temperature: Double? = null,
    val conductivity: Int? = null,
) {
    /** True once the four parameters Agrilens actually uses have been decoded. */
    fun hasCoreNpk(): Boolean = nitrogen != null && phosphorus != null && potassium != null
}
