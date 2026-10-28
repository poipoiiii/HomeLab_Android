package com.poi.homelab.feature.lab

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

class LabRepository(
    private val mcp: McpClient,
    private val json: Json,
) {
    suspend fun health(baseUrl: String, logLines: Int = 40): HealthSnapshot {
        val text = mcp.callTool(
            baseUrl,
            "health_snapshot",
            buildJsonObject { put("log_lines", logLines) },
        )
        return json.decodeFromString<HealthSnapshot>(text)
    }

    suspend fun listServices(baseUrl: String): List<ContainerInfo> {
        val text = mcp.callTool(baseUrl, "list_services")
        return json.decodeFromString<List<ContainerInfo>>(text)
    }

    suspend fun diskUsage(baseUrl: String): DiskInfo {
        val text = mcp.callTool(baseUrl, "disk_usage")
        return json.decodeFromString<DiskInfo>(text)
    }

    suspend fun serviceLogs(baseUrl: String, name: String, lines: Int = 120): String =
        mcp.callTool(
            baseUrl,
            "service_logs",
            buildJsonObject {
                put("name", name)
                put("lines", lines)
            },
        )

    suspend fun startService(baseUrl: String, name: String): JsonObject =
        callForObject(baseUrl, "start_service", buildJsonObject { put("name", name) })

    suspend fun stopService(baseUrl: String, name: String, confirmToken: String? = null): JsonObject =
        callForObject(
            baseUrl,
            "stop_service",
            buildJsonObject {
                put("name", name)
                if (!confirmToken.isNullOrBlank()) put("confirm_token", confirmToken)
            },
        )

    suspend fun restartService(baseUrl: String, name: String, confirmToken: String? = null): JsonObject =
        callForObject(
            baseUrl,
            "restart_service",
            buildJsonObject {
                put("name", name)
                if (!confirmToken.isNullOrBlank()) put("confirm_token", confirmToken)
            },
        )

    suspend fun download(
        baseUrl: String,
        url: String,
        query: String,
        target: String,
        source: String,
        series: String,
        limit: Int,
        items: String,
    ): JsonObject = callForObject(
        baseUrl,
        "download_media",
        buildJsonObject {
            put("url", url)
            put("query", query)
            put("target", target)
            put("source", source)
            put("series", series)
            put("limit", limit)
            put("items", items)
        },
    )

    private suspend fun callForObject(
        baseUrl: String,
        tool: String,
        arguments: JsonObject,
    ): JsonObject {
        val text = mcp.callTool(baseUrl, tool, arguments)
        return runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { buildJsonObject { put("text", text) } }
    }
}
