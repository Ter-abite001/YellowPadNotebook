package com.example.yellowpad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

private data class InkStroke(val points: List<Offset>, val color: Color, val width: Float, val erase: Boolean = false)
private data class NotePage(var title: String, var text: String = "", val strokes: MutableList<InkStroke> = mutableListOf())

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { NotebookApp() } }
    }
}

@Composable
private fun NotebookApp() {
    val context = LocalContext.current
    val pages = remember { mutableStateListOf<NotePage>().apply { addAll(loadPages(context)) } }
    if (pages.isEmpty()) pages.add(NotePage("Page 1"))
    var current by remember { mutableIntStateOf(0) }
    var drawMode by remember { mutableStateOf(true) }
    var eraser by remember { mutableStateOf(false) }
    var penWidth by remember { mutableFloatStateOf(3.5f) }
    var inkColor by remember { mutableStateOf(Color(0xFF202020)) }
    var activePoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }

    fun save() = savePages(context, pages)
    fun page() = pages[current.coerceIn(0, pages.lastIndex)]

    Column(Modifier.fillMaxSize().background(Color(0xFFF2F0E8))) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Yellow Pad", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { if (current > 0) { current--; activePoints = emptyList() } }) {
                Icon(Icons.Default.ArrowBack, "Previous page")
            }
            Text("${current + 1}/${pages.size}", fontSize = 13.sp)
            IconButton(onClick = { if (current < pages.lastIndex) { current++; activePoints = emptyList() } }) {
                Icon(Icons.Default.ArrowForward, "Next page")
            }
            IconButton(onClick = {
                pages.add(NotePage("Page ${pages.size + 1}"))
                current = pages.lastIndex
                activePoints = emptyList()
                save()
            }) { Icon(Icons.Default.Add, "New page") }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = drawMode, onClick = { drawMode = true }, label = { Text("Handwrite") }, leadingIcon = { Icon(Icons.Default.Brush, null) })
            FilterChip(selected = !drawMode, onClick = { drawMode = false }, label = { Text("Type") }, leadingIcon = { Icon(Icons.Default.Edit, null) })
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                if (page().strokes.isNotEmpty()) { page().strokes.removeAt(page().strokes.lastIndex); refresh++; save() }
            }) { Icon(Icons.Default.Undo, "Undo") }
            IconButton(onClick = {
                // Redo is available through the redo stack below.
                redo(page())
                refresh++; save()
            }) { Icon(Icons.Default.Redo, "Redo") }
        }

        if (drawMode) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { eraser = false }) { Text(if (!eraser) "✓ Pen" else "Pen") }
                TextButton(onClick = { eraser = true }) { Text(if (eraser) "✓ Eraser" else "Eraser") }
                Text("Size", fontSize = 12.sp)
                Slider(value = penWidth, onValueChange = { penWidth = it }, valueRange = 1f..10f, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    page().strokes.clear(); activePoints = emptyList(); refresh++; save()
                }) { Icon(Icons.Default.Delete, null); Text("Clear") }
            }
        } else {
            Text("Type calculations below. Text is saved automatically.", fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
            .border(1.dp, Color(0xFFD7C58C), RoundedCornerShape(4.dp))
            .background(Color(0xFFFFF4C7))) {
            Canvas(Modifier.fillMaxSize()) {
                val lineGap = 36.dp.toPx()
                var y = 34.dp.toPx()
                while (y < size.height) {
                    drawLine(Color(0xFFB8C9D8), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    y += lineGap
                }
                val all = page().strokes + listOfNotNull(if (activePoints.isNotEmpty()) InkStroke(activePoints, if (eraser) Color(0xFFFFF4C7) else inkColor, if (eraser) 24f else penWidth * 2.2f, eraser) else null)
                all.forEach { s ->
                    if (s.points.size == 1) drawCircle(s.color, s.width / 2, s.points.first())
                    else if (s.points.size > 1) {
                        val p = Path().apply { moveTo(s.points.first().x, s.points.first().y); s.points.drop(1).forEach { lineTo(it.x, it.y) } }
                        drawPath(p, s.color, style = Stroke(s.width, cap = StrokeCap.Round))
                    }
                }
            }
            if (drawMode) {
                // Stylus and finger input are both supported.
                Box(Modifier.fillMaxSize().pointerInput(current, eraser, penWidth, inkColor) {
                    detectDragGestures(
                        onDragStart = { activePoints = listOf(it) },
                        onDrag = { change, _ -> activePoints = activePoints + change.position },
                        onDragEnd = {
                            if (activePoints.isNotEmpty()) {
                                page().strokes.add(InkStroke(activePoints, if (eraser) Color(0xFFFFF4C7) else inkColor, if (eraser) 24f else penWidth * 2.2f, eraser))
                                activePoints = emptyList(); save()
                            }
                        },
                        onDragCancel = { activePoints = emptyList() }
                    )
                })
            } else {
                BasicTextField(
                    value = page().text,
                    onValueChange = { page().text = it; save(); refresh++ },
                    modifier = Modifier.fillMaxSize().padding(start = 18.dp, top = 16.dp, end = 12.dp, bottom = 12.dp),
                    textStyle = TextStyle(color = Color(0xFF202020), fontSize = 19.sp, lineHeight = 36.sp),
                    decorationBox = { inner ->
                        Box { if (page().text.isEmpty()) Text("Start typing…", color = Color.Gray, fontSize = 18.sp); inner() }
                    }
                )
            }
        }

        Text("Pages", fontSize = 12.sp, color = Color.DarkGray, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pages.forEachIndexed { i, p ->
                AssistChip(onClick = { current = i; activePoints = emptyList() }, label = { Text(p.title) }, colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (i == current) Color(0xFFE8D58E) else Color.White
                ))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private val redoStacks = mutableMapOf<NotePage, MutableList<InkStroke>>()
private fun redo(page: NotePage) {
    val stack = redoStacks[page] ?: return
    if (stack.isNotEmpty()) page.strokes.add(stack.removeAt(stack.lastIndex))
}

private fun savePages(context: android.content.Context, pages: List<NotePage>) {
    val root = JSONArray()
    pages.forEach { p ->
        val item = JSONObject().put("title", p.title).put("text", p.text)
        val strokes = JSONArray()
        p.strokes.forEach { s ->
            val pts = JSONArray()
            s.points.forEach { pts.put(JSONObject().put("x", it.x).put("y", it.y)) }
            strokes.put(JSONObject().put("points", pts).put("color", s.color.value.toLong().toString()).put("width", s.width).put("erase", s.erase))
        }
        item.put("strokes", strokes); root.put(item)
    }
    context.getSharedPreferences("notebook", 0).edit().putString("pages", root.toString()).apply()
}

private fun loadPages(context: android.content.Context): List<NotePage> {
    return try {
        val raw = context.getSharedPreferences("notebook", 0).getString("pages", null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val item = arr.getJSONObject(i)
            val p = NotePage(item.optString("title", "Page ${i + 1}"), item.optString("text", ""))
            val ss = item.optJSONArray("strokes") ?: JSONArray()
            for (j in 0 until ss.length()) {
                val s = ss.getJSONObject(j); val pts = s.getJSONArray("points")
                val points = (0 until pts.length()).map { k -> val v = pts.getJSONObject(k); Offset(v.getDouble("x").toFloat(), v.getDouble("y").toFloat()) }
                val color = Color(s.optString("color", Color.Black.value.toLong().toString()).toULong().toLong())
                p.strokes.add(InkStroke(points, color, s.optDouble("width", 4.0).toFloat(), s.optBoolean("erase", false)))
            }
            p
        }
    } catch (_: Exception) { emptyList() }
}
