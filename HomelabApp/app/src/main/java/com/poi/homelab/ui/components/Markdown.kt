package com.poi.homelab.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染：标题、段落、列表、引用、代码块、分割线，
 * 以及行内的粗体、斜体、删除线、行内代码和链接文本。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val accent = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.background

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inlineMarkdown(block.text, accent, codeBackground),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    },
                    color = color,
                )

                is MdBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text, accent, codeBackground),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )

                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = if (block.ordered) "${index + 1}." else "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = accent,
                                modifier = Modifier.width(22.dp),
                            )
                            Text(
                                text = inlineMarkdown(item, accent, codeBackground),
                                style = MaterialTheme.typography.bodyMedium,
                                color = color,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(50),
                            ),
                    )
                    Text(
                        text = inlineMarkdown(block.text, accent, codeBackground),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }

                is MdBlock.Code -> CodeBlock(block.code, color, codeBackground)

                is MdBlock.Diagram -> {
                    val diagram = remember(block.code) { parseFlowDiagram(block.code) }
                    if (diagram == null) {
                        CodeBlock(block.code, color, codeBackground)
                    } else {
                        FlowChart(diagram, accent, color)
                    }
                }

                MdBlock.Rule -> HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val ordered: Boolean, val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val code: String) : MdBlock
    data class Diagram(val code: String) : MdBlock
    data object Rule : MdBlock
}

@Composable
private fun CodeBlock(code: String, color: Color, codeBackground: Color) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(codeBackground)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(10.dp),
            )
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        Text(
            text = code.trimEnd('\n'),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = color,
            softWrap = false,
        )
    }
}

private val headingRegex = Regex("^(#{1,6})\\s+(.+)$")
private val bulletRegex = Regex("^\\s*[-*+]\\s+(.+)$")
private val orderedRegex = Regex("^\\s*\\d+[.)]\\s+(.+)$")

private fun parseMarkdown(markdown: String): List<MdBlock> {
    val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString())
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trim()
        when {
            line.startsWith("```") -> {
                flushParagraph()
                val language = line.removePrefix("```").trim().lowercase()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                if (i < lines.size) i++
                val content = code.toString()
                blocks += if (
                    language.startsWith("mermaid") ||
                    language.startsWith("flowchart") ||
                    language.startsWith("graph")
                ) {
                    MdBlock.Diagram(content)
                } else {
                    MdBlock.Code(content)
                }
                continue
            }

            line.isEmpty() -> flushParagraph()

            line == "---" || line == "***" || line == "___" -> {
                flushParagraph()
                blocks += MdBlock.Rule
            }

            headingRegex.matches(line) -> {
                val match = headingRegex.matchEntire(line)!!
                flushParagraph()
                blocks += MdBlock.Heading(
                    level = match.groupValues[1].length,
                    text = match.groupValues[2].trim(),
                )
            }

            bulletRegex.matches(raw) || orderedRegex.matches(raw) -> {
                flushParagraph()
                val ordered = orderedRegex.matches(raw)
                val regex = if (ordered) orderedRegex else bulletRegex
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val match = regex.matchEntire(lines[i]) ?: break
                    items += match.groupValues[1].trim()
                    i++
                }
                blocks += MdBlock.Bullets(ordered = ordered, items = items)
                continue
            }

            line.startsWith(">") -> {
                flushParagraph()
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote += lines[i].trim().removePrefix(">").trimStart()
                    i++
                }
                blocks += MdBlock.Quote(quote.joinToString("\n"))
                continue
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(raw.trimEnd())
            }
        }
        i++
    }
    flushParagraph()
    return blocks
}

private fun inlineMarkdown(text: String, accent: Color, codeBackground: Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackground,
                                color = accent,
                            ),
                        ) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append('`')
                        i++
                    }
                }

                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(inlineMarkdown(text.substring(i + 2, end), accent, codeBackground))
                        }
                        i = end + 2
                    } else {
                        append("**")
                        i += 2
                    }
                }

                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 2) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append("~~")
                        i += 2
                    }
                }

                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(inlineMarkdown(text.substring(i + 1, end), accent, codeBackground))
                        }
                        i = end + 1
                    } else {
                        append('*')
                        i++
                    }
                }

                text[i] == '[' -> {
                    val labelEnd = text.indexOf(']', i + 1)
                    val hasTarget = labelEnd > i &&
                        labelEnd + 1 < text.length &&
                        text[labelEnd + 1] == '('
                    val urlEnd = if (hasTarget) text.indexOf(')', labelEnd + 2) else -1
                    if (hasTarget && urlEnd > labelEnd) {
                        withStyle(
                            SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
                        ) {
                            append(inlineMarkdown(text.substring(i + 1, labelEnd), accent, codeBackground))
                        }
                        i = urlEnd + 1
                    } else {
                        append('[')
                        i++
                    }
                }

                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }

private enum class NodeShape { RECT, ROUND, DIAMOND, CIRCLE }

private data class FlowNode(val id: String, val label: String, val shape: NodeShape)

private data class FlowEdge(val from: String, val to: String, val label: String?)

private data class FlowDiagram(
    val horizontal: Boolean,
    val nodes: List<FlowNode>,
    val edges: List<FlowEdge>,
)

private val flowEdgeSplitter = Regex(
    "\\s*(?:-->|---|==>|-\\.->|\\.\\.\\.|--|==)\\s*",
)

private val flowEdgeLabel = Regex("^\\|([^|]*)\\|")

/**
 * 只解析 Mermaid flowchart/graph 的常用子集：节点、链式箭头、|标签|。
 * 解析失败时调用方会退回普通代码块。
 */
private fun parseFlowDiagram(raw: String): FlowDiagram? {
    var horizontal = false
    var sawHeader = false
    val nodes = LinkedHashMap<String, FlowNode>()
    val edges = mutableListOf<FlowEdge>()

    raw.replace("\r\n", "\n").split('\n').forEach { rawLine ->
        val line = rawLine.substringBefore("%%").trim()
        if (line.isEmpty()) return@forEach
        val lower = line.lowercase()
        if (lower.startsWith("flowchart") || lower.startsWith("graph")) {
            sawHeader = true
            horizontal = Regex("\\b(lr|rl)\\b").containsMatchIn(lower)
            return@forEach
        }
        if (lower.startsWith("subgraph") || lower == "end" ||
            lower.startsWith("classdef") || lower.startsWith("class ") ||
            lower.startsWith("style ") || lower.startsWith("linkstyle") ||
            lower.startsWith("click ") || lower.startsWith("direction ")
        ) {
            return@forEach
        }
        line.trimEnd(';').split(';').forEach { statement ->
            parseFlowStatement(statement, nodes, edges)
        }
    }

    if (!sawHeader || nodes.isEmpty()) return null
    return FlowDiagram(horizontal = horizontal, nodes = nodes.values.toList(), edges = edges)
}

private fun parseFlowStatement(
    statement: String,
    nodes: MutableMap<String, FlowNode>,
    edges: MutableList<FlowEdge>,
) {
    val text = statement.trim()
    if (text.isEmpty()) return
    val tokens = text.split(flowEdgeSplitter)
    if (tokens.size == 1) {
        registerFlowNode(tokens.first(), nodes)
        return
    }
    var previous: String? = null
    tokens.forEachIndexed { index, rawToken ->
        val token = rawToken.trim()
        if (token.isEmpty()) return@forEachIndexed
        val labelMatch = flowEdgeLabel.find(token)
        val edgeLabel = labelMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        val nodeText = if (labelMatch != null) {
            token.substring(labelMatch.range.last + 1)
        } else {
            token
        }
        val id = registerFlowNode(nodeText, nodes) ?: return@forEachIndexed
        val from = previous
        if (index > 0 && from != null) {
            edges += FlowEdge(from, id, edgeLabel)
        }
        previous = id
    }
}

private fun registerFlowNode(raw: String, nodes: MutableMap<String, FlowNode>): String? {
    val token = raw.trim()
    if (token.isEmpty()) return null
    val bracket = token.indexOfFirst { it == '[' || it == '(' || it == '{' }
    if (bracket < 0) {
        val id = token.takeWhile { !it.isWhitespace() }
        if (id.isEmpty()) return null
        nodes.putIfAbsent(id, FlowNode(id, id, NodeShape.RECT))
        return id
    }

    val id = token.substring(0, bracket).trim().takeWhile { !it.isWhitespace() }
    if (id.isEmpty()) return null
    val wrapper = token.substring(bracket)
    val shape = when {
        wrapper.startsWith("((") -> NodeShape.CIRCLE
        wrapper.startsWith("[(") -> NodeShape.ROUND
        wrapper.startsWith("[") -> NodeShape.RECT
        wrapper.startsWith("(") -> NodeShape.ROUND
        wrapper.startsWith("{") -> NodeShape.DIAMOND
        else -> NodeShape.RECT
    }
    val inner = when {
        wrapper.startsWith("((") && wrapper.endsWith("))") -> wrapper.substring(2, wrapper.length - 2)
        wrapper.startsWith("[(") && wrapper.endsWith(")]") -> wrapper.substring(2, wrapper.length - 2)
        wrapper.startsWith("[") && wrapper.endsWith("]") -> wrapper.substring(1, wrapper.length - 1)
        wrapper.startsWith("(") && wrapper.endsWith(")") -> wrapper.substring(1, wrapper.length - 1)
        wrapper.startsWith("{") && wrapper.endsWith("}") -> wrapper.substring(1, wrapper.length - 1)
        else -> wrapper
    }
    val label = inner
        .replace("<br/>", " / ")
        .replace("<br>", " / ")
        .trim()
        .trim('"', '\'')
        .ifEmpty { id }

    val current = nodes[id]
    if (current == null) {
        nodes[id] = FlowNode(id, label, shape)
    } else if (current.label == id && label != id) {
        nodes[id] = current.copy(label = label)
    }
    return id
}

private fun flowLayers(diagram: FlowDiagram): List<List<FlowNode>> {
    if (diagram.nodes.isEmpty()) return emptyList()
    val outgoing = diagram.edges.groupBy { it.from }
    val targets = diagram.edges.map { it.to }.toSet()
    val roots = diagram.nodes
        .filter { it.id !in targets }
        .map { it.id }
        .ifEmpty { listOf(diagram.nodes.first().id) }
    val depth = mutableMapOf<String, Int>()
    val queue = ArrayDeque<Pair<String, Int>>()
    roots.forEach { id ->
        if (depth.put(id, 0) == null) queue.addLast(id to 0)
    }
    while (queue.isNotEmpty()) {
        val (id, currentDepth) = queue.removeFirst()
        outgoing[id].orEmpty().forEach { edge ->
            val next = currentDepth + 1
            if (next < diagram.nodes.size && next > (depth[edge.to] ?: -1)) {
                depth[edge.to] = next
                queue.addLast(edge.to to next)
            }
        }
    }
    diagram.nodes.forEach { depth.putIfAbsent(it.id, 0) }
    return diagram.nodes
        .groupBy { depth[it.id] ?: 0 }
        .toSortedMap()
        .values
        .toList()
}

@Composable
private fun FlowChart(diagram: FlowDiagram, accent: Color, color: Color) {
    val layers = remember(diagram) { flowLayers(diagram) }
    if (layers.isEmpty()) return

    if (diagram.horizontal) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            layers.forEachIndexed { index, layer ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    layer.forEach { node ->
                        FlowNodeChip(node, accent, color, Modifier.widthIn(max = 190.dp))
                    }
                }
                if (index < layers.lastIndex) {
                    FlowArrow(
                        vertical = false,
                        color = accent,
                        labels = flowEdgeLabels(diagram, layers[index], layers[index + 1]),
                    )
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            layers.forEachIndexed { index, layer ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    layer.forEach { node ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            FlowNodeChip(node, accent, color, Modifier.widthIn(max = 230.dp))
                        }
                    }
                }
                if (index < layers.lastIndex) {
                    FlowArrow(
                        vertical = true,
                        color = accent,
                        labels = flowEdgeLabels(diagram, layers[index], layers[index + 1]),
                    )
                }
            }
        }
    }
}

private fun flowEdgeLabels(
    diagram: FlowDiagram,
    from: List<FlowNode>,
    to: List<FlowNode>,
): List<String> {
    val fromIds = from.map { it.id }.toSet()
    val toIds = to.map { it.id }.toSet()
    return diagram.edges.asSequence()
        .filter { it.from in fromIds && it.to in toIds }
        .mapNotNull { it.label }
        .distinct()
        .toList()
}

@Composable
private fun FlowArrow(vertical: Boolean, color: Color, labels: List<String>) {
    val modifier = if (vertical) {
        Modifier.fillMaxWidth().height(24.dp)
    } else {
        Modifier.padding(horizontal = 8.dp).height(24.dp)
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (labels.isNotEmpty()) {
            Text(
                text = labels.joinToString(" / "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Text(
            text = if (vertical) "↓" else "→",
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun FlowNodeChip(
    node: FlowNode,
    accent: Color,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shape = when (node.shape) {
        NodeShape.CIRCLE, NodeShape.ROUND -> RoundedCornerShape(50)
        NodeShape.DIAMOND -> RoundedCornerShape(10.dp)
        NodeShape.RECT -> RoundedCornerShape(8.dp)
    }
    val isDecision = node.shape == NodeShape.DIAMOND
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (isDecision) accent else color,
        border = BorderStroke(
            1.dp,
            if (isDecision) accent else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Text(
            text = node.label,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
