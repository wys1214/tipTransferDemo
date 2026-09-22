package com.yunsi.tiptransferdemo

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.google.android.filament.MaterialInstance
import io.github.sceneview.*
import io.github.sceneview.math.*
import kotlin.math.cos
import kotlin.math.sin

private data class TokenMaterials(
    val body: MaterialInstance, val light: MaterialInstance,
    val detail: MaterialInstance, val green: MaterialInstance,
)

/** One renderer per screen, shared materials, real meshes for both moving and settled tokens. */
@Composable
private fun TokenScene(modifier: Modifier, content: @Composable SceneScope.(Map<VisualTheme, TokenMaterials>) -> Unit) {
    val engine = rememberEngine()
    val loader = rememberMaterialLoader(engine)
    val materials = remember(loader) {
        VisualTheme.entries.associateWith { theme ->
            val gold = theme == VisualTheme.GOLD
            TokenMaterials(
                loader.createColorInstance(if (gold) Color(0xFFEFB844) else theme.primary,
                    metallic = if (gold) .75f else .12f, roughness = .28f, reflectance = .5f),
                loader.createColorInstance(if (gold) Color(0xFFFFE6A2) else Color(0xFFF1F5FF),
                    metallic = if (gold) .7f else .05f, roughness = .26f),
                loader.createColorInstance(if (gold) Color(0xFFC58925) else theme.dark,
                    metallic = .25f, roughness = .32f),
                loader.createColorInstance(Color(0xFF3E9870), metallic = 0f, roughness = .55f),
            )
        }
    }
    SceneView(
        modifier = modifier.background(Brush.verticalGradient(listOf(Color(0xFFF9FBFF), Color(0xFFEDF2FA)))),
        surfaceType = SurfaceType.TextureSurface,
        engine = engine, materialLoader = loader, isOpaque = false,
        autoCenterContent = false, autoFitContent = false, cameraManipulator = null,
        cameraNode = rememberCameraNode(engine) { position = Position(z = 4.7f) },
        mainLightNode = rememberMainLightNode(engine) { intensity = 85_000f },
    ) { content(materials) }
}

@Composable
private fun SceneScope.Collectible(theme: VisualTheme, pose: TokenPose, materials: TokenMaterials) {
    Node(position = Position(pose.x, pose.y, 0f), scale = Scale(pose.scale),
        rotation = Rotation(pose.tilt, pose.turn, 0f)) {
        when (theme) {
            VisualTheme.GOLD -> Node(rotation = Rotation(x = 78f)) {
                CylinderNode(radius = .42f, height = .085f, sideCount = 48, materialInstance = materials.body)
                CylinderNode(radius = .34f, height = .092f, sideCount = 48, materialInstance = materials.detail)
                CylinderNode(radius = .315f, height = .098f, sideCount = 48, materialInstance = materials.body)
                TorusNode(majorRadius = .37f, minorRadius = .025f, majorSegments = 48, minorSegments = 10,
                    materialInstance = materials.light, position = Position(y = .044f))
                val star = remember {
                    List(10) { i ->
                        val angle = Math.PI / 2 + i * Math.PI / 5
                        val r = if (i % 2 == 0) .22 else .1
                        Position2((cos(angle) * r).toFloat(), (sin(angle) * r).toFloat())
                    }
                }
                ShapeNode(polygonPath = star, materialInstance = materials.light, position = Position(y = .053f))
                repeat(24) { i ->
                    val angle = i * Math.PI / 12
                    CubeNode(size = Size(.012f, .088f, .02f), materialInstance = materials.light,
                        position = Position((cos(angle) * .413).toFloat(), 0f, (sin(angle) * .413).toFloat()),
                        rotation = Rotation(y = -i * 15f))
                }
            }
            VisualTheme.ROCKET -> {
                CylinderNode(radius = .18f, height = .55f, sideCount = 32, materialInstance = materials.light)
                Node(position = Position(y = .29f), scale = Scale(1f, 1.35f, 1f)) {
                    SphereNode(radius = .18f, stacks = 16, slices = 24, materialInstance = materials.body)
                }
                CylinderNode(radius = .13f, height = .08f, sideCount = 24, materialInstance = materials.detail,
                    position = Position(y = -.31f))
                Node(position = Position(y = .07f, z = .175f), rotation = Rotation(x = 90f)) {
                    CylinderNode(radius = .095f, height = .025f, sideCount = 24, materialInstance = materials.detail)
                    TorusNode(majorRadius = .094f, minorRadius = .015f, majorSegments = 24, minorSegments = 8,
                        materialInstance = materials.body, position = Position(y = .018f))
                }
                repeat(3) { i -> Node(rotation = Rotation(y = i * 120f)) {
                    CubeNode(size = Size(.085f, .25f, .23f), materialInstance = materials.body,
                        position = Position(y = -.22f, z = .18f), rotation = Rotation(x = -22f))
                } }
            }
            VisualTheme.FLOWER -> {
                CylinderNode(radius = .023f, height = .65f, sideCount = 12, materialInstance = materials.green,
                    position = Position(y = -.17f))
                repeat(2) { i -> Node(position = Position(if (i == 0) -.1f else .1f, -.19f + i * .13f, 0f),
                    rotation = Rotation(z = if (i == 0) 45f else -45f), scale = Scale(.45f, 1f, .2f)) {
                    SphereNode(radius = .16f, stacks = 12, slices = 16, materialInstance = materials.green)
                } }
                repeat(6) { i ->
                    val a = i * Math.PI / 3
                    Node(position = Position((cos(a) * .19).toFloat(), .22f + (sin(a) * .19).toFloat(), 0f),
                        scale = Scale(1f, 1f, .48f)) {
                        SphereNode(radius = .15f, stacks = 16, slices = 20, materialInstance = materials.body)
                    }
                }
                SphereNode(radius = .11f, stacks = 16, slices = 20, materialInstance = materials.light,
                    position = Position(y = .22f, z = .085f))
            }
            VisualTheme.HEART_BALLOON -> {
                // Overlapping tapered lobes share one parent transform; no detached pieces on rotation.
                repeat(2) { i -> Node(position = Position(x = if (i == 0) -.12f else .12f, y = .13f),
                    rotation = Rotation(z = if (i == 0) 37f else -37f), scale = Scale(.78f, 1.12f, .65f)) {
                    SphereNode(radius = .27f, stacks = 24, slices = 28, materialInstance = materials.body)
                } }
                ConeNode(radius = .042f, height = .08f, sideCount = 16, materialInstance = materials.body,
                    position = Position(y = -.22f))
                repeat(8) { i ->
                    CylinderNode(radius = .007f, height = .047f, sideCount = 8, materialInstance = materials.light,
                        position = Position(x = sin(i * .5f) * .025f, y = -.28f - i * .043f),
                        rotation = Rotation(z = cos(i * .5f) * 12f))
                }
            }
            VisualTheme.PAPER_PLANE -> Node(rotation = Rotation(x = 65f, y = -25f, z = -12f)) {
                val left = remember { listOf(Position2(0f, -.5f), Position2(-.42f, .3f), Position2(0f, .15f)) }
                val right = remember { listOf(Position2(0f, -.5f), Position2(0f, .15f), Position2(.42f, .3f)) }
                ShapeNode(polygonPath = left, materialInstance = materials.light, rotation = Rotation(z = -12f))
                ShapeNode(polygonPath = right, materialInstance = materials.body, rotation = Rotation(z = 12f))
                CubeNode(size = Size(.018f, .12f, .65f), materialInstance = materials.light, position = Position(z = -.12f))
            }
            VisualTheme.PURPLE -> {
                SphereNode(radius = .29f, stacks = 24, slices = 32, materialInstance = materials.body)
                TorusNode(majorRadius = .39f, minorRadius = .034f, majorSegments = 40, minorSegments = 10,
                    materialInstance = materials.light, rotation = Rotation(x = 28f, z = -25f))
                SphereNode(radius = .055f, stacks = 12, slices = 16, materialInstance = materials.light,
                    position = Position(x = .34f, y = .14f))
            }
        }
    }
}

@Composable
fun SendingTokenScene(theme: VisualTheme, progress: Float, dragFraction: Float, modifier: Modifier = Modifier) {
    TokenScene(modifier) { palette ->
        val launched = progress > 0f
        val t = progress.coerceIn(0f, 1f)
        // Acceleration starts at the release position; the same mesh exits the scene.
        val y = if (launched) .42f + 4f * t * t else dragFraction * .42f
        // 테마 변경 시 이전 Filament 노드를 확실히 폐기해 두 재화가 겹치지 않게 한다.
        key(theme) {
            Collectible(theme, TokenPose(if (theme == VisualTheme.PAPER_PLANE) t * .45f else 0f,
                y, .95f, if (theme == VisualTheme.ROCKET) 0f else t * -18f,
                if (theme == VisualTheme.GOLD) t * 360f else t * 18f), palette.getValue(theme))
        }
    }
}

/** 선택 버튼용 실제 3D 모델 스트립. 엔진 하나에서 모든 미리보기를 렌더링한다. */
@Composable
fun TokenSelectionScene(
    themes: List<VisualTheme>,
    selectedTheme: VisualTheme,
    modifier: Modifier = Modifier,
) {
    TokenScene(modifier) { palette ->
        themes.forEachIndexed { index, theme ->
            key(theme) {
                val center = (themes.lastIndex / 2f)
                Collectible(
                    theme = theme,
                    pose = TokenPose(
                        x = (index - center) * .72f,
                        y = 0f,
                        scale = if (theme == selectedTheme) .43f else .34f,
                        tilt = if (theme == VisualTheme.PAPER_PLANE) 8f else -4f,
                        turn = if (theme == selectedTheme) 10f else 0f,
                    ),
                    materials = palette.getValue(theme),
                )
            }
        }
    }
}

private data class SceneToken(val index: Long, val theme: VisualTheme, val start: Long)

@Composable
fun ReceivingTokenScene(theme: VisualTheme, count: Long, modifier: Modifier = Modifier) {
    val tokens = remember { mutableStateListOf<SceneToken>() }
    var observedCount by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    val view = LocalView.current
    // This effect only enqueues. New snapshots do not cancel animations already in flight.
    LaunchedEffect(count) {
        if (count < observedCount) { tokens.clear(); observedCount = 0L }
        val start = SystemClock.uptimeMillis()
        if (count > observedCount) {
            // Bound rendering work after reconnects; the amount/count label always shows the full ledger.
            val first = maxOf(observedCount, count - VISIBLE_TOKEN_LIMIT)
            for (i in first until count) tokens.add(SceneToken(i, theme, start + (i - first) * 90L))
            observedCount = count
            while (tokens.size > VISIBLE_TOKEN_LIMIT * 2) tokens.removeAt(0)
        }
    }
    LaunchedEffect(tokens.lastOrNull()?.index) {
        while (tokens.any { now < it.start + TOKEN_LANDING_MS }) {
            withFrameNanos { now = SystemClock.uptimeMillis() }
        }
        while (tokens.size > VISIBLE_TOKEN_LIMIT) tokens.removeAt(0)
    }
    LaunchedEffect(count) {
        if (count > 0) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }
    Box(modifier) {
    TokenScene(Modifier.fillMaxSize()) { palette ->
        tokens.forEach { token -> key(token.index) {
            val p = ((now - token.start).toFloat() / TOKEN_LANDING_MS).coerceIn(0f, 1f)
            if (now >= token.start) {
                // Reused slots stop displaying the older model once the new one has settled.
                val replaced = tokens.any { it.index > token.index && it.index % VISIBLE_TOKEN_LIMIT == token.index % VISIBLE_TOKEN_LIMIT && now >= it.start + TOKEN_LANDING_MS }
                if (!replaced) Collectible(token.theme,
                    arrivalPose(token.index, p, token.theme == VisualTheme.FLOWER, token.theme == VisualTheme.HEART_BALLOON),
                    palette.getValue(token.theme))
            }
        } }
    }
    // A restrained halo marks central arrival. It never changes confirmed amounts.
    Canvas(Modifier.fillMaxSize()) {
        tokens.forEach { token ->
            val age = (now - token.start).toFloat() / TOKEN_LANDING_MS
            if (age in .30f.. .65f) {
                val pulse = (age - .30f) / .35f
                drawCircle(token.theme.primary.copy(alpha = (1f - pulse) * .18f),
                    radius = size.minDimension * (.14f + .16f * pulse),
                    center = Offset(size.width * .5f, size.height * .43f),
                    style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
    }
}
