package com.arnav.island.coverscreen.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import com.arnav.island.coverscreen.CoverPetBreed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Interactive 3D volumetric puppy renderer (60–120Hz) for the Z Flip 5 Cover Screen,
 * inspired by Huawei Pura X's interactive cover-screen pets.
 *
 * Supports:
 * - True perspective 3D depth-sorted spheres/ellipsoids with spherical lighting & specular highlights
 * - Petting/stroking (`onTouch`) -> leans head into finger, rapid tail wag, happy `^ ^` eyes, rising hearts
 * - Feeding 3D bone (`feedBone()`) -> bone arcs onto floor, puppy trots & munches, +Hunger/+Happiness
 * - Throwing 3D ball (`playBall()`) -> tennis ball bounces across stage, puppy chases & leaps
 * - Phone shake (`triggerShake()`) -> dizzy wobble, swirling stars & ear flop
 */
class CoverPet3DRenderer(private val density: Float) {

    enum class PetState {
        IDLE,
        PETTING,
        FEEDING,
        PLAYING_BALL,
        DIZZY_SHAKE,
        SLEEPY,
    }

    var breed: CoverPetBreed = CoverPetBreed.SHIBA
    var petName: String = "Breeze Pup"
    var hunger: Float = 0.82f
        private set
    var happiness: Float = 0.88f
        private set

    var state: PetState = PetState.IDLE
        private set

    private var stateSinceMs = SystemClock.elapsedRealtime()
    private var lastInteractMs = SystemClock.elapsedRealtime()
    private var petPosX = 0f
    private var targetPosX = 0f
    private var petPosY = 0f
    private var headLookX = 0f
    private var headLookY = 0f
    private var targetLookX = 0f
    private var targetLookY = 0f
    private var bounceVelY = 0f
    private var statusToast: String? = null
    private var statusToastUntilMs = 0L

    // Interactive props
    private var propStartMs = 0L
    private var propX = 0f
    private var propY = 0f

    // Floating particles (hearts, stars, crumbs, music/bark sparks)
    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var size: Float,
        var color: Int,
        var kind: Int, // 0 = heart, 1 = star, 2 = crumb
        var life: Float,
        var maxLife: Float,
    )

    private val particles = ArrayList<Particle>(24)

    // Pre-allocated 3D draw list to avoid allocations per frame
    private data class Prim3D(
        var z: Float = 0f,
        var sx: Float = 0f,
        var sy: Float = 0f,
        var rx: Float = 0f,
        var ry: Float = 0f,
        var rotDeg: Float = 0f,
        var color: Int = 0,
        var highlightColor: Int = 0,
        var shadowColor: Int = 0,
        var kind: Int = 0, // 0 = shaded ellipsoid, 1 = eye/feature, 2 = collar ring
        var eyeMode: Int = 0, // 0 = normal, 1 = happy arc ^, 2 = dizzy @, 3 = sleepy -, 4 = shiny sparkle
    )

    private val drawList = ArrayList<Prim3D>(48).apply {
        repeat(48) { add(Prim3D()) }
    }
    private var primCount = 0

    // Interactive dock button rects (in Canvas coordinates)
    val hitBounds = RectF()
    val feedButtonRect = RectF()
    val ballButtonRect = RectF()
    val petButtonRect = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isFakeBoldText = true
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
    }
    private val tmpRect = RectF()
    private val tmpPath = Path()

    private var stageLeft = 0f
    private var stageTop = 0f
    private var stageW = 1f
    private var stageH = 1f
    private var isDraggingPet = false

    private fun dp(v: Float) = v * density

    fun onPetTapOrStroke(x: Float, y: Float, isDrag: Boolean) {
        val now = SystemClock.elapsedRealtime()
        lastInteractMs = now
        val cx = stageLeft + stageW * 0.5f
        val cy = stageTop + stageH * 0.50f
        targetLookX = ((x - cx) / (stageW * 0.45f)).coerceIn(-1f, 1f)
        targetLookY = ((y - cy) / (stageH * 0.45f)).coerceIn(-1f, 1f)

        if (isDrag || hitBounds.contains(x, y)) {
            state = PetState.PETTING
            stateSinceMs = now
            happiness = (happiness + if (isDrag) 0.008f else 0.04f).coerceIn(0f, 1f)
            if (!isDrag || Random.nextFloat() < 0.25f) {
                spawnParticle(
                    x = cx + petPosX + Random.nextFloat() * dp(44f) - dp(22f),
                    y = cy - dp(40f) + Random.nextFloat() * dp(18f),
                    vx = (Random.nextFloat() - 0.5f) * dp(26f),
                    vy = -dp(38f) - Random.nextFloat() * dp(22f),
                    size = dp(8f) + Random.nextFloat() * dp(4f),
                    color = 0xFFFF5E99.toInt(),
                    kind = 0,
                    maxLife = 1.25f,
                )
            }
            if (!isDrag) {
                bounceVelY = -dp(95f)
                showToast("Yêu bạn quá! ♥")
            }
        }
    }

    fun onTouchRelease() {
        isDraggingPet = false
    }

    fun feedBone() {
        val now = SystemClock.elapsedRealtime()
        lastInteractMs = now
        state = PetState.FEEDING
        stateSinceMs = now
        propStartMs = now
        propX = if (Random.nextBoolean()) dp(38f) else -dp(38f)
        propY = dp(42f)
        hunger = (hunger + 0.16f).coerceIn(0f, 1f)
        happiness = (happiness + 0.08f).coerceIn(0f, 1f)
        showToast("Măm măm! Ngon quá 🦴")
    }

    fun playBall() {
        val now = SystemClock.elapsedRealtime()
        lastInteractMs = now
        state = PetState.PLAYING_BALL
        stateSinceMs = now
        propStartMs = now
        propX = -dp(58f)
        propY = dp(44f)
        happiness = (happiness + 0.14f).coerceIn(0f, 1f)
        hunger = (hunger - 0.03f).coerceIn(0.05f, 1f)
        bounceVelY = -dp(140f)
        showToast("Gâu gâu! Bắt bóng nào 🎾")
    }

    fun triggerShake() {
        val now = SystemClock.elapsedRealtime()
        lastInteractMs = now
        state = PetState.DIZZY_SHAKE
        stateSinceMs = now
        bounceVelY = -dp(115f)
        showToast("Ủa trời đất quay cuồng 😵")
        val cx = stageLeft + stageW * 0.5f + petPosX
        val cy = stageTop + stageH * 0.42f
        repeat(6) { i ->
            val ang = i * (PI.toFloat() * 2f / 6f)
            spawnParticle(
                x = cx + cos(ang) * dp(28f),
                y = cy - dp(30f) + sin(ang) * dp(12f),
                vx = cos(ang) * dp(22f),
                vy = -dp(18f) + sin(ang) * dp(12f),
                size = dp(6.5f),
                color = 0xFFFFD54A.toInt(),
                kind = 1,
                maxLife = 1.4f,
            )
        }
    }

    fun handleTap(x: Float, y: Float): Boolean {
        if (feedButtonRect.contains(x, y)) {
            feedBone()
            return true
        }
        if (ballButtonRect.contains(x, y)) {
            playBall()
            return true
        }
        if (petButtonRect.contains(x, y) || hitBounds.contains(x, y)) {
            onPetTapOrStroke(x, y, isDrag = false)
            return true
        }
        return false
    }

    fun step(dt: Float) {
        val safeDt = dt.coerceIn(0.001f, 0.05f)
        val now = SystemClock.elapsedRealtime()
        val inStateMs = now - stateSinceMs
        val idleMs = now - lastInteractMs

        when (state) {
            PetState.PETTING -> {
                targetPosX = 0f
                if (inStateMs > 2_400L) state = PetState.IDLE
            }
            PetState.FEEDING -> {
                val p = (inStateMs / 2_600f).coerceIn(0f, 1f)
                targetPosX = if (p < 0.72f) propX * 0.45f else 0f
                targetLookX = (propX / dp(60f)).coerceIn(-1f, 1f)
                targetLookY = 0.55f
                if (inStateMs in 700L..1800L && Random.nextFloat() < 0.22f) {
                    spawnParticle(
                        x = stageLeft + stageW * 0.5f + propX * 0.6f,
                        y = stageTop + stageH * 0.58f,
                        vx = (Random.nextFloat() - 0.5f) * dp(35f),
                        vy = -dp(25f) - Random.nextFloat() * dp(20f),
                        size = dp(3.5f),
                        color = 0xFFFFF59D.toInt(),
                        kind = 2,
                        maxLife = 0.7f,
                    )
                }
                if (inStateMs > 2_800L) {
                    state = PetState.IDLE
                    targetLookX = 0f
                    targetLookY = 0f
                }
            }
            PetState.PLAYING_BALL -> {
                val p = (inStateMs / 3_200f).coerceIn(0f, 1f)
                val ballX = sin(p * PI.toFloat() * 3f) * dp(64f)
                targetPosX = ballX * 0.48f
                targetLookX = (ballX / dp(64f)).coerceIn(-1f, 1f)
                targetLookY = 0.35f
                if (inStateMs > 3_400L) {
                    state = PetState.IDLE
                    targetPosX = 0f
                    targetLookX = 0f
                    targetLookY = 0f
                }
            }
            PetState.DIZZY_SHAKE -> {
                targetPosX = sin((inStateMs / 1000f) * 26f) * dp(12f)
                if (inStateMs > 2_400L) {
                    state = PetState.IDLE
                    targetPosX = 0f
                }
            }
            PetState.IDLE -> {
                val wander = sin((now % 18_000L) / 18_000f * PI.toFloat() * 2f)
                targetPosX = wander * dp(14f)
                targetLookX = cos((now % 9_000L) / 9_000f * PI.toFloat() * 2f) * 0.35f
                targetLookY = sin((now % 7_000L) / 7_000f * PI.toFloat() * 2f) * 0.15f
                if (idleMs > 30_000L) {
                    state = PetState.SLEEPY
                }
            }
            PetState.SLEEPY -> {
                targetPosX = 0f
                targetLookX = 0f
                targetLookY = 0.35f
            }
        }

        // Smooth springs for head, body position & bounce
        petPosX += (targetPosX - petPosX) * min(1f, safeDt * 7f)
        headLookX += (targetLookX - headLookX) * min(1f, safeDt * 8f)
        headLookY += (targetLookY - headLookY) * min(1f, safeDt * 8f)

        bounceVelY += dp(460f) * safeDt
        petPosY += bounceVelY * safeDt
        if (petPosY > 0f) {
            petPosY = 0f
            bounceVelY = 0f
        }

        // Update particles
        var i = particles.size - 1
        while (i >= 0) {
            val pt = particles[i]
            pt.life += safeDt
            if (pt.life >= pt.maxLife) {
                particles.removeAt(i)
            } else {
                pt.x += pt.vx * safeDt
                pt.y += pt.vy * safeDt
                if (pt.kind == 2) pt.vy += dp(90f) * safeDt
            }
            i--
        }
    }

    fun draw(canvas: Canvas, bounds: RectF, showBottomDock: Boolean = true) {
        stageLeft = bounds.left
        stageTop = bounds.top
        stageW = bounds.width().coerceAtLeast(1f)
        stageH = bounds.height().coerceAtLeast(1f)

        val now = SystemClock.elapsedRealtime()
        val t = (now % 120_000L) / 1000f
        val unit = (min(stageW, stageH) / 220f).coerceIn(0.65f, 1.8f)

        // 1. Ambient floor & 3D soft spotlight stage
        val floorY = bounds.top + stageH * if (showBottomDock) 0.65f else 0.72f
        val centerX = bounds.centerX() + petPosX
        val centerY = floorY - dp(38f) * unit + petPosY

        hitBounds.set(
            centerX - dp(68f) * unit,
            centerY - dp(62f) * unit,
            centerX + dp(68f) * unit,
            floorY + dp(26f) * unit,
        )

        // Glowing 3D floor pedestal ring
        val stageR = dp(78f) * unit
        fillPaint.shader = RadialGradient(
            bounds.centerX(), floorY + dp(8f) * unit, stageR,
            intArrayOf(0x385AC8FA.toInt(), 0x167C4DFF, 0x00000000),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawOval(
            bounds.centerX() - stageR,
            floorY - dp(12f) * unit,
            bounds.centerX() + stageR,
            floorY + dp(28f) * unit,
            fillPaint,
        )
        fillPaint.shader = null

        // Shadow directly under the puppy (shrinks when jumping)
        val jumpScale = (1f - abs(petPosY) / (dp(65f) * unit)).coerceIn(0.55f, 1f)
        fillPaint.color = 0xAA000000.toInt()
        canvas.drawOval(
            centerX - dp(40f) * unit * jumpScale,
            floorY + dp(2f) * unit,
            centerX + dp(40f) * unit * jumpScale,
            floorY + dp(18f) * unit * jumpScale,
            fillPaint,
        )

        // 2. Top-left pet name + mood stats bars
        if (showBottomDock) {
            drawStatsHeader(canvas, bounds, unit)
        }

        // 3. Build & sort 3D primitives for the volumetric puppy
        buildPuppy3D(now, t, centerX, centerY, unit)
        renderSorted3D(canvas, unit, t)

        // 4. Interactive props (thrown bone or bouncing tennis ball)
        if (state == PetState.FEEDING) {
            drawBone3D(canvas, bounds.centerX(), floorY, now - propStartMs, unit)
        } else if (state == PetState.PLAYING_BALL) {
            drawBall3D(canvas, bounds.centerX(), floorY, now - propStartMs, unit)
        }

        // 5. Particles (hearts, stars, crumbs)
        drawParticles(canvas)

        // 6. Status speech bubble
        if (now < statusToastUntilMs && !statusToast.isNullOrBlank()) {
            drawSpeechBubble(canvas, centerX, centerY - dp(56f) * unit, statusToast!!, unit)
        } else if (state == PetState.SLEEPY) {
            drawSleepyZzz(canvas, centerX + dp(28f) * unit, centerY - dp(42f) * unit, t, unit)
        }

        // 7. Bottom interactive dock (Feed, Play Ball, Pet)
        if (showBottomDock) {
            drawBottomDock(canvas, bounds, unit)
        }
    }

    private fun buildPuppy3D(now: Long, t: Float, cx: Float, cy: Float, u: Float) {
        primCount = 0
        val mainC = breed.primaryColor
        val creamC = breed.secondaryColor
        val earC = breed.earColor
        val collarC = breed.collarColor

        val wagSpeed = when (state) {
            PetState.PETTING, PetState.FEEDING, PetState.PLAYING_BALL -> 16f
            PetState.SLEEPY -> 2.2f
            else -> 6.5f
        }
        val wagAmp = when (state) {
            PetState.PETTING, PetState.PLAYING_BALL -> 18f
            PetState.SLEEPY -> 4f
            else -> 11f
        }
        val wagX = sin(t * wagSpeed) * dp(wagAmp) * u

        val yaw = when (state) {
            PetState.DIZZY_SHAKE -> sin(t * 24f) * 0.48f
            else -> headLookX * 0.45f
        }
        val pitch = when (state) {
            PetState.FEEDING -> 0.28f + 0.14f * abs(sin(t * 10f))
            PetState.SLEEPY -> 0.25f + 0.06f * sin(t * 2f)
            else -> headLookY * 0.28f
        }
        val breathe = 1f + 0.035f * sin(t * 3.1f)
        val hopOffset = if (state == PetState.PLAYING_BALL) -abs(sin(t * 8f)) * dp(6f) * u else 0f

        // Projection helper: maps local 3D (lx, ly, lz) -> screen (sx, sy, scale)
        val focal = dp(260f) * u
        fun proj(lx: Float, ly: Float, lz: Float, rx: Float, ry: Float, rotDeg: Float, col: Int, kind: Int = 0, eyeMode: Int = 0) {
            if (primCount >= drawList.size) drawList.add(Prim3D())
            val p = drawList[primCount++]
            // Slight 3D body yaw rotation
            val cosY = cos(yaw * 0.35f)
            val sinY = sin(yaw * 0.35f)
            val rx3 = lx * cosY + lz * sinY
            val rz3 = -lx * sinY + lz * cosY
            val depthScale = (focal / (focal + rz3)).coerceIn(0.68f, 1.35f)

            p.z = rz3
            p.sx = cx + rx3 * depthScale
            p.sy = cy + hopOffset + ly * depthScale
            p.rx = rx * depthScale
            p.ry = ry * depthScale
            p.rotDeg = rotDeg
            p.color = col
            p.highlightColor = liftColor(col, 0.38f)
            p.shadowColor = darkenColor(col, 0.42f)
            p.kind = kind
            p.eyeMode = eyeMode
        }

        // Tail 3D (segmented behind torso, z > 0)
        proj(
            lx = dp(-18f) * u + wagX,
            ly = dp(12f) * u - abs(wagX) * 0.22f,
            lz = dp(26f) * u,
            rx = dp(7.5f) * u,
            ry = dp(14f) * u,
            rotDeg = (wagX / dp(1f)) * 1.4f - 20f,
            col = mainC,
        )
        proj(
            lx = dp(-18f) * u + wagX * 1.35f,
            ly = dp(4f) * u - abs(wagX) * 0.35f,
            lz = dp(24f) * u,
            rx = dp(6f) * u,
            ry = dp(6.5f) * u,
            rotDeg = 0f,
            col = creamC,
        )

        // Back legs (z = +10)
        proj(dp(-17f) * u, dp(29f) * u, dp(10f) * u, dp(8.5f) * u, dp(11f) * u, 0f, mainC)
        proj(dp(17f) * u, dp(29f) * u, dp(10f) * u, dp(8.5f) * u, dp(11f) * u, 0f, mainC)

        // Main Torso 3D (z = +8)
        proj(0f, dp(18f) * u, dp(8f) * u, dp(23f) * u * breathe, dp(21f) * u, 0f, mainC)
        // Fluffy cream belly/chest patch (z = -6)
        proj(0f, dp(17f) * u, dp(-8f) * u, dp(16f) * u * breathe, dp(15f) * u, 0f, creamC)

        // Front legs + white paws (z = -12)
        val stepL = if (state == PetState.PLAYING_BALL) sin(t * 14f) * dp(4f) * u else 0f
        val stepR = -stepL
        proj(dp(-12f) * u, dp(31f) * u + stepL, dp(-11f) * u, dp(7.2f) * u, dp(11f) * u, 0f, mainC)
        proj(dp(12f) * u, dp(31f) * u + stepR, dp(-11f) * u, dp(7.2f) * u, dp(11f) * u, 0f, mainC)
        // Paws
        proj(dp(-12f) * u, dp(40f) * u + stepL, dp(-14f) * u, dp(7.4f) * u, dp(4.5f) * u, 0f, creamC)
        proj(dp(12f) * u, dp(40f) * u + stepR, dp(-14f) * u, dp(7.4f) * u, dp(4.5f) * u, 0f, creamC)

        // Collar & Gold Bell (z = -15)
        proj(0f, dp(2f) * u, dp(-14f) * u, dp(16f) * u, dp(5.2f) * u, 0f, collarC)
        proj(0f, dp(7f) * u, dp(-20f) * u, dp(4.5f) * u, dp(4.5f) * u, 0f, 0xFFFFD54A.toInt())

        // Head coordinates rotated by 3D yaw & pitch
        val headX = sin(yaw) * dp(6f) * u
        val headY = dp(-18f) * u + sin(pitch) * dp(5f) * u
        val headZ = dp(-14f) * u
        val headTilt = when (state) {
            PetState.DIZZY_SHAKE -> sin(t * 28f) * 18f
            PetState.PETTING -> headLookX * 14f + sin(t * 6f) * 5f
            else -> headLookX * 9f
        }

        // Ears 3D (bounce with spring & tilt)
        val earFlop = abs(sin(t * 8f)) * if (state == PetState.PETTING) dp(3f) * u else dp(0.6f) * u
        proj(headX - dp(21f) * u, headY - dp(16f) * u + earFlop, headZ + dp(3f) * u, dp(9f) * u, dp(15f) * u, -24f + headTilt * 0.4f, earC)
        proj(headX + dp(21f) * u, headY - dp(16f) * u + earFlop, headZ + dp(3f) * u, dp(9f) * u, dp(15f) * u, 24f + headTilt * 0.4f, earC)

        // Main Cranium Sphere 3D
        proj(headX, headY, headZ, dp(25.5f) * u, dp(22.5f) * u, headTilt * 0.25f, mainC)

        // Fluffy Cheek Highlights
        val faceShiftX = sin(yaw) * dp(12f) * u
        val faceShiftY = sin(pitch) * dp(7f) * u
        proj(headX + faceShiftX - dp(13f) * u, headY + faceShiftY + dp(4f) * u, headZ - dp(16f) * u, dp(10.5f) * u, dp(8.5f) * u, 0f, creamC)
        proj(headX + faceShiftX + dp(13f) * u, headY + faceShiftY + dp(4f) * u, headZ - dp(16f) * u, dp(10.5f) * u, dp(8.5f) * u, 0f, creamC)

        // 3D Snout / Muzzle protruding forward (z = -24)
        proj(headX + faceShiftX * 1.15f, headY + faceShiftY + dp(6f) * u, headZ - dp(22f) * u, dp(12f) * u, dp(9.5f) * u, 0f, creamC)

        // Cute pink tongue when happy/panting
        if (state == PetState.PETTING || state == PetState.PLAYING_BALL || state == PetState.FEEDING) {
            val pant = abs(sin(t * 14f)) * dp(2f) * u
            proj(headX + faceShiftX * 1.2f, headY + faceShiftY + dp(13.5f) * u + pant * 0.4f, headZ - dp(24f) * u, dp(4.5f) * u, dp(5.5f) * u + pant * 0.3f, 0f, 0xFFFF6B9D.toInt())
        }

        // Shiny 3D Nose (z = -29)
        proj(headX + faceShiftX * 1.25f, headY + faceShiftY + dp(2.5f) * u, headZ - dp(29f) * u, dp(5.0f) * u, dp(3.6f) * u, 0f, 0xFF1F2329.toInt())

        // 3D Eyes (blink, happy ^ ^, dizzy @ @, sleepy - -)
        val blinkPhase = t % 3.4f
        val isBlinking = blinkPhase in 0.15f..0.28f
        val eyeMode = when {
            state == PetState.DIZZY_SHAKE -> 2
            state == PetState.SLEEPY -> 3
            state == PetState.PETTING || state == PetState.FEEDING -> 1
            isBlinking -> 3
            else -> 0
        }
        val eyeColor = if (breed == CoverPetBreed.CYBER) 0xFF00F0FF.toInt() else 0xFF181B22.toInt()
        proj(headX + faceShiftX - dp(10.5f) * u, headY + faceShiftY - dp(3.5f) * u, headZ - dp(23.5f) * u, dp(4.6f) * u, dp(5.2f) * u, headTilt * 0.3f, eyeColor, kind = 1, eyeMode = eyeMode)
        proj(headX + faceShiftX + dp(10.5f) * u, headY + faceShiftY - dp(3.5f) * u, headZ - dp(23.5f) * u, dp(4.6f) * u, dp(5.2f) * u, headTilt * 0.3f, eyeColor, kind = 1, eyeMode = eyeMode)
    }

    private fun renderSorted3D(canvas: Canvas, u: Float, t: Float) {
        // Simple insertion sort by Z descending (back-to-front painter's algorithm)
        for (i in 1 until primCount) {
            val key = drawList[i]
            var j = i - 1
            while (j >= 0 && drawList[j].z < key.z) {
                drawList[j + 1] = drawList[j]
                j--
            }
            drawList[j + 1] = key
        }

        for (i in 0 until primCount) {
            val p = drawList[i]
            canvas.save()
            canvas.translate(p.sx, p.sy)
            if (p.rotDeg != 0f) canvas.rotate(p.rotDeg)

            if (p.kind == 1) {
                drawEyeFeature(canvas, p, u, t)
            } else {
                val maxR = max(p.rx, p.ry).coerceAtLeast(1f)
                fillPaint.shader = RadialGradient(
                    -p.rx * 0.34f,
                    -p.ry * 0.38f,
                    maxR * 1.45f,
                    intArrayOf(p.highlightColor, p.color, p.shadowColor),
                    floatArrayOf(0f, 0.48f, 1f),
                    Shader.TileMode.CLAMP,
                )
                tmpRect.set(-p.rx, -p.ry, p.rx, p.ry)
                canvas.drawOval(tmpRect, fillPaint)
                fillPaint.shader = null
            }
            canvas.restore()
        }
    }

    private fun drawEyeFeature(canvas: Canvas, p: Prim3D, u: Float, t: Float) {
        when (p.eyeMode) {
            1 -> {
                // Happy ^ eye arc
                strokePaint.color = p.color
                strokePaint.strokeWidth = dp(2.4f) * u
                tmpPath.rewind()
                tmpPath.moveTo(-p.rx, p.ry * 0.25f)
                tmpPath.quadTo(0f, -p.ry * 0.9f, p.rx, p.ry * 0.25f)
                canvas.drawPath(tmpPath, strokePaint)
            }
            2 -> {
                // Dizzy rotating spiral eye
                strokePaint.color = p.color
                strokePaint.strokeWidth = dp(1.8f) * u
                canvas.save()
                canvas.rotate((t * 480f) % 360f)
                canvas.drawCircle(0f, 0f, p.rx * 0.85f, strokePaint)
                canvas.drawLine(-p.rx * 0.55f, 0f, p.rx * 0.55f, 0f, strokePaint)
                canvas.drawLine(0f, -p.rx * 0.55f, 0f, p.rx * 0.55f, strokePaint)
                canvas.restore()
            }
            3 -> {
                // Closed sleepy / blinking line
                strokePaint.color = p.color
                strokePaint.strokeWidth = dp(2.2f) * u
                canvas.drawLine(-p.rx * 0.9f, 0f, p.rx * 0.9f, 0f, strokePaint)
            }
            else -> {
                // Glossy 3D orb eye with white specular catchlight
                fillPaint.color = p.color
                canvas.drawOval(-p.rx, -p.ry, p.rx, p.ry, fillPaint)
                fillPaint.color = Color.WHITE
                canvas.drawCircle(-p.rx * 0.32f, -p.ry * 0.35f, p.rx * 0.36f, fillPaint)
                fillPaint.color = 0xAAFFFFFF.toInt()
                canvas.drawCircle(p.rx * 0.28f, p.ry * 0.25f, p.rx * 0.16f, fillPaint)
            }
        }
    }

    private fun drawBone3D(canvas: Canvas, cx: Float, floorY: Float, elapsedMs: Long, u: Float) {
        val t = (elapsedMs / 1000f).coerceIn(0f, 2.8f)
        val arcP = (t / 0.55f).coerceIn(0f, 1f)
        val bx = cx + propX * arcP
        val bounce = sin(arcP * PI.toFloat()) * dp(52f) * u
        val by = floorY - bounce + dp(4f) * u
        val munchScale = if (elapsedMs > 700L) (1f - ((elapsedMs - 700L) / 1400f).coerceIn(0f, 0.85f)) else 1f
        if (munchScale <= 0.15f) return

        canvas.save()
        canvas.translate(bx, by)
        canvas.scale(munchScale, munchScale)
        canvas.rotate(18f * (1f - arcP))
        val bw = dp(13f) * u
        val bh = dp(4.5f) * u
        fillPaint.color = 0xFFFFF8E1.toInt()
        canvas.drawRoundRect(-bw, -bh, bw, bh, bh, bh, fillPaint)
        canvas.drawCircle(-bw, -bh * 0.7f, dp(4.5f) * u, fillPaint)
        canvas.drawCircle(-bw, bh * 0.7f, dp(4.5f) * u, fillPaint)
        canvas.drawCircle(bw, -bh * 0.7f, dp(4.5f) * u, fillPaint)
        canvas.drawCircle(bw, bh * 0.7f, dp(4.5f) * u, fillPaint)
        canvas.restore()
    }

    private fun drawBall3D(canvas: Canvas, cx: Float, floorY: Float, elapsedMs: Long, u: Float) {
        val p = (elapsedMs / 3_200f).coerceIn(0f, 1f)
        if (p >= 1f) return
        val bx = cx + sin(p * PI.toFloat() * 3f) * dp(64f) * u
        val bounceH = abs(sin(p * PI.toFloat() * 5f)) * dp(46f) * u
        val by = floorY - bounceH - dp(8f) * u
        val r = dp(11f) * u

        fillPaint.color = 0x55000000
        canvas.drawOval(bx - r * 0.9f, floorY + dp(2f) * u, bx + r * 0.9f, floorY + dp(8f) * u, fillPaint)

        fillPaint.shader = RadialGradient(
            bx - r * 0.35f, by - r * 0.35f, r * 1.4f,
            intArrayOf(0xFFE6FF6E.toInt(), 0xFFAEEA00.toInt(), 0xFF64DD17.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(bx, by, r, fillPaint)
        fillPaint.shader = null
    }

    private fun drawStatsHeader(canvas: Canvas, b: RectF, u: Float) {
        val x = b.left + dp(14f)
        val y = b.top + dp(12f)

        textPaint.textSize = dp(13f) * u.coerceAtLeast(0.9f)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(petName, x, y + dp(12f), textPaint)

        // Hunger & Happiness mini bars
        val barW = dp(52f)
        val barH = dp(4.5f)
        drawMiniMeter(canvas, x, y + dp(20f), barW, barH, hunger, 0xFFFFB74D.toInt(), "🦴")
        drawMiniMeter(canvas, x, y + dp(31f), barW, barH, happiness, 0xFFFF6B9D.toInt(), "♥")
    }

    private fun drawMiniMeter(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, col: Int, label: String) {
        subTextPaint.textSize = dp(9f)
        subTextPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, x, y + h, subTextPaint)
        val bx = x + dp(13f)
        fillPaint.color = 0x2AFFFFFF
        canvas.drawRoundRect(bx, y, bx + w, y + h, h / 2f, h / 2f, fillPaint)
        fillPaint.color = col
        canvas.drawRoundRect(bx, y, bx + w * frac.coerceIn(0.05f, 1f), y + h, h / 2f, h / 2f, fillPaint)
    }

    private fun drawBottomDock(canvas: Canvas, b: RectF, u: Float) {
        val dockY = b.bottom - dp(36f)
        val btnW = min(dp(84f), (b.width() - dp(36f)) / 3f)
        val btnH = dp(32f)
        val gap = dp(8f)
        val totalW = btnW * 3f + gap * 2f
        val startX = b.centerX() - totalW / 2f

        feedButtonRect.set(startX, dockY - btnH / 2f, startX + btnW, dockY + btnH / 2f)
        ballButtonRect.set(startX + btnW + gap, dockY - btnH / 2f, startX + btnW * 2f + gap, dockY + btnH / 2f)
        petButtonRect.set(startX + (btnW + gap) * 2f, dockY - btnH / 2f, startX + totalW, dockY + btnH / 2f)

        drawDockPill(canvas, feedButtonRect, "🦴 Cho ăn", 0x33FFB74D)
        drawDockPill(canvas, ballButtonRect, "🎾 Bóng", 0x3369F0AE)
        drawDockPill(canvas, petButtonRect, "🤚 Vuốt ve", 0x33FF6B9D)
    }

    private fun drawDockPill(canvas: Canvas, r: RectF, label: String, tint: Int) {
        fillPaint.color = tint
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fillPaint)
        strokePaint.color = 0x44FFFFFF
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, strokePaint)

        textPaint.textSize = dp(11.5f)
        textPaint.textAlign = Paint.Align.CENTER
        val baseline = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, r.centerX(), baseline, textPaint)
    }

    private fun drawSpeechBubble(canvas: Canvas, cx: Float, y: Float, text: String, u: Float) {
        textPaint.textSize = dp(11.5f) * u.coerceIn(0.85f, 1.15f)
        textPaint.textAlign = Paint.Align.CENTER
        val tw = textPaint.measureText(text)
        val bw = tw + dp(20f)
        val bh = dp(24f)
        tmpRect.set(cx - bw / 2f, y - bh / 2f, cx + bw / 2f, y + bh / 2f)
        fillPaint.color = 0xEB22252E.toInt()
        canvas.drawRoundRect(tmpRect, bh / 2f, bh / 2f, fillPaint)
        strokePaint.color = 0x55FFFFFF
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(tmpRect, bh / 2f, bh / 2f, strokePaint)
        val baseline = tmpRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, cx, baseline, textPaint)
    }

    private fun drawSleepyZzz(canvas: Canvas, x: Float, y: Float, t: Float, u: Float) {
        textPaint.textSize = dp(13f) * u
        textPaint.textAlign = Paint.Align.LEFT
        val cycle = (t * 0.6f) % 1f
        val oldColor = textPaint.color
        textPaint.color = ((sin(cycle * PI.toFloat()) * 220).toInt().coerceIn(0, 255) shl 24) or 0x90CAF9
        canvas.drawText("Zzz", x + cycle * dp(12f), y - cycle * dp(18f), textPaint)
        textPaint.color = oldColor
    }

    private fun drawParticles(canvas: Canvas) {
        for (pt in particles) {
            val fade = (1f - pt.life / pt.maxLife).coerceIn(0f, 1f)
            val a = (255 * fade).toInt().coerceIn(0, 255)
            fillPaint.color = (a shl 24) or (pt.color and 0x00FFFFFF)
            when (pt.kind) {
                0 -> {
                    val s = pt.size * 0.55f
                    tmpPath.rewind()
                    tmpPath.moveTo(pt.x, pt.y + s * 0.65f)
                    tmpPath.cubicTo(pt.x - s * 1.2f, pt.y - s * 0.1f, pt.x - s * 0.65f, pt.y - s * 0.95f, pt.x, pt.y - s * 0.3f)
                    tmpPath.cubicTo(pt.x + s * 0.65f, pt.y - s * 0.95f, pt.x + s * 1.2f, pt.y - s * 0.1f, pt.x, pt.y + s * 0.65f)
                    tmpPath.close()
                    canvas.drawPath(tmpPath, fillPaint)
                }
                1 -> {
                    val r = pt.size
                    val inner = r * 0.28f
                    tmpPath.rewind()
                    tmpPath.moveTo(pt.x, pt.y - r)
                    tmpPath.quadTo(pt.x + inner, pt.y - inner, pt.x + r, pt.y)
                    tmpPath.quadTo(pt.x + inner, pt.y + inner, pt.x, pt.y + r)
                    tmpPath.quadTo(pt.x - inner, pt.y + inner, pt.x - r, pt.y)
                    tmpPath.quadTo(pt.x - inner, pt.y - inner, pt.x, pt.y - r)
                    tmpPath.close()
                    canvas.drawPath(tmpPath, fillPaint)
                }
                else -> canvas.drawCircle(pt.x, pt.y, pt.size, fillPaint)
            }
        }
    }

    private fun spawnParticle(x: Float, y: Float, vx: Float, vy: Float, size: Float, color: Int, kind: Int, maxLife: Float) {
        if (particles.size >= 24) particles.removeAt(0)
        particles.add(Particle(x, y, vx, vy, size, color, kind, 0f, maxLife))
    }

    private fun showToast(msg: String) {
        statusToast = msg
        statusToastUntilMs = SystemClock.elapsedRealtime() + 2_200L
    }

    private fun liftColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * factor).toInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b)
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        val r = (Color.red(color) * (1f - factor)).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * (1f - factor)).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * (1f - factor)).toInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b)
    }
}
