package com.example.yellowpad

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Redo
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val PaperColor = Color(0xFFFFF2BF)
private val InkColor = Color(0xFF202020)

private data class InkStroke(
    val points: List<Offset>,
    val color: Color,
    val width: Float,
    val erase: Boolean = false
)

private data class TextArea(val id: String, val text: String, val x: Float, val y: Float)
private data class PageSnapshot(val strokes: List<InkStroke>, val textAreas: List<TextArea>)

private data class NotePage(
    var title: String,
    val strokes: MutableList<InkStroke> = mutableListOf(),
    val textAreas: MutableList<TextArea> = mutableListOf(),
    val undoStack: MutableList<PageSnapshot> = mutableListOf(),
    val redoStack: MutableList<PageSnapshot> = mutableListOf()
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent { MaterialTheme { NotebookApp() } }
    }
}

@Composable
private fun NotebookApp() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val pages = remember { mutableStateListOf<NotePage>().apply { addAll(loadPages(context)) } }
    if (pages.isEmpty()) pages.add(NotePage("Page 1"))

    var current by remember { mutableIntStateOf(0) }
    var drawMode by remember { mutableStateOf(true) }
    var eraser by remember { mutableStateOf(false) }
    var shapeRecognition by remember { mutableStateOf(false) }
    var toolsExpanded by remember { mutableStateOf(false) }
    var pagesExpanded by remember { mutableStateOf(false) }
    var penWidth by remember { mutableFloatStateOf(3.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var activePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var focusedTextId by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }

    fun page() = pages[current.coerceIn(0, pages.lastIndex)]
    fun save() = savePages(context, pages)
    val contentRevision = refresh
    fun resetPaperView() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }
    LaunchedEffect(drawMode, current) {
        if (!drawMode) {
            val target = page().textAreas.firstOrNull() ?: TextArea(
                id = "${System.nanoTime()}",
                text = "",
                x = with(density) { 16.dp.toPx() },
                y = with(density) { 8.dp.toPx() }
            ).also {
                recordUndo(page())
                page().textAreas.add(it)
                save()
                refresh++
            }
            focusedTextId = target.id
        }
    }

    LaunchedEffect(drawMode, current, focusedTextId) {
        if (!drawMode && focusedTextId != null) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(PaperColor)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                val target = page()
                if (target.undoStack.isNotEmpty()) {
                    target.redoStack.add(snapshot(target))
                    restore(target, target.undoStack.removeAt(target.undoStack.lastIndex))
                    refresh++
                    save()
                }
            }) { Icon(Icons.Outlined.Undo, "Undo", tint = Color.Black) }
            IconButton(onClick = {
                val target = page()
                if (target.redoStack.isNotEmpty()) {
                    target.undoStack.add(snapshot(target))
                    restore(target, target.redoStack.removeAt(target.redoStack.lastIndex))
                    refresh++
                    save()
                }
            }) { Icon(Icons.Outlined.Redo, "Redo", tint = Color.Black) }
            Spacer(Modifier.weight(1f))
            if (zoom > 1.001f) {
                IconButton(onClick = ::resetPaperView) {
                    Icon(Icons.Outlined.ZoomOutMap, "Reset zoom", tint = Color.Black)
                }
            }
            IconButton(onClick = {
                pages.add(NotePage("Page ${pages.size + 1}"))
                current = pages.lastIndex
                focusedTextId = null
                activePoints = emptyList()
                resetPaperView()
                if (!drawMode) keyboard?.show()
                save()
            }) { Icon(Icons.Outlined.Add, "Add page", tint = Color.Black) }
            IconButton(onClick = { pagesExpanded = true }) {
                Icon(Icons.Outlined.ViewList, "Pages", tint = Color.Black)
            }
            Box {
                IconButton(onClick = { toolsExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, "Drawing tools", tint = Color.Black)
                }
                DropdownMenu(expanded = toolsExpanded, onDismissRequest = { toolsExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(if (eraser) "Use pen" else "Use eraser") },
                        leadingIcon = { Icon(Icons.Outlined.Create, null) },
                        onClick = { eraser = !eraser; toolsExpanded = false }
                    )
                    DropdownMenuItem(
                        text = { Text(if (shapeRecognition) "Disable shape recognition" else "Recognize shapes") },
                        onClick = { shapeRecognition = !shapeRecognition; toolsExpanded = false }
                    )
                    Column(Modifier.width(220.dp).padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text("Pen size", fontSize = 13.sp)
                        Slider(value = penWidth, onValueChange = { penWidth = it }, valueRange = 1f..10f)
                    }
                    DropdownMenuItem(
                        text = { Text("Clear page") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                        onClick = {
                            val target = page()
                            if (target.strokes.isNotEmpty() || target.textAreas.isNotEmpty()) {
                                recordUndo(target)
                                target.strokes.clear()
                                target.textAreas.clear()
                                focusedTextId = null
                                refresh++
                                save()
                            }
                            toolsExpanded = false
                        }
                    )
                }
            }
        }

        BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .background(PaperColor)
                    .pointerInput(current) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downPosition = down.position
                            val strokePoints = mutableListOf<Offset>()
                            if (drawMode) strokePoints.add(Offset((downPosition.x - panX) / zoom, (downPosition.y - panY) / zoom))
                            var maxMovement = 0f
                            var hadMultiplePointers = false
                            var consumedByChild = down.isConsumed

                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.any { it.isConsumed }) consumedByChild = true
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    hadMultiplePointers = true
                                    activePoints = emptyList()
                                    val oldCentroid = centroid(event.changes.map { it.previousPosition })
                                    val newCentroid = centroid(pressed.map { it.position })
                                    val oldDistance = averageDistance(event.changes.filter { it.pressed || it.previousPressed }.map { it.previousPosition }, oldCentroid)
                                    val newDistance = averageDistance(pressed.map { it.position }, newCentroid)
                                    val factor = if (oldDistance > 0f && newDistance > 0f) newDistance / oldDistance else 1f
                                    val nextZoom = (zoom * factor).coerceIn(1f, 4f)
                                    val paperAnchor = Offset((oldCentroid.x - panX) / zoom, (oldCentroid.y - panY) / zoom)
                                    zoom = nextZoom
                                    panX = if (nextZoom == 1f) 0f else newCentroid.x - paperAnchor.x * nextZoom
                                    panY = if (nextZoom == 1f) 0f else newCentroid.y - paperAnchor.y * nextZoom
                                    event.changes.forEach { it.consume() }
                                } else if (pressed.isEmpty()) {
                                    val release = event.changes.firstOrNull()
                                    if (drawMode && !hadMultiplePointers) {
                                        val stroke = recognizeShape(strokePoints, shapeRecognition)
                                        if (stroke.isNotEmpty()) {
                                            recordUndo(page())
                                            page().strokes.add(
                                                InkStroke(
                                                    stroke,
                                                    if (eraser) PaperColor else InkColor,
                                                    if (eraser) 24f else penWidth * 2.2f,
                                                    eraser
                                                )
                                            )
                                            save()
                                            refresh++
                                        }
                                        activePoints = emptyList()
                                    } else if (!drawMode && !hadMultiplePointers && !consumedByChild && maxMovement < viewConfiguration.touchSlop) {
                                        val position = release?.position ?: downPosition
                                        val paperPosition = Offset((position.x - panX) / zoom, (position.y - panY) / zoom)
                                        val firstRule = with(density) { 34.dp.toPx() }
                                        val ruleGap = with(density) { 36.dp.toPx() }
                                        val rule = max(0, ((paperPosition.y - firstRule) / ruleGap).roundToInt())
                                        val textArea = TextArea(
                                            id = "${System.nanoTime()}",
                                            text = "",
                                            x = paperPosition.x.coerceAtLeast(with(density) { 12.dp.toPx() }),
                                            y = firstRule + rule * ruleGap - with(density) { 26.dp.toPx() }
                                        )
                                        recordUndo(page())
                                        page().textAreas.add(textArea)
                                        focusedTextId = textArea.id
                                        refresh++
                                        save()
                                    }
                                    break
                                } else {
                                    val change = pressed.first()
                                    maxMovement = max(maxMovement, (change.position - downPosition).getDistance())
                                    if (drawMode && !hadMultiplePointers) {
                                        strokePoints.add(Offset((change.position.x - panX) / zoom, (change.position.y - panY) / zoom))
                                        activePoints = strokePoints.toList()
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
            ) {
                val viewportWidth = constraints.maxWidth.toFloat()
                val textAreas = page().textAreas.toList()
                LaunchedEffect(focusedTextId, maxHeight, zoom, drawMode) {
                    val focusedArea = textAreas.firstOrNull { it.id == focusedTextId }
                    if (!drawMode && focusedArea != null) {
                        val top = focusedArea.y * zoom + panY
                        val bottom = top + with(density) { 36.dp.toPx() } * zoom
                        val topLimit = with(density) { 8.dp.toPx() }
                        val bottomLimit = with(density) { (maxHeight - 12.dp).toPx() }
                        if (bottom > bottomLimit) panY -= bottom - bottomLimit
                        if (top < topLimit) panY += topLimit - top
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = panX
                            translationY = panY
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val lineGap = with(density) { 36.dp.toPx() }
                        var y = with(density) { 34.dp.toPx() }
                        while (y < size.height) {
                            drawLine(Color(0xFF303030), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                            y += lineGap
                        }
                        val visibleStrokes = page().strokes + listOfNotNull(
                            activePoints.takeIf { it.isNotEmpty() }?.let {
                                InkStroke(it, if (eraser) PaperColor else InkColor, if (eraser) 24f else penWidth * 2.2f, eraser)
                            }
                        )
                        visibleStrokes.forEach { stroke ->
                            if (stroke.points.size == 1) {
                                drawCircle(stroke.color, stroke.width / 2f, stroke.points.first())
                            } else if (stroke.points.size > 1) {
                                val path = Path().apply {
                                    moveTo(stroke.points.first().x, stroke.points.first().y)
                                    stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                                drawPath(path, stroke.color, style = Stroke(stroke.width, cap = StrokeCap.Round))
                            }
                        }
                    }

                    if (!drawMode) {
                        textAreas.forEach { area ->
                            val xDp = with(density) { area.x.toDp() }
                            val yDp = with(density) { area.y.toDp() }
                            val fieldWidth = with(density) { (viewportWidth - area.x - 12.dp.toPx()).coerceAtLeast(48f).toDp() }
                            BasicTextField(
                                value = area.text,
                                onValueChange = { value ->
                                    val target = page()
                                    val index = target.textAreas.indexOfFirst { it.id == area.id }
                                    if (index >= 0 && target.textAreas[index].text != value) {
                                        recordUndo(target)
                                        target.textAreas[index] = target.textAreas[index].copy(text = value)
                                        refresh++
                                        save()
                                    }
                                },
                                modifier = Modifier
                                    .offset(x = xDp, y = yDp)
                                    .width(fieldWidth)
                                    .height(36.dp)
                                    .then(if (area.id == focusedTextId) Modifier.focusRequester(focusRequester) else Modifier),
                                textStyle = TextStyle(color = InkColor, fontSize = 19.sp, lineHeight = 36.sp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                onTextLayout = {},
                                decorationBox = { innerTextField ->
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) { innerTextField() }
                                }
                            )
                        }
                    }
                }
            }
        Row(
            Modifier.fillMaxWidth().height(78.dp).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModeButton(
                selected = drawMode,
                icon = { Icon(Icons.Outlined.Create, "Draw") },
                onClick = {
                    drawMode = true
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
            )
            Spacer(Modifier.width(22.dp))
            ModeButton(
                selected = !drawMode,
                icon = { Icon(Icons.Outlined.Edit, "Write") },
                onClick = {
                    drawMode = false
                    if (page().textAreas.isNotEmpty()) focusedTextId = page().textAreas.first().id
                }
            )
        }
    }

    if (pagesExpanded) {
        AlertDialog(
            onDismissRequest = { pagesExpanded = false },
            title = { Text("Pages") },
            text = {
                Column {
                    pages.forEachIndexed { index, notePage ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                current = index
                                focusedTextId = null
                                activePoints = emptyList()
                                resetPaperView()
                                pagesExpanded = false
                            }
                        ) {
                            Text(if (index == current) "${notePage.title}  ✓" else notePage.title, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pagesExpanded = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun ModeButton(selected: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(58.dp),
        shape = CircleShape,
        color = if (selected) Color.Black else Color.White,
        contentColor = if (selected) Color.White else Color.Black,
        shadowElevation = if (selected) 3.dp else 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            icon()
        }
    }
}

private fun snapshot(page: NotePage) = PageSnapshot(page.strokes.toList(), page.textAreas.toList())

private fun recordUndo(page: NotePage) {
    page.undoStack.add(snapshot(page))
    if (page.undoStack.size > 100) page.undoStack.removeAt(0)
    page.redoStack.clear()
}

private fun restore(page: NotePage, state: PageSnapshot) {
    page.strokes.clear()
    page.strokes.addAll(state.strokes)
    page.textAreas.clear()
    page.textAreas.addAll(state.textAreas)
}

private fun centroid(points: List<Offset>): Offset {
    if (points.isEmpty()) return Offset.Zero
    return Offset(points.sumOf { it.x.toDouble() }.toFloat() / points.size, points.sumOf { it.y.toDouble() }.toFloat() / points.size)
}

private fun averageDistance(points: List<Offset>, center: Offset): Float {
    if (points.isEmpty()) return 0f
    return points.sumOf { (it - center).getDistance().toDouble() }.toFloat() / points.size
}

private fun recognizeShape(points: List<Offset>, enabled: Boolean): List<Offset> {
    if (!enabled || points.size < 5) return points
    val first = points.first()
    val last = points.last()
    val boundsLeft = points.minOf { it.x }
    val boundsRight = points.maxOf { it.x }
    val boundsTop = points.minOf { it.y }
    val boundsBottom = points.maxOf { it.y }
    val width = boundsRight - boundsLeft
    val height = boundsBottom - boundsTop
    val diagonal = sqrt(width * width + height * height)
    if (diagonal < 36f) return points

    val pathLength = points.zipWithNext().sumOf { (a, b) -> (b - a).getDistance().toDouble() }.toFloat()
    val directLength = (last - first).getDistance()
    if (directLength > 36f && pathLength > 0f && directLength / pathLength > 0.95f) return listOf(first, last)

    if ((first - last).getDistance() > max(18f, diagonal * 0.18f) || width < 18f || height < 18f) return points

    val center = Offset((boundsLeft + boundsRight) / 2f, (boundsTop + boundsBottom) / 2f)
    val aspect = width / height
    val radii = points.map { (it - center).getDistance() }
    val meanRadius = radii.average().toFloat()
    val radialError = radii.map { abs(it - meanRadius) }.average().toFloat() / meanRadius.coerceAtLeast(1f)
    if (aspect in 0.72f..1.38f && radialError < 0.2f) {
        return (0..48).map { index ->
            val angle = 2f * PI.toFloat() * index / 48f
            Offset(center.x + width / 2f * cos(angle), center.y + height / 2f * sin(angle))
        }
    }

    val corners = points.indices.mapNotNull { index ->
        val previous = points[(index - 1 + points.size) % points.size]
        val current = points[index]
        val next = points[(index + 1) % points.size]
        val incoming = atan2(current.y - previous.y, current.x - previous.x)
        val outgoing = atan2(next.y - current.y, next.x - current.x)
        var turn = abs(outgoing - incoming)
        if (turn > PI.toFloat()) turn = 2f * PI.toFloat() - turn
        if (turn > 0.65f) current else null
    }.fold(mutableListOf<Offset>()) { result, point ->
        if (result.isEmpty() || (point - result.last()).getDistance() > diagonal * 0.12f) result.add(point)
        result
    }
    if (corners.size == 4 && corners.all { it.x in boundsLeft..boundsRight && it.y in boundsTop..boundsBottom }) {
        val ordered = corners.sortedBy { atan2(it.y - center.y, it.x - center.x) }
        val rightAngles = ordered.indices.map { index ->
            val a = ordered[(index - 1 + ordered.size) % ordered.size] - ordered[index]
            val b = ordered[(index + 1) % ordered.size] - ordered[index]
            abs(a.x * b.x + a.y * b.y) / (a.getDistance() * b.getDistance()).coerceAtLeast(1f)
        }
        if (rightAngles.all { it < 0.42f }) {
            return listOf(
                Offset(boundsLeft, boundsTop), Offset(boundsRight, boundsTop),
                Offset(boundsRight, boundsBottom), Offset(boundsLeft, boundsBottom), Offset(boundsLeft, boundsTop)
            )
        }
    }
    if (corners.size == 3) return corners + corners.first()
    return points
}

private fun savePages(context: android.content.Context, pages: List<NotePage>) {
    val root = JSONArray()
    pages.forEach { page ->
        val item = JSONObject().put("title", page.title)
            .put("text", page.textAreas.firstOrNull()?.text.orEmpty())
        val strokes = JSONArray()
        page.strokes.forEach { stroke ->
            val points = JSONArray()
            stroke.points.forEach { points.put(JSONObject().put("x", it.x).put("y", it.y)) }
            strokes.put(JSONObject().put("points", points).put("color", stroke.color.value.toLong().toString()).put("width", stroke.width).put("erase", stroke.erase))
        }
        val textAreas = JSONArray()
        page.textAreas.forEach { area ->
            textAreas.put(JSONObject().put("id", area.id).put("text", area.text).put("x", area.x).put("y", area.y))
        }
        item.put("strokes", strokes).put("textAreas", textAreas)
        root.put(item)
    }
    context.getSharedPreferences("notebook", 0).edit().putString("pages", root.toString()).apply()
}

private fun loadPages(context: android.content.Context): List<NotePage> {
    return try {
        val raw = context.getSharedPreferences("notebook", 0).getString("pages", null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val page = NotePage(item.optString("title", "Page ${index + 1}"))
            val strokes = item.optJSONArray("strokes") ?: JSONArray()
            for (strokeIndex in 0 until strokes.length()) {
                val stroke = strokes.getJSONObject(strokeIndex)
                val savedPoints = stroke.getJSONArray("points")
                val points = (0 until savedPoints.length()).map { pointIndex ->
                    val point = savedPoints.getJSONObject(pointIndex)
                    Offset(point.getDouble("x").toFloat(), point.getDouble("y").toFloat())
                }
                val color = Color(stroke.optString("color", Color.Black.value.toLong().toString()).toULong().toLong())
                page.strokes.add(InkStroke(points, color, stroke.optDouble("width", 4.0).toFloat(), stroke.optBoolean("erase", false)))
            }
            val savedAreas = item.optJSONArray("textAreas")
            if (savedAreas != null) {
                for (areaIndex in 0 until savedAreas.length()) {
                    val area = savedAreas.getJSONObject(areaIndex)
                    page.textAreas.add(
                        TextArea(
                            area.optString("id", "${index}_$areaIndex"),
                            area.optString("text", ""),
                            area.optDouble("x", 16.0).toFloat(),
                            area.optDouble("y", 8.0).toFloat()
                        )
                    )
                }
            } else {
                val legacyText = item.optString("text", "")
                if (legacyText.isNotEmpty()) page.textAreas.add(TextArea("${index}_legacy", legacyText, 16f, 8f))
            }
            page
        }
    } catch (_: Exception) {
        emptyList()
    }
}