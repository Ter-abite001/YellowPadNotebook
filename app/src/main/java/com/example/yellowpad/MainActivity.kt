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
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Keyboard
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
import androidx.compose.runtime.snapshots.SnapshotStateList
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
    val strokes: SnapshotStateList<InkStroke> = mutableStateListOf(),
    val textAreas: SnapshotStateList<TextArea> = mutableStateListOf(),
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

    fun page() = pages[current.coerceIn(0, pages.lastIndex)]
    fun save() = savePages(context, pages)
    val currentPage = page()
    val visibleStrokes = currentPage.strokes.toList()
    val textAreas = currentPage.textAreas.toList()
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
            }
            focusedTextId = target.id
        }
    }

    LaunchedEffect(drawMode, current, focusedTextId) {
        if (!drawMode && focusedTextId != null) {
            withFrameNanos { }
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
                    save()
                }
            }) { Icon(Icons.Outlined.Undo, "Undo", tint = Color.Black) }
            IconButton(onClick = {
                val target = page()
                if (target.redoStack.isNotEmpty()) {
                    target.undoStack.add(snapshot(target))
                    restore(target, target.redoStack.removeAt(target.redoStack.lastIndex))
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
                        val strokesToDraw = visibleStrokes + listOfNotNull(
                            activePoints.takeIf { it.isNotEmpty() }?.let {
                                InkStroke(it, if (eraser) PaperColor else InkColor, if (eraser) 24f else penWidth * 2.2f, eraser)
                            }
                        )
                        strokesToDraw.forEach { stroke ->
                            if (stroke.points.size == 1) {
                                drawCircle(stroke.color, stroke.width / 2f, stroke.points.first())
                            } else if (stroke.points.size > 1) {
                                val path = smoothedStrokePath(stroke.points)
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
                icon = { Icon(Icons.Outlined.Gesture, "Handwriting") },
                onClick = {
                    drawMode = true
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
            )
            Spacer(Modifier.width(22.dp))
            ModeButton(
                selected = !drawMode,
                icon = { Icon(Icons.Outlined.Keyboard, "Typing") },
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

private fun smoothedStrokePath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, points.first().y)
    if (points.size == 2) {
        lineTo(points.last().x, points.last().y)
        return@apply
    }
    for (index in 0 until points.lastIndex) {
        val previous = points[(index - 1).coerceAtLeast(0)]
        val start = points[index]
        val end = points[index + 1]
        val next = points[(index + 2).coerceAtMost(points.lastIndex)]
        val control1 = start + (end - previous) / 6f
        val control2 = end - (next - start) / 6f
        cubicTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
    }
    lineTo(points.last().x, points.last().y)
}

private fun recognizeShape(points: List<Offset>, enabled: Boolean): List<Offset> {
    if (!enabled || points.size < 2) return points

    val boundsLeft = points.minOf { it.x }
    val boundsRight = points.maxOf { it.x }
    val boundsTop = points.minOf { it.y }
    val boundsBottom = points.maxOf { it.y }
    val center = Offset((boundsLeft + boundsRight) / 2f, (boundsTop + boundsBottom) / 2f)
    val scale = sqrt((boundsRight - boundsLeft) * (boundsRight - boundsLeft) +
        (boundsBottom - boundsTop) * (boundsBottom - boundsTop))
    if (scale < 36f) return points

    val normalized = points.map { Offset((it.x - center.x) / scale, (it.y - center.y) / scale) }
    val first = normalized.first()
    val last = normalized.last()
    val directLength = (last - first).getDistance()
    val pathLength = normalized.zipWithNext().sumOf { (a, b) -> (b - a).getDistance().toDouble() }.toFloat()

    if (pathLength > 0f && directLength / pathLength > 0.985f) {
        val maxDeviation = normalized.maxOf { distanceFromLine(it, first, last) }
        if (maxDeviation < 0.025f) return listOf(points.first(), points.last())
    }

    val closed = (first - last).getDistance() < 0.12f
    if (!closed && points.size >= 8) {
        recognizeArrow(normalized, center, scale)?.let { return it }
    }
    if (!closed || normalized.size < 8) return points

    val ring = normalized.dropLast(1).ifEmpty { normalized }
    val sampled = resampleStroke(ring + ring.first(), 96).dropLast(1)
    val corners = findCorners(sampled, closed = true)

    if (corners.size < 3) {
        val left = normalized.minOf { it.x }
        val right = normalized.maxOf { it.x }
        val top = normalized.minOf { it.y }
        val bottom = normalized.maxOf { it.y }
        val radiusX = (right - left) / 2f
        val radiusY = (bottom - top) / 2f
        if (radiusX <= 0f || radiusY <= 0f || radiusY / radiusX !in 0.15f..6.7f) return points
        val ellipseCenter = Offset((left + right) / 2f, (top + bottom) / 2f)
        val ellipseErrors = sampled.map { point ->
            abs(sqrt(
                ((point.x - ellipseCenter.x) / radiusX) * ((point.x - ellipseCenter.x) / radiusX) +
                    ((point.y - ellipseCenter.y) / radiusY) * ((point.y - ellipseCenter.y) / radiusY)
            ) - 1f)
        }
        val angularCoverage = sampled.map {
            atan2((it.y - ellipseCenter.y) / radiusY, (it.x - ellipseCenter.x) / radiusX)
        }.sorted()
        val largestAngularGap = (angularCoverage.zipWithNext().maxOfOrNull { it.second - it.first } ?: 0f)
            .coerceAtLeast((angularCoverage.firstOrNull()?.let { angularCoverage.first() + 2f * PI.toFloat() - angularCoverage.last() } ?: 0f))
        val ellipseConfidence = 1f - ellipseErrors.average().toFloat() / 0.18f
        if (ellipseConfidence >= 0.72f && ellipseErrors.maxOrNull()!! < 0.36f && largestAngularGap < 1.2f) {
            return (0..64).map { index ->
                val angle = 2f * PI.toFloat() * index / 64f
                Offset(
                    center.x + (ellipseCenter.x + radiusX * cos(angle)) * scale,
                    center.y + (ellipseCenter.y + radiusY * sin(angle)) * scale
                )
            }
        }
        return points
    }

    if (corners.size !in 3..4) return points
    val ordered = corners.sortedBy { atan2(it.y, it.x) }
    val polygonError = sampled.map { point ->
        ordered.indices.minOf { index ->
            distanceFromSegment(point, ordered[index], ordered[(index + 1) % ordered.size])
        }
    }
    if (polygonError.average() > 0.035 || polygonError.maxOrNull()!! > 0.11) return points

    val sideLengths = ordered.indices.map { index ->
        (ordered[(index + 1) % ordered.size] - ordered[index]).getDistance()
    }
    if (sideLengths.minOrNull()!! < 0.12f) return points
    if (corners.size == 3) {
        val angles = interiorAngles(ordered)
        val confidence = 1f - polygonError.average().toFloat() / 0.08f
        if (angles.all { it in 0.38f..2.45f } && confidence >= 0.72f) {
            return (ordered + ordered.first()).map { Offset(center.x + it.x * scale, center.y + it.y * scale) }
        }
        return points
    }

    val angleErrors = interiorAngles(ordered).map { abs(it - PI.toFloat() / 2f) }
    if (angleErrors.any { it > 0.55f }) return points
    val rightAngleError = angleErrors.average().toFloat()
    val rightAngleConfidence = 1f - rightAngleError / 0.6f
    val sideRatio = sideLengths.maxOrNull()!! / sideLengths.minOrNull()!!
    val polygonConfidence = 1f - polygonError.average().toFloat() / 0.08f
    if (rightAngleConfidence < 0.68f || polygonConfidence < 0.72f) return points
    val square = sideRatio <= 1.15f
    val recognizedVertices = if (square) {
        val firstEdge = ordered[1] - ordered[0]
        val secondEdge = ordered[3] - ordered[0]
        val side = (sideLengths[0] + sideLengths[1] + sideLengths[2] + sideLengths[3]) / 4f
        val firstUnit = firstEdge / firstEdge.getDistance()
        val secondUnit = secondEdge / secondEdge.getDistance()
        val orientation = if (firstUnit.x * secondUnit.y - firstUnit.y * secondUnit.x >= 0f) 1f else -1f
        val perpendicularUnit = Offset(-firstUnit.y * orientation, firstUnit.x * orientation)
        listOf(
            ordered[0],
            ordered[0] + firstUnit * side,
            ordered[0] + firstUnit * side + perpendicularUnit * side,
            ordered[0] + perpendicularUnit * side
        )
    } else {
        ordered
    }
    return (recognizedVertices + recognizedVertices.first()).map {
        Offset(center.x + it.x * scale, center.y + it.y * scale)
    }
}

private fun resampleStroke(points: List<Offset>, count: Int): List<Offset> {
    if (points.size < 2 || count < 2) return points
    val lengths = points.zipWithNext().map { (a, b) -> (b - a).getDistance() }
    val totalLength = lengths.sum()
    if (totalLength <= 0f) return List(count) { points.first() }
    val result = ArrayList<Offset>(count)
    var segment = 0
    var traversed = 0f
    for (index in 0 until count) {
        val target = totalLength * index / (count - 1)
        while (segment < lengths.lastIndex && traversed + lengths[segment] < target) {
            traversed += lengths[segment]
            segment++
        }
        val length = lengths[segment]
        val fraction = if (length == 0f) 0f else (target - traversed) / length
        result.add(points[segment] + (points[segment + 1] - points[segment]) * fraction)
    }
    return result
}

private fun findCorners(points: List<Offset>, closed: Boolean): List<Offset> {
    if (points.size < 8) return emptyList()
    val window = max(3, points.size / 24)
    val candidates = points.indices.mapNotNull { index ->
        if (!closed && (index < window || index >= points.size - window)) return@mapNotNull null
        val before = points[if (closed) (index - window + points.size) % points.size else index - window]
        val current = points[index]
        val after = points[if (closed) (index + window) % points.size else index + window]
        val incoming = current - before
        val outgoing = after - current
        val turn = abs(atan2(
            incoming.x * outgoing.y - incoming.y * outgoing.x,
            incoming.x * outgoing.x + incoming.y * outgoing.y
        ))
        if (turn >= 0.58f) Corner(current, turn) else null
    }.sortedByDescending { it.strength }

    val selected = mutableListOf<Corner>()
    for (candidate in candidates) {
        if (selected.none { (it.point - candidate.point).getDistance() < 0.10f }) {
            selected.add(candidate)
        }
    }
    return selected.map { it.point }
}

private data class Corner(val point: Offset, val strength: Float)

private fun interiorAngles(vertices: List<Offset>): List<Float> = vertices.indices.map { index ->
    val previous = vertices[(index - 1 + vertices.size) % vertices.size] - vertices[index]
    val next = vertices[(index + 1) % vertices.size] - vertices[index]
    atan2(
        abs(previous.x * next.y - previous.y * next.x),
        previous.x * next.x + previous.y * next.y
    )
}

private fun distanceFromLine(point: Offset, start: Offset, end: Offset): Float =
    distanceFromSegment(point, start, end)

private fun distanceFromSegment(point: Offset, start: Offset, end: Offset): Float {
    val segment = end - start
    val lengthSquared = segment.x * segment.x + segment.y * segment.y
    if (lengthSquared <= 0f) return (point - start).getDistance()
    val projection = (((point.x - start.x) * segment.x + (point.y - start.y) * segment.y) / lengthSquared)
        .coerceIn(0f, 1f)
    return (point - (start + segment * projection)).getDistance()
}

private fun recognizeArrow(points: List<Offset>, center: Offset, scale: Float): List<Offset>? {
    if (points.size < 20) return null
    val sampled = resampleStroke(points, 100)
    val start = sampled.first()
    val end = sampled.last()
    val bestTip = (12..72).flatMap { firstIndex ->
        (firstIndex + 10..88).mapNotNull { secondIndex ->
            val distance = (sampled[firstIndex] - sampled[secondIndex]).getDistance()
            if (distance < 0.035f) Triple(firstIndex, secondIndex, distance) else null
        }
    }.minByOrNull { it.third } ?: return null
    val (firstIndex, secondIndex) = bestTip
    val tip = (sampled[firstIndex] + sampled[secondIndex]) / 2f
    val firstWing = sampled.subList(firstIndex + 1, secondIndex)
        .maxByOrNull { distanceFromSegment(it, start, tip) } ?: return null
    val secondWing = end
    val shaft = tip - start
    val shaftLength = shaft.getDistance()
    val shaftDeviation = sampled.take(firstIndex + 1).maxOf { distanceFromSegment(it, start, tip) }
    val firstWingVector = firstWing - tip
    val secondWingVector = secondWing - tip
    if (shaftLength < 0.35f || shaftDeviation > 0.04f ||
        firstWingVector.getDistance() !in 0.08f..0.5f ||
        secondWingVector.getDistance() !in 0.08f..0.5f ||
        max(firstWingVector.getDistance(), secondWingVector.getDistance()) /
            min(firstWingVector.getDistance(), secondWingVector.getDistance()) > 2.5f
    ) return null
    val direction = shaft / shaftLength
    val firstProjection = firstWingVector.x * direction.x + firstWingVector.y * direction.y
    val secondProjection = secondWingVector.x * direction.x + secondWingVector.y * direction.y
    if (firstProjection > -0.035f || secondProjection > -0.035f) return null
    val firstAngle = atan2(abs(direction.x * firstWingVector.y - direction.y * firstWingVector.x),
        -(direction.x * firstWingVector.x + direction.y * firstWingVector.y))
    val secondAngle = atan2(abs(direction.x * secondWingVector.y - direction.y * secondWingVector.x),
        -(direction.x * secondWingVector.x + direction.y * secondWingVector.y))
    if (firstAngle !in 0.25f..1.35f || secondAngle !in 0.25f..1.35f) return null
    val confidence = 1f - bestTip.third / 0.05f
    if (confidence < 0.72f) return null
    return listOf(start, tip, firstWing, tip, secondWing).map {
        Offset(center.x + it.x * scale, center.y + it.y * scale)
    }
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