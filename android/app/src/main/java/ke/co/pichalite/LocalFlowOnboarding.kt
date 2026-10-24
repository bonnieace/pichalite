package ke.co.pichalite

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlin.math.sin

internal class LocalFlowIntroStore(context: Context, name: String = "local_flow_intro_v1") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    val completed get() = preferences.getBoolean("completed", false)
    fun complete() { check(preferences.edit().putBoolean("completed", true).commit()) { "Could not save introduction progress" } }
}

/** Shown once on this feature's first launch, including upgrades; replayable from Help. */
@Composable internal fun LocalFlowOnboarding(finish: () -> Unit, autoAdvance: Boolean = true, playMotion: Boolean = true) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val reducedMotion = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(step, active, autoAdvance, reducedMotion) {
        if (active && autoAdvance && !reducedMotion && step < 2) { delay(4800); step++ }
    }
    val titles = listOf("Smaller photos.\nSame memories.", "Saved first.\nVerified next.", "Then make room\nfor more.")
    val captions = listOf(
        "Compress local photos right on your phone, even offline. Your pictures stay on your device.",
        "The smaller photo is saved to Gallery and checked. Your original stays until that copy is safe.",
        "The verified copy replaces the local original. Allow Android’s prompt if asked. Already-small originals are retained.",
    )
    Column(Modifier.fillMaxSize().background(Paper).safeDrawingPadding().testTag("local_flow_onboarding")) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("pichalite", Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = Ink)
            TextButton(onClick = finish) { Text("Skip") }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            key(step) { IntroMotion(step, active && playMotion && !reducedMotion, Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 300.dp).aspectRatio(1.25f)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("COMPRESS", "SAVE", "REPLACE").forEachIndexed { index, label ->
                    Text("0${index + 1} $label", fontSize = 10.sp, letterSpacing = 0.7.sp, fontWeight = FontWeight.Bold,
                        color = if (step == index) Orange else Muted)
                }
            }
            Crossfade(targetState = step, label = "Local flow explanation") { page ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(titles[page], color = Ink, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.ExtraBold)
                    Text(captions[page], color = Muted, fontSize = 15.sp, lineHeight = 23.sp)
                    if (page == 2) Text("Google Photos imports keep their cloud originals.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { index ->
                    Box(Modifier.weight(1f).height(3.dp).background(if (index == step) Orange else Line, RoundedCornerShape(3.dp)))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
                Button(onClick = { if (step < 2) step++ else finish() }, modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Ink)) {
                    Text(if (step == 2) "Let’s get started" else "Next", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable private fun IntroMotion(step: Int, playing: Boolean, modifier: Modifier) {
    val progress: State<Float> = if (playing) {
        rememberInfiniteTransition(label = "Photo journey").animateFloat(0f, 1f,
            infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "Photo journey phase")
    } else remember { mutableFloatStateOf(0.85f) }
    val descriptions = listOf("A large photo becomes a smaller photo", "The smaller photo is saved and verified in Gallery", "The verified copy is kept while the original moves to the bin")
    Canvas(modifier.semantics { contentDescription = descriptions[step] }) {
        val p = progress.value
        val unit = size.width / 320f
        fun point(x: Float, y: Float) = Offset(size.width * x, size.height * y)
        fun check(center: Offset, radius: Float = 20f * unit) {
            drawCircle(Green, radius, center)
            val path = Path().apply {
                moveTo(center.x - radius * .45f, center.y)
                lineTo(center.x - radius * .1f, center.y + radius * .35f)
                lineTo(center.x + radius * .48f, center.y - radius * .35f)
            }
            drawPath(path, Color.White, style = Stroke(3f * unit))
        }
        drawCircle(Color(0xFFF1EAD9), size.width * .39f, point(.5f, .5f))
        drawCircle(Orange.copy(alpha = .18f), 10f * unit, point(.86f, .24f + .025f * sin(p * 6.28f)))
        drawCircle(Green.copy(alpha = .25f), 5f * unit, point(.12f, .7f))
        when (step) {
            0 -> {
                drawPhoto(point(.25f, .5f), unit * (1f - p * .12f), -12f, Color(0xFFE4D6B5))
                drawCircle(Orange.copy(alpha = .16f * (1f - p)), (12f + p * 27f) * unit, point(.52f, .5f), style = Stroke(2f * unit))
                drawLine(Orange, point(.46f, .5f), point(.61f, .5f), 3f * unit)
                drawLine(Orange, point(.58f, .45f), point(.61f, .5f), 3f * unit)
                drawLine(Orange, point(.58f, .55f), point(.61f, .5f), 3f * unit)
                drawPhoto(point(.76f, .5f), unit * (.65f + sin(p * 3.14f) * .03f), 8f, Orange)
            }
            1 -> {
                val folder = point(.4f, .52f)
                drawRoundRect(Green.copy(alpha = .25f), folder + Offset(0f, -18f * unit), Size(55f * unit, 30f * unit), CornerRadius(8f * unit))
                drawRoundRect(Green, folder, Size(145f * unit, 86f * unit), CornerRadius(12f * unit))
                val t = (p * 1.8f).coerceIn(0f, 1f)
                drawPhoto(point(.22f + .39f * t, .30f + .20f * t), .68f * unit, -8f * (1f - t), Orange)
                if (p > .55f) check(point(.82f, .72f))
            }
            else -> {
                val bin = point(.2f, .65f)
                drawRoundRect(Muted.copy(alpha = .2f), bin, Size(54f * unit, 55f * unit), CornerRadius(6f * unit))
                drawLine(Muted, bin + Offset(-5f * unit, 0f), bin + Offset(59f * unit, 0f), 4f * unit)
                drawLine(Muted, bin + Offset(17f * unit, -7f * unit), bin + Offset(37f * unit, -7f * unit), 4f * unit)
                val t = (p * 1.7f).coerceIn(0f, 1f)
                drawPhoto(point(.28f, .31f + t * .36f), unit * (.8f - t * .5f), -12f + t * 25f, Color(0xFFE4D6B5), 1f - t)
                drawPhoto(point(.72f, .45f), .85f * unit, 7f, Orange)
                check(point(.79f, .69f))
            }
        }
    }
}

private fun DrawScope.drawPhoto(center: Offset, scale: Float, angle: Float, frame: Color, alpha: Float = 1f) {
    val w = 83f * scale; val h = 110f * scale
    rotate(angle, center) {
        drawRoundRect(frame, center - Offset(w / 2, h / 2), Size(w, h), CornerRadius(10f * scale), alpha = alpha)
        drawRoundRect(Paper, center - Offset(w * .4f, h * .4f), Size(w * .8f, h * .64f), CornerRadius(4f * scale), alpha = alpha)
        val mountains = Path().apply {
            moveTo(center.x - w * .4f, center.y + h * .24f)
            lineTo(center.x - w * .1f, center.y - h * .1f)
            lineTo(center.x + w * .07f, center.y + h * .05f)
            lineTo(center.x + w * .24f, center.y - h * .02f)
            lineTo(center.x + w * .4f, center.y + h * .24f); close()
        }
        drawPath(mountains, Ink, alpha)
        drawCircle(Orange, w * .095f, center + Offset(w * .2f, -h * .22f), alpha)
    }
}
