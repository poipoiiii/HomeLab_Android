package com.poi.homelab.feature.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/* ------------------------------- lab-tools ------------------------------- */

@Serializable
data class LabSummary(
    val total: Int = 0,
    val running: Int = 0,
    val stopped: Int = 0,
    val unhealthy: Int = 0,
    val attention: Int = 0,
    @SerialName("checked_at") val checkedAt: String = "",
)

@Serializable
data class DiskSection(
    val count: Int = 0,
    val size: String = "",
    @SerialName("unused_size") val unusedSize: String = "",
)

@Serializable
data class DiskInfo(
    val images: DiskSection = DiskSection(),
    val containers: DiskSection = DiskSection(),
    val volumes: DiskSection = DiskSection(),
    @SerialName("build_cache") val buildCache: DiskSection = DiskSection(),
    @SerialName("total_reclaimable") val totalReclaimable: String = "",
)

@Serializable
data class ContainerInfo(
    val name: String = "",
    val state: String = "",
    val health: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val restarts: Int = 0,
    @SerialName("started_at") val startedAt: String? = null,
    val ports: List<String> = emptyList(),
    @SerialName("error_count") val errorCount: Int = 0,
    val errors: List<String> = emptyList(),
    val image: String? = null,
    @SerialName("compose_project") val composeProject: String? = null,
)

@Serializable
data class HealthSnapshot(
    val summary: LabSummary = LabSummary(),
    val disk: DiskInfo = DiskInfo(),
    val attention: List<ContainerInfo> = emptyList(),
    val containers: List<ContainerInfo> = emptyList(),
)
