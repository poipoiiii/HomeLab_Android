# 视频生成（AI 导演）

用户说一句话，AI 先追问或直接给出分镜方案；方案以卡片形式确认后，才提交给家里的 `motion-studio` 渲染。服务端只做渲染，不保存任何具体视频的配置。

## 流程

1. 视频页默认是「AI 导演」聊天：用户输入一句需求。
2. `MotionDirector` 调当前选中的 AI 服务商，输出严格 JSON：`status=ask`（追问）或 `status=plan`（完整方案）。
3. 追问最多 3 个问题、连续不超过 2 轮；用户说「你定」就直接出方案。
4. 方案卡展示片名、主题、镜头数、预计时长和每个镜头摘要。用户可继续说「改短一点/换主题」，AI 会基于上一版方案调整。
5. 用户点「确认生成」才真正提交；模型自己不能触发渲染。
6. 提交后自动切到「任务」，显示进度、播放、保存、删除。

## 小砂糖集成

- 小砂糖也能直接做视频，不需要先打开视频页：
  - `plan_video`：把一句话交给 `MotionDirector`，返回追问（`status=ask`）或方案摘要（`status=plan`）；方案暂存在 `AppContainer.motionPlans`。
  - `render_video`：只在用户明确确认方案后提交；模型不能自己触发渲染。
  - `list_video_jobs`：查最近任务状态与进度。
  - `save_video_result`：把成片保存到相册，并把服务端封面挂到对话。
  - `cancel_video_job`：按用户明确要求取消任务。
- `MotionPlanStore` 跨轮次保存导演对话、上一版方案和待确认方案；它和视频页的 `MotionViewModel` 状态分开，互不覆盖。清空对话时一起 reset。
- 视频服务地址/Token 仍走视频页的 `MotionPrefs`；默认值由 `HomelabApp/local.properties` 的 `motion.url` / `motion.token` 在构建期注入，装好即可用，只有换服务时才需要改。没配置时工具返回 `not_configured`，引导去 视频页 → 设置。
- 对话里只给方案摘要，不贴 JSON、不内嵌播放器；成片在视频页播放，或保存后用系统相册打开。

## 提示词

- 提示词在 [MotionDirectorPrompt.kt](../../../HomelabApp/app/src/main/java/com/poi/homelab/feature/motion/MotionDirectorPrompt.kt)，运行时拼上「上一版方案 + 对话历史 + 新消息」。
- 规则参考 `kiro-motion-studio` 的 SKILL.md：开工前确认、每个镜头多层动态、切点对齐 120 BPM、转场不重复、中文文案短句化。
- 输出协议是硬约束：只允许一个 JSON 对象；`plan` 直接对应服务端的 `MotionRequest`（`template=scenes`）。
- 方案约束：30~90 秒、6~16 个镜头、至少 3 种场景类型、cover 开场 / end 收尾、时长取 0.5 的倍数。
- 如果模型输出带 Markdown 或解释，`MotionDirector` 会做一次「只输出 JSON」的修复重试；仍失败则回到追问。

## 方案与渲染

- `MotionScene` 支持 `cover` / `portrait` / `section` / `list` / `stat` / `quote` / `end`，字段见 `homelab/motion-studio/API.md`。
- `portrait` 的 `variant=figure` 是通用概念人像；七宗罪才用 `pride` 等专属符号。
- 渲染参数默认 `1080p / 30fps / audio=true`；服务端合成 120 BPM 音轨并卡点。
- 手动表单保留在「手动」页，适合已经写好条目、只想调样式的情况。

## AI 服务商

- 复用「设置 → 小砂糖 → AI 配置」里选中的服务商与 Key，不额外配置。
- 没配服务商时，导演页只显示提示，不发起请求；没配视频服务地址时，确认按钮会打开视频服务设置。

## 排错

| 现象 | 处理 |
| --- | --- |
| AI 只追问不出方案 | 说「你定」或补齐主题/时长；最多两轮后会强制出方案 |
| 方案卡没出现 | 模型返回不是合法 JSON；已自动修复一次，再失败会提示重说 |
| 401 | 视频服务 Token 与 `.env` 的 `MOTION_TOKEN` 不一致 |
| 中文变方块 | 服务端 `/healthz` 的 `cjk_fonts=false`，重 build 镜像 |
| 排队不动 | 服务端 `RENDER_CONCURRENCY=1`，看 `docker compose logs -f` |
