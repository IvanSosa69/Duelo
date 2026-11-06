package com.example.tapgame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlin.math.abs
import kotlin.random.Random

private const val WORLD_W = 800f
private const val WORLD_H = 400f
private const val GROUND = 340f

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

@Composable
fun App() {
    val nav = rememberNavController()
    var playerWon by remember { mutableStateOf(false) }
    NavHost(nav, startDestination = "menu") {
        composable("menu") {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text("DUELO", style = MaterialTheme.typography.displayMedium)
                Text("Mejor de 3 rondas")
                Spacer(Modifier.height(24.dp))
                Button(onClick = { nav.navigate("game") }) { Text("Jugar") }
            }
        }
        composable("game") {
            GameScreen { won ->
                playerWon = won
                nav.navigate("over") { popUpTo("menu") }
            }
        }
        composable("over") {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Text(
                    if (playerWon) "¡Ganaste!" else "Perdiste",
                    style = MaterialTheme.typography.displayMedium
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = { nav.navigate("game") { popUpTo("menu") } }) { Text("Revancha") }
                TextButton(onClick = { nav.popBackStack("menu", false) }) { Text("Menú") }
            }
        }
    }
}

class Fighter(val color: Color, startX: Float) {
    var x = startX
    var y = 0f
    var vy = 0f
    var hp = 100f
    var facing = 1
    var moveDir = 0
    var blockInput = false
    var blocking = false
    var stun = 0f
    var attackType = 0
    var attackTimer = 0f
    var attackDur = 0f
    var attackCooldown = 0f
    var hitDone = false

    val onGround get() = y <= 0f

    fun reset(startX: Float) {
        x = startX; y = 0f; vy = 0f; hp = 100f
        moveDir = 0; blockInput = false; blocking = false
        stun = 0f; attackTimer = 0f; attackCooldown = 0f
    }

    fun jump() {
        if (onGround && stun <= 0f && attackTimer <= 0f) vy = 650f
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
        blocking = blockInput && stun <= 0f && attackTimer <= 0f && onGround
        if (stun <= 0f && attackTimer <= 0f && !blocking) x += moveDir * 220f * dt
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
        x = x.coerceIn(40f, 760f)
    }

    private fun tryHit(o: Fighter) {
        val reach = if (attackType == 1) 85f else 105f
        val dmg = if (attackType == 1) 6f else 10f
        val inFront = (o.x - x) * facing > 0f
        if (inFront && abs(o.x - x) < reach && abs(o.y - y) < 70f) {
            if (o.blocking) {
                o.hp = (o.hp - dmg * 0.15f).coerceAtLeast(0f)
                o.x += facing * 8f
            } else {
                o.hp = (o.hp - dmg).coerceAtLeast(0f)
                o.stun = 0.3f
                o.attackTimer = 0f
                o.x += facing * 25f
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
        me.moveDir = 0
        if (foe.attackTimer > 0f && dist < 140f && Random.nextFloat() < 0.5f) {
            me.blockInput = true
            return
        }
        if (dist > 110f) { me.moveDir = toward; return }
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

class GameModel {
    val player = Fighter(Color(0xFF1E88E5), 200f)
    val enemy = Fighter(Color(0xFFE53935), 600f)
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

    private fun resetRound() {
        player.reset(200f)
        enemy.reset(600f)
        clock = 60f
        timeLeft = 60
        pause = 1.2f
    }

    fun step(dt: Float, onOver: (Boolean) -> Unit) {
        if (finished) return
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
            if (pWins >= 2 || eWins >= 2) {
                finished = true
                onOver(pWins >= 2)
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
        val minD = 50f
        val dist = abs(dx)
        if (dist < minD && player.y < 60f && enemy.y < 60f) {
            val push = (minD - dist) / 2f
            val dir = if (dx >= 0f) 1 else -1
            player.x -= dir * push
            enemy.x += dir * push
        }
    }
