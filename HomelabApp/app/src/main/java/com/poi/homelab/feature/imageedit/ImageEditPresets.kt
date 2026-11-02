package com.poi.homelab.feature.imageedit

/** 修图页的效果预设：一句人话，转成给模型的中文指令。 */
data class ImageEditPreset(
    val id: String,
    val title: String,
    val hint: String,
    val prompt: String,
)

val IMAGE_EDIT_PRESETS = listOf(
    ImageEditPreset(
        id = "background",
        title = "换背景",
        hint = "保留人物与姿势",
        prompt = "把背景换成傍晚的海边，保留人物的五官、发型、衣服和姿势完全不变，光线自然过渡",
    ),
    ImageEditPreset(
        id = "remove",
        title = "去路人",
        hint = "背景补全",
        prompt = "去掉画面里的路人和杂物，用周围背景自然补全，人物主体保持原样",
    ),
    ImageEditPreset(
        id = "snow",
        title = "换季节",
        hint = "改成雪景",
        prompt = "把场景换成冬天的雪景，加薄雪与冷色调，保留人物形象与构图不变",
    ),
    ImageEditPreset(
        id = "style",
        title = "换风格",
        hint = "胶片感",
        prompt = "把照片改成胶片质感的日系写真风格，柔和颗粒、低饱和、暖调，主体不变",
    ),
    ImageEditPreset(
        id = "product",
        title = "商品图",
        hint = "白底干净",
        prompt = "把主体抠到纯白背景上，做成电商商品图，光线均匀、边缘干净、保留材质细节",
    ),
    ImageEditPreset(
        id = "retouch",
        title = "轻修人像",
        hint = "自然不过度",
        prompt = "轻微磨皮与均匀肤色，提亮眼神，保持皮肤纹理和真实五官，不要美颜过度",
    ),
)

fun presetById(id: String): ImageEditPreset? = IMAGE_EDIT_PRESETS.firstOrNull { it.id == id }
