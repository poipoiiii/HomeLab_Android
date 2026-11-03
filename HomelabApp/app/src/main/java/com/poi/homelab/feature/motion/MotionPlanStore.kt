package com.poi.homelab.feature.motion

/**
 * 小砂糖跨轮次暂存 AI 导演的对话和待确认方案。
 *
 * 每次对话都会新建 AgentTools，所以不能把状态放在工具实例里；
 * 这里和视频页的 MotionViewModel 状态完全分开，避免两边互相覆盖。
 */
class MotionPlanStore {

    private val turns = mutableListOf<DirectorTurn>()

    @Volatile
    var pending: MotionRequest? = null

    @Volatile
    var last: MotionRequest? = null

    fun history(): List<DirectorTurn> = synchronized(turns) { turns.toList() }

    fun append(role: String, text: String) {
        if (text.isBlank()) return
        synchronized(turns) { turns += DirectorTurn(role, text) }
    }

    fun reset() {
        synchronized(turns) { turns.clear() }
        pending = null
        last = null
    }
}
