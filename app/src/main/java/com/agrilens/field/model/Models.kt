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

/**
 * A farm the admin (or the technician themself, via Schedule Soil Test) has
 * scheduled this technician to test, by date. [status] is the *display*
 * status from the backend — pending | in_progress | completed | cancelled |
 * overdue — overdue is derived server-side, not a distinct stored value.
 */
data class Assignment(
    val id: Long,
    val scheduled_date: String,
    val status: String,
    val can_start: Boolean,
    val lock_message: String?,
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

data class DashboardResponse(
    val assigned_barangays_count: Int,
    val registered_farmers_count: Int,
    val pending_tasks_count: Int,
    val today_scheduled_count: Int,
    val overdue_count: Int,
    val completed_analyses_count: Int,
    val recent_activity: List<RecentActivity>,
)

data class RecentActivity(
    val id: Long,
    val sample_name: String?,
    val farmer_name: String?,
    val farm_name: String?,
    val barangay: String?,
    val fertility_score: Int?,
    val date: String?,
)

data class BarangayListResponse(val barangays: List<BarangaySummary>)

data class BarangaySummary(
    val id: Long,
    val name: String,
    val farmers_count: Int,
    val farms_count: Int,
    val pending_tasks_count: Int,
    val completed_count: Int,
)

data class FarmerListResponse(val farmers: List<FarmerSummary>)

data class FarmerSummary(
    val id: Long,
    val full_name: String,
    val farms_count: Int,
    val pending_tasks_count: Int,
    val completed_count: Int,
)

data class FarmListResponse(val farms: List<FarmSummary>)

data class FarmSummary(
    val id: Long,
    val name: String,
    val address: String?,
    val barangay: String,
    val farm_type: String?,
    val area_hectares: String?,
    val latitude: String?,
    val longitude: String?,
)

data class CreateAnalysisRequest(
    val analysis_type: String,
    val sample_name: String?,
    val probe_id: String?,
    val probe_model: String?,
    val ph_level: Double?,
    val nitrogen_level: Double,
    val phosphorus_level: Double,
    val potassium_level: Double,
)

data class CreateAnalysisResponse(
    val message: String,
    val sample_id: Long,
    val fertility_score: Int,
    val recommended_crop: String?,
)

data class ScheduleAssignmentRequest(
    val scheduled_date: String,
    val notes: String? = null,
)

data class ScheduleAssignmentResponse(
    val message: String,
    val assignment_id: Long,
    val scheduled_date: String,
)

data class TaskListResponse(val tasks: List<TaskItem>)

data class TaskItem(
    val id: Long,
    val status: String,
    val assignment_id: Long?,
    val sample_id: Long?,
    val scheduled_date: String?,
    val date: String?,
    val farmer_name: String?,
    val farm_name: String?,
    val barangay: String?,
    val notes: String?,
    val fertility_score: Int?,
)

data class CreateColorimetricSampleRequest(
    val sample_name: String?,
)

/**
 * Server-side progress of a colorimetric sample. The app only captures and
 * uploads webcam photos — color picking, pH/ppm conversion, averaging and
 * the pH Step 1 → Step 2 indicator choice all happen on the server.
 */
data class ColorimetricStatus(
    val sample: ColorimetricSample,
    val parameters: List<ColorimetricParameter>,
)

data class ColorimetricSample(
    val id: Long,
    val sample_name: String?,
    val farm_name: String?,
    val farmer_name: String?,
    val analyzed: Boolean,
    val ready_to_analyze: Boolean,
)

/** One of ph | nitrogen | phosphorus | potassium. */
data class ColorimetricParameter(
    val key: String,
    val label: String,
    val reagent: String?,
    val captures: Int,
    val required: Int,
    val complete: Boolean,
    val can_capture: Boolean,
    val can_reset: Boolean,
    val reaction_seconds: Int,
    val average_hex: String?,
    val message: String?,
)

data class ColorimetricCaptureResponse(
    val message: String,
    val capture: ColorimetricCapture,
    val status: ColorimetricStatus,
)

data class ColorimetricCapture(
    val parameter: String,
    val color_hex: String,
    val computed_value: Double?,
)

data class ColorimetricResetResponse(
    val message: String,
    val status: ColorimetricStatus,
)

data class ResultLinkResponse(val url: String)

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
