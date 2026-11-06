package com.example.tapgame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private const val PI_F = 3.14159265f

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

// ───────────────────────── Navegación ─────────────────────────

@Composable
fun App() {
    val nav = rememberNavController()
    var selected by remember { mutableIntStateOf(0) }
    var result by remember { mutableIntStateOf(0) } // 0 = nada, 1 = ganó, 2 = perdió
    NavHost(nav, startDestination = "menu") {
        composable("menu") {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("DUELO 3D", style = MaterialTheme.typography.displayMedium)
                Text("Ninja vs Samurái · mejor de 3 rondas")
                Spacer(Modifier.height(24.dp))
                Button(onClick = { nav.navigate("select") }) { Text("Jugar") }
            }
        }
        composable("select") {
            SelectScreen(result, selected, { selected = it }) { nav.navigate("game") }
        }
        composable("game") {
            GameScreen(selected) { won ->
                result = if (won) 1 else 2
                nav.navigate("select") { popUpTo("menu") }
            }
        }
    }
}

// ───────────────────────── Selección de personaje ─────────────────────────

@Composable
fun SelectScreen(result: Int, selected: Int, onPick: (Int) -> Unit, onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(8.dp),
        Arrangement.Center,
        Alignment.CenterHorizontally
    ) {
        val title = when (result) {
            1 -> "¡Ganaste! Elige tu luchador"
            2 -> "Perdiste. ¡Revancha! Elige tu luchador"
            else -> "Elige tu luchador"
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            FighterCard(0, selected == 0) { onPick(0) }
            FighterCard(1, selected == 1) { onPick(1) }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onStart) { Text("¡A pelear!") }
    }
}

@Composable
fun FighterCard(index: Int, isSelected: Boolean, onClick: () -> Unit) {
    val renderer = remember { Renderer() }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now -> t = now / 1e9f }
        }
    }
    Column(
        Modifier
            .width(170.dp)
            .border(
                if (isSelected) 3.dp else 1.dp,
                if (isSelected) Color(0xFFFFC107) else Color.Gray,
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            renderer.renderPortrait(this, index, t)
        }
        Text(if (index == 0) "Ninja Azul" else "Samurái Rojo")
    }
}

// ───────────────────────── Lógica del juego ─────────────────────────
// Unidades ≈ centímetros. El escenario va de x = -300 a x = 300.

class Fighter(startX: Float) {
    var x = startX
    var y = 0f
    var vy = 0f
    var hp = 100f
    var facing = 1
    var moveDir = 0
    var blockInput = false
    var blocking = false
    var crouchInput = false
    var crouching = false
    var stun = 0f
    var attackType = 0      // 1 = puño, 2 = patada
    var attackTimer = 0f
    var attackDur = 0f
    var attackCooldown = 0f
    var hitDone = false
    var walkPhase = 0f
    var win = false
    var lose = false

    val onGround get() = y <= 0f

    fun reset(startX: Float) {
        x = startX; y = 0f; vy = 0f; hp = 100f
        moveDir = 0; blockInput = false; blocking = false
        crouchInput = false; crouching = false
        stun = 0f; attackTimer = 0f; attackCooldown = 0f
        walkPhase = 0f; win = false; lose = false
    }

    fun jump() {
        if (onGround && stun <= 0f && attackTimer <= 0f) vy = 560f
    }

    fun attack(type: Int) {
        if (attackCooldown > 0f || stun > 0f || attackTimer > 0f) return
        attackType = type
        attackDur = if (type == 1) 0.25f else 0.40f
        attackTimer = attackDur
        attackCooldown = attackDur + 0.15f
        hitDone = false
    }

    fun update(dt: Float, other: Fighter) {
        facing = if (other.x >= x) 1 else -1
        if (attackCooldown > 0f) attackCooldown -= dt
        if (stun > 0f) stun -= dt
        crouching = crouchInput && stun <= 0f && onGround
        blocking = blockInput && stun <= 0f && attackTimer <= 0f && onGround
        val canMove = stun <= 0f && attackTimer <= 0f && !blocking && !crouching
        if (canMove) {
            x += moveDir * 170f * dt
            if (moveDir != 0 && onGround) walkPhase += dt * 9f
        }
        if (attackTimer > 0f) {
            attackTimer -= dt
            if (!hitDone && attackTimer <= attackDur * 0.5f) {
                hitDone = true
                tryHit(other)
            }
        }
        vy -= 1800f * dt
        y += vy * dt
        if (y <= 0f) { y = 0f; vy = 0f }
        x = x.coerceIn(-300f, 300f)
    }

    private fun tryHit(o: Fighter) {
        // Los puños pasan por encima de quien está agachado
        if (o.crouching && attackType == 1) return
        val reach = if (attackType == 1) 85f else 110f
        val dmg = if (attackType == 1) 6f else 10f
        val inFront = (o.x - x) * facing > 0f
        if (inFront && abs(o.x - x) < reach && abs(o.y - y) < 60f) {
            if (o.blocking) {
                o.hp = (o.hp - dmg * 0.15f).coerceAtLeast(0f)
                o.x += facing * 6f
            } else {
                o.hp = (o.hp - dmg).coerceAtLeast(0f)
                o.stun = 0.3f
                o.attackTimer = 0f
                o.x += facing * 20f
            }
        }
    }
}

class Ai {
    private var t = 0f
    fun think(dt: Float, me: Fighter, foe: Fighter) {
        t -= dt
        if (t > 0f) return
        t = 0.15f + Random.nextFloat() * 0.25f
        val dist = abs(foe.x - me.x)
        val toward = if (foe.x > me.x) 1 else -1
        me.blockInput = false
        me.crouchInput = false
        me.moveDir = 0
        if (foe.attackTimer > 0f && dist < 130f) {
            val r = Random.nextFloat()
            if (r < 0.4f) { me.blockInput = true; return }
            if (r < 0.6f && foe.attackType == 1) { me.crouchInput = true; return }
        }
        if (dist > 80f) { me.moveDir = toward; return }
        when (Random.nextInt(10)) {
            0, 1, 2 -> me.attack(1)
            3, 4 -> me.attack(2)
            5 -> me.blockInput = true
            6 -> me.moveDir = -toward
            7 -> me.jump()
            else -> {}
        }
    }
}

class GameModel(val playerChar: Int) {
    val player = Fighter(-150f)
    val enemy = Fighter(150f)
    private val ai = Ai()

    var tick by mutableLongStateOf(0L)
    var pWins by mutableIntStateOf(0)
    var eWins by mutableIntStateOf(0)
    var timeLeft by mutableIntStateOf(60)
    var banner by mutableStateOf("¡Ronda 1!")
    var finished = false
    var left = false
    var right = false

    private var round = 1
    private var clock = 60f
    private var pause = 1.2f
    private var endTimer = 0f
    private var matchEnd = false
    private var matchPlayerWon = false

    val inputOk get() = endTimer <= 0f && pause <= 0f && !finished

    private fun resetRound() {
        player.reset(-150f)
        enemy.reset(150f)
        clock = 60f
        timeLeft = 60
        pause = 1.2f
    }

    fun step(dt: Float, onOver: (Boolean) -> Unit) {
        if (finished) return
        if (endTimer > 0f) {
            endStep(dt, onOver)
            return
        }
        if (pause > 0f) {
            pause -= dt
            if (pause <= 0f) banner = ""
            tick++
            return
        }
        player.moveDir = (if (right) 1 else 0) - (if (left) 1 else 0)
        ai.think(dt, enemy, player)
        player.update(dt, enemy)
        enemy.update(dt, player)
        separate()

        clock -= dt
        timeLeft = clock.toInt().coerceAtLeast(0)

        if (player.hp <= 0f || enemy.hp <= 0f || clock <= 0f) {
            val playerWonRound = when {
                enemy.hp <= 0f && player.hp > 0f -> true
                player.hp <= 0f && enemy.hp > 0f -> false
                else -> player.hp >= enemy.hp
            }
            if (playerWonRound) pWins++ else eWins++
            player.win = playerWonRound
            player.lose = !playerWonRound
            enemy.win = !playerWonRound
            enemy.lose = playerWonRound
            matchEnd = pWins >= 2 || eWins >= 2
            matchPlayerWon = pWins >= 2
            banner = if (matchEnd) {
                if (matchPlayerWon) "¡Ganaste!" else "Perdiste"
            } else if (playerWonRound) "¡Punto para ti!" else "Punto del rival"
            endTimer = if (matchEnd) 3.2f else 1.5f
        }
        tick++
    }

    // Pose de victoria: el ganador salta y el perdedor se rinde
    private fun endStep(dt: Float, onOver: (Boolean) -> Unit) {
        endTimer -= dt
        player.moveDir = 0; enemy.moveDir = 0
        player.blockInput = false; enemy.blockInput = false
        player.crouchInput = false; enemy.crouchInput = false
        val w = if (player.win) player else enemy
        if (w.onGround && endTimer > 0.25f) w.vy = 480f
        player.update(dt, enemy)
        enemy.update(dt, player)
        if (endTimer <= 0f) {
            if (matchEnd) {
                finished = true
                onOver(matchPlayerWon)
                return
            }
            round++
            banner = "¡Ronda $round!"
            resetRound()
        }
        tick++
    }

    private fun separate() {
        val dx = enemy.x - player.x
        val minD = 40f
        val dist = abs(dx)
        if (dist < minD && player.y < 60f && enemy.y < 60f) {
            val push = (minD - dist) / 2f
            val dir = if (dx >= 0f) 1 else -1
            player.x -= dir * push
            enemy.x += dir * push
        }
    }
}

// ───────────────────────── Pantalla de juego ─────────────────────────

@Composable
fun GameScreen(playerChar: Int, onGameOver: (Boolean) -> Unit) {
    val model = remember { GameModel(playerChar) }
    val renderer = remember { Renderer() }

    LaunchedEffect(Unit) {
        var last = 0L
        while (!model.finished) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.033f)
                last = now
                model.step(dt, onGameOver)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val unused = model.tick // suscribe el redibujado a cada frame
            renderer.render(this, model)
        }

        Text(
            "${model.pWins} - ${model.eWins}   ⏱ ${model.timeLeft}",
            Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            color = Color.White
        )
        if (model.banner.isNotEmpty()) {
            Text(
                model.banner,
                Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.displaySmall,
                color = Color.White
            )
        }

        Column(
            Modifier.align(Alignment.BottomStart).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HoldButton("Agacharse") { model.player.crouchInput = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HoldButton("←") { model.left = it }
                HoldButton("→") { model.right = it }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HoldButton("Saltar") { if (it && model.inputOk) model.player.jump() }
            HoldButton("Puño") { if (it && model.inputOk) model.player.attack(1) }
            HoldButton("Patada") { if (it && model.inputOk) model.player.attack(2) }
            HoldButton("Guardia") { model.player.blockInput = it }
        }
    }
}

@Composable
fun HoldButton(label: String, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(62.dp)
            .background(Color(0x99000000), CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onChange(true)
                    tryAwaitRelease()
                    onChange(false)
                })
            },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 11.sp) }
}

// ───────────────────────── Motor 3D propio ─────────────────────────
// Cada luchador son cajas 3D articuladas (cabeza, torso, brazos con codo, piernas
// con rodilla, ropa) proyectadas con perspectiva, sombreadas y ordenadas por profundidad.

class Renderer {

    private class Face {
        val x = FloatArray(4)
        val y = FloatArray(4)
        var depth = 0f
        var color = Color.Black
    }

    private class Pal(
        val style: Int,       // 0 = ninja, 1 = samurái
        val skin: Color,
        val shirt: Color,
        val pants: Color,
        val shoes: Color,
        val glove: Color,
        val fore: Color,
        val hair: Color,
        val band: Color,
        val accent: Color,
        val armor: Color,
        // Postura de combate (ángulos en radianes)
        val lean0: Float,
        val tA0: Float, val sA0: Float, val tB0: Float, val sB0: Float,
        val uA0: Float, val rA0: Float, val uB0: Float, val rB0: Float
    )

    private val palNinja = Pal(
        style = 0,
        skin = Color(0xFFE0AC69), shirt = Color(0xFF1565C0), pants = Color(0xFF0D2A5C),
        shoes = Color(0xFF111111), glove = Color(0xFF111111), fore = Color(0xFF1565C0),
        hair = Color(0xFF1E88E5), band = Color(0xFFFFFFFF), accent = Color(0xFF0B1220),
        armor = Color(0xFF000000),
        lean0 = 0.12f,
        tA0 = 0.7f, sA0 = -1.0f, tB0 = -0.6f, sB0 = 0.14f,
        uA0 = 0.9f, rA0 = 1.2f, uB0 = 0.4f, rB0 = 1.9f
    )
    private val palSamurai = Pal(
        style = 1,
        skin = Color(0xFFD9A066), shirt = Color(0xFFC62828), pants = Color(0xFF1B1B1B),
        shoes = Color(0xFFEEEEEE), glove = Color(0xFF7F0000), fore = Color(0xFFD9A066),
        hair = Color(0xFF111111), band = Color(0xFFEEEEEE), accent = Color(0xFFFFC107),
        armor = Color(0xFF4A1010),
        lean0 = 0.06f,
        tA0 = 0.9f, sA0 = -1.1f, tB0 = -0.9f, sB0 = 0.7f,
        uA0 = 0.8f, rA0 = 1.0f, uB0 = 0.8f, rB0 = 1.0f
    )

    private fun pal(i: Int): Pal = if (i == 0) palNinja else palSamurai

    private val faces = Array(900) { Face() }
    private var n = 0
    private val order = Comparator<Face> { a, b -> b.depth.compareTo(a.depth) }
    private val path = Path()
    private val edgeStyle = Stroke(width = 1.2f)
    private val edgeColor = Color(0x66000000)
    private val skyBrush = Brush.verticalGradient(
        0f to Color(0xFF5C7DB0),
        0.32f to Color(0xFFFFD9B3),
        1f to Color(0xFFFFD9B3)
    )

    private val portrait = Fighter(0f)

    private val sides = floatArrayOf(-1f, 1f)
    private val ropeH = floatArrayOf(55f, 95f)
    private val postColor = Color(0xFF5D4037)
    private val ropeColor = Color(0xFFB71C1C)
    private val lanternColor = Color(0xFFE53935)
    private val eyeColor = Color(0xFF1B1B1B)

    // Fondo
    private val farX = floatArrayOf(0.08f, 0.27f, 0.48f, 0.70f, 0.92f)
    private val farH = floatArrayOf(0.20f, 0.27f, 0.22f, 0.29f, 0.19f)
    private val midPX = floatArrayOf(0.0f, 0.18f, 0.38f, 0.58f, 0.80f, 1.0f)
    private val midPH = floatArrayOf(0.12f, 0.17f, 0.13f, 0.18f, 0.14f, 0.11f)
    private val rowBack = floatArrayOf(0.02f, 0.10f, 0.19f, 0.30f, 0.42f, 0.53f, 0.62f, 0.73f, 0.82f, 0.91f, 0.98f)
    private val rowFront = floatArrayOf(0.05f, 0.24f, 0.47f, 0.70f, 0.93f)
    private val pineColor = Color(0xFF1B4332)
    private val snowColor = Color(0xFFF2F7FB)
    private val trunkColor = Color(0xFF4E342E)
    private val pinkColor = Color(0xFFF8BBD0)
    private val redColor = Color(0xFFC62828)

    // Cámara
    private val camY = 150f
    private val camZ = 620f
    private val cYaw = cos(0.30f)
    private val sYaw = sin(0.30f)
    private val cP = cos(0.12f)
    private val sP = sin(0.12f)
    private var midX = 0f
    private var midY = 0f
    private var focal = 1f

    // Resultados temporales
    private var rx = 0f
    private var ry = 0f
    private var rz = 0f
    private var scrX = 0f
    private var scrY = 0f
    private var endX = 0f
    private var endY = 0f

    // Transformación del luchador actual
    private var curF = 1f
    private var curOx = 0f
    private var curOy = 0f
    private var curOz = 0f
    private var flash = false

    // Cadera e inclinación del torso
    private val hipD = 96f
    private var hipY = 96f
    private var tAng = 0f
    private var tCos = 1f
    private var tSin = 0f

    private val vx = FloatArray(8)
    private val vy = FloatArray(8)
    private val vz = FloatArray(8)
    private val sx = FloatArray(8)
    private val sy = FloatArray(8)

    private val faceIdx = intArrayOf(
        1, 3, 7, 5,   // +X
        0, 2, 6, 4,   // -X
        2, 3, 7, 6,   // +Y
        0, 1, 5, 4,   // -Y
        4, 5, 7, 6,   // +Z
        0, 1, 3, 2    // -Z
    )

    private fun toView(x: Float, y: Float, z: Float) {
        val x1 = x * cYaw + z * sYaw
        val z1 = -x * sYaw + z * cYaw
        val y2 = y - camY
        val z2 = z1 - camZ
        rx = x1
        ry = y2 * cP - z2 * sP
        rz = y2 * sP + z2 * cP
    }

    private fun rotN(x: Float, y: Float, z: Float) {
        val x1 = x * cYaw + z * sYaw
        val z1 = -x * sYaw + z * cYaw
        rx = x1
        ry = y * cP - z1 * sP
        rz = y * sP + z1 * cP
    }

    private fun proj(x: Float, y: Float, z: Float) {
        toView(x, y, z)
        val d = if (-rz < 1f) 1f else -rz
        scrX = midX + focal * rx / d
        scrY = midY - focal * ry / d
    }

    private fun addBox(
        cx: Float, cy: Float, cz: Float,
        hx: Float, hy: Float, hz: Float,
        ang: Float, color: Color
    ) {
        if (n + 6 > faces.size) return
        val ca = cos(ang)
        val sa = sin(ang)
        for (i in 0 until 8) {
            val ax = if ((i and 1) != 0) hx else -hx
            val ay = if ((i and 2) != 0) hy else -hy
            val az = if ((i and 4) != 0) hz else -hz
            val lx = cx + ax * ca - ay * sa
            val ly = cy + ax * sa + ay * ca
            val lz = cz + az
            proj(curOx + curF * lx, curOy + ly, curOz + lz)
            vx[i] = rx; vy[i] = ry; vz[i] = rz
            sx[i] = scrX; sy[i] = scrY
        }
        var r = color.red
        var g = color.green
        var b = color.blue
        if (flash) {
            r += (1f - r) * 0.6f
            g += (1f - g) * 0.6f
            b += (1f - b) * 0.6f
        }
        for (k in 0 until 6) {
            val nx = when (k) { 0 -> ca; 1 -> -ca; 2 -> -sa; 3 -> sa; else -> 0f }
            val ny = when (k) { 0 -> sa; 1 -> -sa; 2 -> ca; 3 -> -ca; else -> 0f }
            val nz = when (k) { 4 -> 1f; 5 -> -1f; else -> 0f }
            val wnx = curF * nx
            var cvx = 0f
            var cvy = 0f
            var cvz = 0f
            for (j in 0 until 4) {
                val v = faceIdx[k * 4 + j]
                cvx += vx[v]; cvy += vy[v]; cvz += vz[v]
            }
            cvx *= 0.25f; cvy *= 0.25f; cvz *= 0.25f
            rotN(wnx, ny, nz)
            if (rx * cvx + ry * cvy + rz * cvz >= 0f) continue // cara de espaldas

            val lit = max(0f, wnx * -0.30f + ny * 0.79f + nz * 0.54f)
            val br = min(1f, 0.45f + 0.6f * lit)
            val fc = faces[n++]
            for (j in 0 until 4) {
                val v = faceIdx[k * 4 + j]
                fc.x[j] = sx[v]
                fc.y[j] = sy[v]
            }
            fc.depth = cvx * cvx + cvy * cvy + cvz * cvz
            fc.color = Color(r * br, g * br, b * br, 1f)
        }
    }

    private fun DrawScope.flush() {
        java.util.Arrays.sort(faces, 0, n, order)
        for (i in 0 until n) {
            val fc = faces[i]
            path.reset()
            path.moveTo(fc.x[0], fc.y[0])
            path.lineTo(fc.x[1], fc.y[1])
            path.lineTo(fc.x[2], fc.y[2])
            path.lineTo(fc.x[3], fc.y[3])
            path.close()
            drawPath(path, fc.color)
            drawPath(path, edgeColor, style = edgeStyle)
        }
        n = 0
    }

    private fun DrawScope.fillQuad(x0: Float, z0: Float, x1: Float, z1: Float, color: Color) {
        path.reset()
        proj(x0, 0f, z0); path.moveTo(scrX, scrY)
        proj(x1, 0f, z0); path.lineTo(scrX, scrY)
        proj(x1, 0f, z1); path.lineTo(scrX, scrY)
        proj(x0, 0f, z1); path.lineTo(scrX, scrY)
        path.close()
        drawPath(path, color)
    }

    private fun DrawScope.shadow(f: Fighter) {
        proj(f.x, 0f, 0f)
        val cx = scrX
        val cy = scrY
        proj(f.x + 28f, 0f, 0f)
        val k = 1f / (1f + f.y / 120f)
        val rad = abs(scrX - cx) * 1.3f * k
        drawOval(Color(0x66000000), Offset(cx - rad, cy - rad * 0.35f), Size(rad * 2f, rad * 0.7f))
    }

    // ── Fondo asiático: cielo, sol, montañas nevadas, torii, pinos y cerezos ──

    private fun DrawScope.mountain(px: Float, baseY: Float, hgt: Float, color: Color, snow: Color) {
        val hb = hgt * 1.1f
        path.reset()
        path.moveTo(px - hb, baseY)
        path.lineTo(px, baseY - hgt)
        path.lineTo(px + hb, baseY)
        path.close()
        drawPath(path, color)
        val cap = hgt * 0.32f
        val cw = cap * 1.1f
        path.reset()
        path.moveTo(px, baseY - hgt)
        path.lineTo(px - cw, baseY - hgt + cap)
        path.lineTo(px - cw * 0.45f, baseY - hgt + cap * 0.7f)
        path.lineTo(px, baseY - hgt + cap * 1.1f)
        path.lineTo(px + cw * 0.45f, baseY - hgt + cap * 0.7f)
        path.lineTo(px + cw, baseY - hgt + cap)
        path.close()
        drawPath(path, snow)
    }

    private fun DrawScope.pine(px: Float, baseY: Float, s: Float) {
        drawRect(trunkColor, Offset(px - s * 0.04f, baseY - s * 0.12f), Size(s * 0.08f, s * 0.12f))
        for (t in 0 until 3) {
            val by = baseY - s * 0.08f - t * s * 0.26f
            val hw = s * (0.30f - 0.07f * t)
            val th = s * 0.36f
            path.reset()
            path.moveTo(px - hw, by)
            path.lineTo(px, by - th)
            path.lineTo(px + hw, by)
            path.close()
            drawPath(path, pineColor)
            path.reset()
            path.moveTo(px - hw * 0.5f, by - th * 0.5f)
            path.lineTo(px, by - th)
            path.lineTo(px + hw * 0.5f, by - th * 0.5f)
            path.close()
            drawPath(path, snowColor)
        }
    }

    private fun DrawScope.sakura(px: Float, baseY: Float, s: Float) {
        drawRect(trunkColor, Offset(px - s * 0.04f, baseY - s * 0.45f), Size(s * 0.08f, s * 0.45f))
        drawCircle(pinkColor, s * 0.24f, Offset(px, baseY - s * 0.62f))
        drawCircle(pinkColor, s * 0.18f, Offset(px - s * 0.2f, baseY - s * 0.5f))
        drawCircle(pinkColor, s * 0.18f, Offset(px + s * 0.2f, baseY - s * 0.5f))
    }

    private fun DrawScope.torii(px: Float, baseY: Float, s: Float) {
        drawRect(redColor, Offset(px - s * 0.5f, baseY - s), Size(s * 0.08f, s))
        drawRect(redColor, Offset(px + s * 0.42f, baseY - s), Size(s * 0.08f, s))
        drawRect(redColor, Offset(px - s * 0.5f, baseY - s * 0.8f), Size(s, s * 0.06f))
        drawRect(Color(0xFF2B2B2B), Offset(px - s * 0.62f, baseY - s * 1.04f), Size(s * 1.24f, s * 0.1f))
    }

    private fun DrawScope.drawBackdrop(w: Float, h: Float) {
        val hz = midY - focal * sP / cP // línea del horizonte
        drawRect(skyBrush)
        drawCircle(Color(0x33FFF3E0), h * 0.11f, Offset(w * 0.74f, h * 0.17f))
        drawCircle(Color(0xFFFFF3E0), h * 0.06f, Offset(w * 0.74f, h * 0.17f))
        for (i in farX.indices) {
            mountain(farX[i] * w, hz + 2f, farH[i] * h, Color(0xFF8FA3BF), Color(0xFFF5F9FF))
        }
        for (i in midPX.indices) {
            mountain(midPX[i] * w, hz + h * 0.012f, midPH[i] * h, Color(0xFF5D7390), Color(0xFFE3ECF7))
        }
        drawRect(Color(0x44FFFFFF), Offset(0f, hz - h * 0.03f), Size(w, h * 0.045f))
        drawRect(Color(0xFFEAF2F8), Offset(0f, hz + h * 0.012f), Size(w, h - hz))
        torii(w * 0.16f, hz + h * 0.09f, h * 0.2f)
        for (i in rowBack.indices) {
            pine(rowBack[i] * w, hz + h * 0.06f, h * 0.12f * (0.85f + 0.1f * (i % 3)))
        }
        sakura(w * 0.36f, hz + h * 0.15f, h * 0.2f)
        sakura(w * 0.82f, hz + h * 0.15f, h * 0.2f)
        for (i in rowFront.indices) {
            pine(rowFront[i] * w, hz + h * 0.17f, h * 0.2f * (0.9f + 0.1f * (i % 2)))
        }
    }

    // ── Personajes ──

    // Caja del tronco/cabeza: gira alrededor de la cadera según la inclinación
    private fun ubBox(
        cx: Float, cy: Float, cz: Float,
        hx: Float, hy: Float, hz: Float, color: Color
    ) {
        val dy = cy - hipD
        addBox(cx * tCos - dy * tSin, cx * tSin + dy * tCos + hipY, cz, hx, hy, hz, tAng, color)
    }

    // Miembro colgante: caja que parte de (px,py) con ángulo ang y largo len
    private fun limb(
        px: Float, py: Float, pz: Float, ang: Float, len: Float,
        hw: Float, hd: Float, color: Color
    ) {
        val s = sin(ang)
        val c = cos(ang)
        addBox(px + len * 0.5f * s, py - len * 0.5f * c, pz, hw, len * 0.5f, hd, ang, color)
        endX = px + len * s
        endY = py - len * c
    }

    private fun leg(thigh: Float, shin: Float, z: Float, pal: Pal, flatShoe: Boolean) {
        val wide = if (pal.style == 1) 2f else 0f
        limb(0f, hipY, z, thigh, 45f, 7f + wide, 6.5f + wide * 0.7f, pal.pants)
        val kx = endX
        val ky = endY
        val a = thigh + shin
        limb(kx, ky, z, a, 45f, 6.2f + wide, 6f + wide * 0.7f, pal.pants)
        val ex = endX
        val ey = endY
        val sa = if (flatShoe) 0f else a
        val c = cos(sa)
        val s = sin(sa)
        addBox(ex + 5f * c + 2f * s, ey + 5f * s - 2f * c, z, 11f, 4f, 6.8f, sa, pal.shoes)
    }

    private fun arm(u: Float, r: Float, z: Float, pal: Pal) {
        val dy = 145f - hipD
        val px = -dy * tSin
        val py = dy * tCos + hipY
        val a1 = u + tAng
        limb(px, py, z, a1, 28f, 5.4f, 5.4f, pal.shirt)
        val ex = endX
        val ey = endY
        val a2 = a1 + r
        limb(ex, ey, z, a2, 24f, 4.6f, 4.6f, pal.fore)
        limb(endX, endY, z, a2, 11f, 6.5f, 6.5f, pal.glove)
    }

    private fun drawFighter(f: Fighter, pal: Pal, time: Float) {
        curF = f.facing.toFloat()
        curOx = f.x
        curOy = f.y
        curOz = 0f
        flash = f.stun > 0f

        val idle = f.onGround && f.moveDir == 0 && f.attackTimer <= 0f &&
            !f.blocking && !f.crouching && f.stun <= 0f && !f.win && !f.lose

        // Postura de combate propia de cada personaje
        var lean = pal.lean0
        var tA = pal.tA0
        var sA = pal.sA0
        var tB = pal.tB0
        var sB = pal.sB0
        var uA = pal.uA0
        var rA = pal.rA0
        var uB = pal.uB0
        var rB = pal.rB0

        if (idle) {
            val br = sin(time * 3f)
            uA += 0.04f * br
            uB -= 0.04f * br
            lean += 0.015f * br
        }
        if (f.moveDir != 0 && f.onGround && f.attackTimer <= 0f && f.stun <= 0f &&
            !f.blocking && !f.crouching
        ) {
            val s = sin(f.walkPhase)
            val c = cos(f.walkPhase)
            tA = 0.5f * s
            tB = -0.5f * s
            sA = -0.7f * max(0f, c)
            sB = -0.7f * max(0f, -c)
        }
        if (!f.onGround) {
            tA = 0.9f; sA = -1.2f; tB = -0.2f; sB = -0.6f
            lean = 0.05f
        }
        if (f.crouching) {
            tA = 1.0f; sA = -1.3f; tB = 0.9f; sB = -1.25f
            lean = 0.22f
            uA = 0.9f; rA = 1.5f; uB = 0.9f; rB = 1.5f
        }
        if (f.blocking) {
            uA = 0.9f; rA = 2.0f; uB = 0.9f; rB = 2.0f
            lean = 0.15f
        }
        if (f.stun > 0f) {
            lean = -0.3f
            uA = -0.2f; rA = 0.3f; uB = -0.2f; rB = 0.3f
        }
        var kicking = false
        if (f.attackTimer > 0f && f.attackDur > 0f) {
            val p = (1f - f.attackTimer / f.attackDur).coerceIn(0f, 1f)
            val e = sin(p * PI_F)
            if (f.attackType == 1) {
                uA = pal.uA0 + (1.55f - pal.uA0) * e
                rA = pal.rA0 * (1f - e)
                lean = pal.lean0 + 0.12f * e
            } else {
                kicking = true
                tA = pal.tA0 + (1.45f - pal.tA0) * e
                val a0 = pal.tA0 + pal.sA0
                val a = a0 * (1f - e) + 1.45f * e - 1.3f * e * (1f - e)
                sA = a - tA
                lean = pal.lean0 - 0.25f * e
            }
        }
        // Poses de victoria y derrota
        if (f.win) {
            tA = 0.9f; sA = -1.5f; tB = -0.4f; sB = -0.9f
            lean = 0.05f
            uA = 2.9f; rA = 0.2f; uB = 2.6f; rB = 0.3f
        } else if (f.lose) {
            tA = 1.1f; sA = -1.3f; tB = 1.0f; sB = -1.25f
            lean = -0.5f
            uA = -0.3f; rA = 0.4f; uB = -0.3f; rB = 0.4f
        }

        // La cadera baja o sube para que el pie de apoyo toque el suelo
        val hA = 6f + 45f * cos(tA) + 45f * cos(tA + sA)
        val hB = 6f + 45f * cos(tB) + 45f * cos(tB + sB)
        hipY = max(hA, hB)

        tAng = -lean
        tCos = cos(tAng)
        tSin = sin(tAng)

        val flatB = f.onGround && !f.win
        val flatA = flatB && !kicking

        // Piernas
        leg(tA, sA, 9f, pal, flatA)
        leg(tB, sB, -9f, pal, flatB)

        // Tronco y cinturón
        ubBox(0f, 123.5f, 0f, 9f, 27.5f, 16f, pal.shirt)

        if (pal.style == 0) {
            // Ninja: capucha azul con máscara, cinta y colas
            ubBox(1f, 164f, 0f, 10.8f, 12.2f, 10.8f, pal.hair)
            ubBox(11.9f, 167f, 0f, 0.5f, 2.6f, 8.4f, pal.skin)
            ubBox(12.4f, 167f, 4.4f, 0.5f, 1.3f, 1.6f, eyeColor)
            ubBox(12.4f, 167f, -4.4f, 0.5f, 1.3f, 1.6f, eyeColor)
            ubBox(1f, 172.5f, 0f, 11.1f, 1.8f, 11.1f, pal.band)
            ubBox(-13f, 171f, 0f, 6f, 1.5f, 2.2f, pal.band)
            ubBox(-11f, 168f, 0f, 5f, 1.3f, 2f, pal.band)
            ubBox(0f, 100f, 0f, 9.8f, 3.5f, 16.8f, pal.accent)
        } else {
            // Samurái: moño, cinta, bigote, peto, hombreras y faja
            ubBox(1f, 164f, 0f, 10.5f, 12f, 10.5f, pal.skin)
            ubBox(0f, 177f, 0f, 11.2f, 3.5f, 11.2f, pal.hair)
            ubBox(-8f, 168f, 0f, 3.2f, 9f, 11f, pal.hair)
            ubBox(-2f, 184f, 0f, 3.5f, 4f, 3.5f, pal.hair)
            ubBox(-2f, 180.5f, 0f, 4f, 1f, 4f, pal.accent)
            ubBox(1f, 170f, 0f, 11f, 2.2f, 11f, pal.band)
            ubBox(11.6f, 166f, 4.5f, 0.8f, 1.8f, 1.6f, eyeColor)
            ubBox(11.6f, 166f, -4.5f, 0.8f, 1.8f, 1.6f, eyeColor)
            ubBox(11.5f, 159f, 0f, 0.7f, 1.1f, 5.5f, pal.hair)
            ubBox(1.5f, 126f, 0f, 8.7f, 16f, 16.4f, pal.armor)
            ubBox(0f, 147f, 21f, 7f, 3f, 8f, pal.armor)
            ubBox(0f, 147f, -21f, 7f, 3f, 8f, pal.armor)
            ubBox(0f, 100f, 0f, 9.8f, 3.5f, 16.8f, pal.accent)
        }

        // Brazos: hombro, codo y puño
        arm(uB, rB, -21f, pal)
        arm(uA, rA, 21f, pal)
    }

    fun renderPortrait(d: DrawScope, index: Int, time: Float) {
        with(d) {
            midX = size.width / 2f
            midY = size.height * 0.56f
            focal = size.height * 3.2f
            portrait.x = 0f
            portrait.facing = 1
            shadow(portrait)
            drawFighter(portrait, pal(index), time)
            flush()
        }
    }

    fun render(d: DrawScope, m: GameModel) {
        with(d) {
            val w = size.width
            val h = size.height
            midX = w / 2f
            midY = h * 0.5f
            focal = min(h * 1.7f, w * 0.8f)
            val time = m.tick * 0.016f

            drawBackdrop(w, h)

            // Escenario de madera
            fillQuad(-450f, -270f, 450f, 270f, Color(0xFF4E342E))
            fillQuad(-380f, -200f, 380f, 200f, Color(0xFFB08968))
            var gx = -380f
            while (gx <= 380f) {
                proj(gx, 0f, -200f)
                val ax = scrX
                val ay = scrY
                proj(gx, 0f, 200f)
                drawLine(Color(0x33000000), Offset(ax, ay), Offset(scrX, scrY), 1.5f)
                gx += 95f
            }
            var gz = -200f
            while (gz <= 200f) {
                proj(-380f, 0f, gz)
                val ax = scrX
                val ay = scrY
                proj(380f, 0f, gz)
                drawLine(Color(0x33000000), Offset(ax, ay), Offset(scrX, scrY), 1.5f)
                gz += 100f
            }

            // Postes, cuerdas y farolillos
            curF = 1f; curOx = 0f; curOy = 0f; curOz = 0f; flash = false
            for (a in sides) for (b in sides) {
                addBox(a * 380f, 65f, b * 200f, 5f, 65f, 5f, 0f, postColor)
            }
            for (hgt in ropeH) {
                addBox(0f, hgt, -200f, 380f, 2f, 2f, 0f, ropeColor)
                for (a in sides) addBox(a * 380f, hgt, 0f, 2f, 2f, 200f, 0f, ropeColor)
            }
            for (a in sides) addBox(a * 380f, 138f, -200f, 7f, 9f, 7f, 0f, lanternColor)
            flush()

            // Sombras y luchadores
            shadow(m.player)
            shadow(m.enemy)
            drawFighter(m.player, pal(m.playerChar), time)
            drawFighter(m.enemy, pal(1 - m.playerChar), time + 1.3f)
            flush()

            // Barras de vida
            val bw = w * 0.38f
            val bh = h * 0.045f
            val top = h * 0.04f
            drawRect(Color(0xFF263238), Offset(w * 0.03f, top), Size(bw, bh))
            drawRect(Color(0xFF43A047), Offset(w * 0.03f, top), Size(bw * m.player.hp / 100f, bh))
            val ew = bw * m.enemy.hp / 100f
            drawRect(Color(0xFF263238), Offset(w * 0.97f - bw, top), Size(bw, bh))
            drawRect(Color(0xFFE53935), Offset(w * 0.97f - ew, top), Size(ew, bh))
        }
    }
}
