package com.poi.homelab.feature.imageedit

/**
 * 修图场景库：一句话说明「什么时候用」，加一条带 {change} 占位的提示词模板。
 *
 * - 修图页：点一下把 [prompt] 填进输入框，用户再自己改。
 * - 小砂糖：先按意图选 [id]，只把具体要求传给 [composeImageEditPrompt]，
 *   保留项、材质光影约束由模板补齐。
 */
data class ImageEditPreset(
    val id: String,
    val title: String,
    val hint: String,
    val prompt: String,
    val whenToUse: String,
    val template: String,
)

val IMAGE_EDIT_PRESETS = listOf(
    ImageEditPreset(
        id = "background",
        title = "换背景",
        hint = "人物不动，只换环境",
        prompt = "把背景换成傍晚的海边，保留人物的五官、发型、衣服和姿势完全不变，光线自然过渡",
        whenToUse = "换背景、换场景、换地点、换天气",
        template = "把背景替换为{change}。人物的脸、发型、服装、姿势、体型和构图完全不变，只重绘人物以外的区域；" +
            "新背景的光线方向、色温要与人物原有的受光和阴影匹配，边缘过渡自然，不要出现抠图白边。",
    ),
    ImageEditPreset(
        id = "remove",
        title = "去杂物",
        hint = "路人、杂物、水印",
        prompt = "去掉画面里的路人和杂物，用周围背景自然补全，人物主体保持原样",
        whenToUse = "去路人、去杂物、去水印、去文字、去电线",
        template = "去掉画面里的{change}，用周围背景和纹理自然补全，不改变人物主体与构图，补全区域的光影和原画面一致。",
    ),
    ImageEditPreset(
        id = "outfit",
        title = "换装",
        hint = "衣服、颜色、配饰",
        prompt = "把人物身上的衣服换成黑色西装，保留脸部、发型和姿势不变",
        whenToUse = "换衣服、换颜色、换款式、加配饰",
        template = "把人物身上的衣物换成{change}。保持人物的脸部、发型、姿势、体型、背景与光线不变；" +
            "新衣服的材质、褶皱、垂坠感要符合原图光照，边缘贴合身体轮廓。",
    ),
    ImageEditPreset(
        id = "portrait",
        title = "轻修人像",
        hint = "自然不过度",
        prompt = "轻微磨皮与均匀肤色，提亮眼神，保持皮肤纹理和真实五官，不要美颜过度",
        whenToUse = "轻修人像、磨皮、去痘、提亮、修气色",
        template = "对人物做{change}：轻微磨皮、均匀肤色、提亮眼神。保留皮肤纹理、痣和真实五官与年龄感，" +
            "不要改变脸型，不要美颜过度。",
    ),
    ImageEditPreset(
        id = "body",
        title = "身材线条",
        hint = "腹肌、胸肌、体型",
        prompt = "露出腹部并加上清晰的腹肌线条，保留脸部、姿势和衣服不变",
        whenToUse = "身材、腹肌、胸肌、肌肉线条、胖瘦调整",
        template = "本次要求：{change}。只重绘躯干区域，按人体解剖结构绘制肌肉线条与自然阴影，" +
            "皮肤质感与整体光线保持一致；脸部、发型、姿势、服装、背景和构图保持不变，不要改变身材比例。",
    ),
    ImageEditPreset(
        id = "style",
        title = "换画风",
        hint = "胶片、油画、卡通",
        prompt = "把照片改成胶片质感的日系写真风格，柔和颗粒、低饱和、暖调，主体不变",
        whenToUse = "换风格、胶片、日系、油画、水彩、卡通、赛博朋克",
        template = "把整张照片改成{change}。保持人物身份、五官、发型、姿势、服装和构图不变，" +
            "只改变画风、色调、颗粒与笔触，细节和边缘要完整。",
    ),
    ImageEditPreset(
        id = "light",
        title = "换光线",
        hint = "色调、时间、季节",
        prompt = "把光线改成黄昏的金色逆光，保留人物和背景物体的位置不变",
        whenToUse = "换光线、换时间段、换季节、调色、冷暖调",
        template = "把画面的光线和氛围改成{change}。保留人物、服装以及背景物体的位置和形状不变，" +
            "只调整色温、明暗、阴影方向和整体色调。",
    ),
    ImageEditPreset(
        id = "product",
        title = "商品图",
        hint = "白底、棚拍光",
        prompt = "把主体抠到纯白背景上，做成电商商品图，光线均匀、边缘干净、保留材质细节",
        whenToUse = "商品图、白底图、抠图、主图",
        template = "把{change}处理成电商商品图：主体居中、纯白背景、棚拍柔光、无杂乱阴影，" +
            "保留材质纹理、logo 和文字清晰可读，边缘干净。",
    ),
    ImageEditPreset(
        id = "restore",
        title = "老照片修复",
        hint = "去划痕、去噪、上色",
        prompt = "修复老照片的划痕和噪点，恢复清晰度与自然肤色，保留年代质感",
        whenToUse = "老照片修复、去划痕、去噪、模糊修复、黑白上色",
        template = "修复这张老照片：{change}。去除噪点、划痕和折痕，恢复清晰度与自然肤色，" +
            "保留原本的五官特征和年代质感，不要过度磨平。",
    ),
    ImageEditPreset(
        id = "compose",
        title = "多图合成",
        hint = "图1 主体，图2/3 参考",
        prompt = "图1 是人物，图2 是场景；把人物放进图2 的场景里，保留人物脸部和姿势不变",
        whenToUse = "多图合成、把人物放进另一张图、商品加场景、双图参考",
        template = "多图合成：{change}。图1 是主体，其余图是参考；保持图1 人物的脸、发型和姿态不变，" +
            "把参考图中的元素按真实透视、光影和比例合成到同一画面，边缘与阴影自然。",
    ),
)

fun presetById(id: String): ImageEditPreset? = IMAGE_EDIT_PRESETS.firstOrNull { it.id == id }

/**
 * 把「具体要求」套进场景模板。场景不存在、模板缺占位符或要求为空时原样返回。
 * 要求本身要短（20 字内），保留项与光影约束由模板补齐，不要重复写。
 */
fun composeImageEditPrompt(scenarioId: String?, instruction: String): String {
    val text = instruction.trim()
    if (text.isEmpty()) return text
    val preset = presetById(scenarioId.orEmpty()) ?: return text
    if (!preset.template.contains("{change}")) return text
    return preset.template.replace("{change}", text).trim()
}
