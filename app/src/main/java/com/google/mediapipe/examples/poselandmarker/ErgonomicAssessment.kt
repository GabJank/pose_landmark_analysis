package com.google.mediapipe.examples.poselandmarker

data class ErgonomicInputs(
    val repeatedOrStatic: Boolean = false,
    val legsUnsupported: Boolean = false,
    val poorCoupling: Boolean = false,
    val loadKg: Double = 0.0,
    val adjustments: RulaAdjustments = RulaAdjustments()
)

data class RulaAdjustments(
    val leftShoulderRaised: Boolean = false,
    val rightShoulderRaised: Boolean = false,
    val leftArmAbducted: Boolean = false,
    val rightArmAbducted: Boolean = false,
    val leftArmSupported: Boolean = false,
    val rightArmSupported: Boolean = false,
    val leftArmAcrossOrOutside: Boolean = false,
    val rightArmAcrossOrOutside: Boolean = false,
    val leftWristTwisted: Boolean = false,
    val rightWristTwisted: Boolean = false,
    val neckTwisted: Boolean = false,
    val neckSideBending: Boolean = false,
    val trunkTwisted: Boolean = false,
    val trunkSideBending: Boolean = false
)

fun RulaAdjustments.activeLabels(): List<String> = buildList {
    if (leftShoulderRaised) add("ombro E elevado")
    if (rightShoulderRaised) add("ombro D elevado")
    if (leftArmAbducted) add("braço E abduzido")
    if (rightArmAbducted) add("braço D abduzido")
    if (leftArmSupported) add("braço E apoiado")
    if (rightArmSupported) add("braço D apoiado")
    if (leftArmAcrossOrOutside) add("antebraço E fora")
    if (rightArmAcrossOrOutside) add("antebraço D fora")
    if (leftWristTwisted) add("punho E torcido")
    if (rightWristTwisted) add("punho D torcido")
    if (neckTwisted) add("pescoço rotacionado")
    if (neckSideBending) add("pescoço lateral")
    if (trunkTwisted) add("tronco rotacionado")
    if (trunkSideBending) add("tronco lateral")
}

data class ErgonomicScore(val score: Int, val risk: String, val action: String)

data class RulaSideResult(
    val side: String, val upperArm: Int, val lowerArm: Int, val wrist: Int,
    val wristTwist: Int, val postureA: Int, val groupA: Int, val neck: Int,
    val trunk: Int, val legs: Int, val postureB: Int, val groupB: Int,
    val muscleUse: Int, val forceLoad: Int, val final: ErgonomicScore
)

data class RulaResult(
    val left: RulaSideResult?,
    val right: RulaSideResult?,
    val worst: RulaSideResult,
    val legsDetected: Boolean,
    val adjustments: RulaAdjustments,
    val leftEstimatedFromFrontal: Boolean = false,
    val rightEstimatedFromFrontal: Boolean = false
)

data class ErgonomicResult(val rula: RulaResult, val reba: ErgonomicScore)

fun ErgonomicResult.displayText(): String {
    val l = rula.left
    val r = rula.right
    val coverage = if (rula.legsDetected) "pernas detectadas" else "pernas informadas"
    val modifiers = rula.adjustments.activeLabels().ifEmpty { listOf("nenhum ajuste") }.joinToString(", ")
    val estimates = buildList {
        if (rula.leftEstimatedFromFrontal) add("lado E estimado pelas duas vistas")
        if (rula.rightEstimatedFromFrontal) add("lado D estimado pelas duas vistas")
    }
    val estimateText = if (estimates.isEmpty()) "" else estimates.joinToString(", ") + "\n"
    val leftText = l?.let { "E ${it.final.score}" } ?: "E não avaliado"
    val rightText = r?.let { "D ${it.final.score}" } ?: "D não avaliado"
    val leftDetail = l?.let { "E: braço ${it.upperArm}, antebraço ${it.lowerArm}, punho ${it.wrist} → A ${it.groupA}" }
        ?: "E: landmarks insuficientes"
    val rightDetail = r?.let { "D: braço ${it.upperArm}, antebraço ${it.lowerArm}, punho ${it.wrist} → A ${it.groupA}" }
        ?: "D: landmarks insuficientes"
    val w = rula.worst
    val partial = if (l == null || r == null) "; avaliação parcial" else ""
    return "RULA $leftText | $rightText (${w.final.risk}$partial)\n" +
        "$leftDetail\n$rightDetail\n$estimateText" +
        "B: pescoço ${w.neck}, tronco ${w.trunk}, pernas ${w.legs} → B ${w.groupB}\n" +
        "Muscular +${w.muscleUse}; carga +${w.forceLoad}; $coverage\n" +
        "Ajustes: $modifiers\n" +
        "REBA ${reba.score} (${reba.risk})"
}

object ErgonomicAssessment {
    fun calculate(p: PoseAngles, input: ErgonomicInputs = ErgonomicInputs()): ErgonomicResult {
        val muscle = if (input.repeatedOrStatic) 1 else 0
        val forceLoad = forceLoadScore(input.loadKg, input.repeatedOrStatic)
        val legs = if (input.legsUnsupported) 2 else 1
        val neck = (neckScore(p.neck) + input.adjustments.neckTwisted.toInt() +
            input.adjustments.neckSideBending.toInt()).coerceIn(1, 6)
        val trunk = (trunkScore(p.trunk) + input.adjustments.trunkTwisted.toInt() +
            input.adjustments.trunkSideBending.toInt()).coerceIn(1, 6)
        val postureB = rulaTableB(neck, trunk, legs)
        val groupB = (postureB + muscle + forceLoad).coerceIn(1, 7)
        val left = if (p.leftArmDetected) rulaSide("Esquerdo", p.leftShoulder, p.leftElbow, p.leftWrist,
            neck, trunk, legs, postureB, groupB, muscle, forceLoad, input.adjustments, true) else null
        val right = if (p.rightArmDetected) rulaSide("Direito", p.rightShoulder, p.rightElbow, p.rightWrist,
            neck, trunk, legs, postureB, groupB, muscle, forceLoad, input.adjustments, false) else null
        val worst = listOfNotNull(left, right).maxByOrNull { it.final.score }
            ?: error("At least one arm must be detected")

        val rebaA = REBA_A[rebaTrunk(p.trunk) - 1][rebaNeck(p.neck) - 1][legs - 1]
        val rebaLoad = when {
            input.loadKg < 5.0 -> 0
            input.loadKg <= 10.0 -> 1
            else -> 2
        }
        val shoulders = listOfNotNull(p.leftShoulder.takeIf { p.leftArmDetected }, p.rightShoulder.takeIf { p.rightArmDetected })
        val elbows = listOfNotNull(p.leftElbow.takeIf { p.leftArmDetected }, p.rightElbow.takeIf { p.rightArmDetected })
        val wrists = listOfNotNull(p.leftWrist.takeIf { p.leftArmDetected }, p.rightWrist.takeIf { p.rightArmDetected })
        val rebaB = REBA_B[rebaUpperArm(shoulders.maxOrNull() ?: 0.0) - 1][rebaLowerArm(elbows.maxOrNull() ?: 0.0) - 1][rebaWrist(wrists.maxOrNull() ?: 0.0) - 1]
        val coupling = if (input.poorCoupling) 2 else 0
        val rebaScore = (REBA_C[(rebaA + rebaLoad).coerceIn(1, 12) - 1]
            [(rebaB + coupling).coerceIn(1, 12) - 1] + muscle).coerceAtMost(15)
        return ErgonomicResult(
            RulaResult(
                left, right, worst, p.legsDetected, input.adjustments,
                p.leftArmEstimatedFromFrontal, p.rightArmEstimatedFromFrontal
            ),
            rebaResult(rebaScore)
        )
    }

    private fun rulaSide(
        side: String, shoulder: Double, elbow: Double, wristAngle: Double,
        neck: Int, trunk: Int, legs: Int, postureB: Int, groupB: Int, muscle: Int,
        forceLoad: Int,
        adjustments: RulaAdjustments, isLeft: Boolean
    ): RulaSideResult {
        val raised = if (isLeft) adjustments.leftShoulderRaised else adjustments.rightShoulderRaised
        val abducted = if (isLeft) adjustments.leftArmAbducted else adjustments.rightArmAbducted
        val supported = if (isLeft) adjustments.leftArmSupported else adjustments.rightArmSupported
        val across = if (isLeft) adjustments.leftArmAcrossOrOutside else adjustments.rightArmAcrossOrOutside
        val twisted = if (isLeft) adjustments.leftWristTwisted else adjustments.rightWristTwisted
        val upperArm = (upperArmScore(shoulder) + raised.toInt() + abducted.toInt() - supported.toInt()).coerceIn(1, 6)
        val lowerArm = (lowerArmScore(elbow) + across.toInt()).coerceIn(1, 3)
        val wrist = wristScore(wristAngle)
        val wristTwist = if (twisted) 2 else 1
        val postureA = rulaTableA(upperArm, lowerArm, wrist, wristTwist)
        val groupA = (postureA + muscle + forceLoad).coerceIn(1, 8)
        val final = rulaResult(RULA_C[groupA - 1][groupB - 1])
        return RulaSideResult(side, upperArm, lowerArm, wrist, wristTwist,
            postureA, groupA, neck, trunk, legs, postureB, groupB, muscle, forceLoad, final)
    }

    private fun forceLoadScore(loadKg: Double, repeatedOrStatic: Boolean) = when {
        loadKg < 2.0 -> 0
        loadKg <= 10.0 && !repeatedOrStatic -> 1
        loadKg <= 10.0 -> 2
        else -> 3
    }

    private fun rulaTableA(upperArm: Int, lowerArm: Int, wrist: Int, twist: Int): Int {
        val row = (upperArm.coerceIn(1, 6) - 1) * 3 + lowerArm.coerceIn(1, 3) - 1
        val column = (wrist.coerceIn(1, 4) - 1) * 2 + twist.coerceIn(1, 2) - 1
        return RULA_A[row][column]
    }

    private fun rulaTableB(neck: Int, trunk: Int, legs: Int) =
        RULA_B[neck.coerceIn(1, 6) - 1][trunk.coerceIn(1, 6) - 1][legs.coerceIn(1, 2) - 1]

    private fun upperArmScore(a: Double) = when { a <= 20 -> 1; a <= 45 -> 2; a <= 90 -> 3; else -> 4 }
    private fun lowerArmScore(a: Double) = if (a in 60.0..100.0) 1 else 2
    private fun wristScore(a: Double) = when { a <= 5 -> 1; a <= 15 -> 2; else -> 3 }
    private fun neckScore(a: Double) = when { a <= 10 -> 1; a <= 20 -> 2; else -> 3 }
    private fun trunkScore(a: Double) = when { a <= 5 -> 1; a <= 20 -> 2; a <= 60 -> 3; else -> 4 }
    private fun rebaUpperArm(a: Double) = when { a <= 20 -> 1; a <= 45 -> 2; a <= 90 -> 3; else -> 4 }
    private fun rebaLowerArm(a: Double) = if (a in 60.0..100.0) 1 else 2
    private fun rebaWrist(a: Double) = if (a <= 15) 1 else 2
    private fun rebaNeck(a: Double) = if (a <= 20) 1 else 2
    private fun rebaTrunk(a: Double) = when { a <= 5 -> 1; a <= 20 -> 2; a <= 60 -> 3; else -> 4 }
    private fun Boolean.toInt() = if (this) 1 else 0

    private fun rulaResult(s: Int) = when (s) {
        1, 2 -> ErgonomicScore(s, "Aceitável", "Manter e acompanhar")
        3, 4 -> ErgonomicScore(s, "Baixo", "Investigar")
        5, 6 -> ErgonomicScore(s, "Alto", "Mudar em breve")
        else -> ErgonomicScore(7, "Muito alto", "Mudar imediatamente")
    }

    private fun rebaResult(s: Int) = when (s) {
        1 -> ErgonomicScore(s, "Desprezível", "Pode não exigir ação")
        in 2..3 -> ErgonomicScore(s, "Baixo", "Considerar mudanças")
        in 4..7 -> ErgonomicScore(s, "Médio", "Investigar e mudar")
        in 8..10 -> ErgonomicScore(s, "Alto", "Mudar rapidamente")
        else -> ErgonomicScore(s, "Muito alto", "Intervir imediatamente")
    }

    // Linhas: braço (1..6) x antebraço (1..3). Colunas: punho (1..4) x giro (1..2).
    private val RULA_A = arrayOf(
        intArrayOf(1,2,2,2,2,3,3,3), intArrayOf(2,2,2,2,3,3,3,3), intArrayOf(2,3,3,3,3,3,4,4),
        intArrayOf(2,3,3,3,3,4,4,4), intArrayOf(3,3,3,3,3,4,4,4), intArrayOf(3,4,4,4,4,4,5,5),
        intArrayOf(3,3,4,4,4,4,5,5), intArrayOf(3,4,4,4,4,4,5,5), intArrayOf(4,4,4,4,4,5,5,5),
        intArrayOf(4,4,4,4,4,5,5,5), intArrayOf(4,4,4,4,4,5,5,5), intArrayOf(4,4,4,5,5,5,6,6),
        intArrayOf(5,5,5,5,5,6,6,7), intArrayOf(5,6,6,6,6,7,7,7), intArrayOf(6,6,6,7,7,7,7,8),
        intArrayOf(7,7,7,7,7,8,8,9), intArrayOf(8,8,8,8,8,9,9,9), intArrayOf(9,9,9,9,9,9,9,9)
    )

    private val RULA_B = arrayOf(
        arrayOf(intArrayOf(1,3),intArrayOf(2,3),intArrayOf(3,4),intArrayOf(5,5),intArrayOf(6,6),intArrayOf(7,7)),
        arrayOf(intArrayOf(2,3),intArrayOf(2,3),intArrayOf(4,5),intArrayOf(5,5),intArrayOf(6,7),intArrayOf(7,7)),
        arrayOf(intArrayOf(3,3),intArrayOf(3,4),intArrayOf(4,5),intArrayOf(5,6),intArrayOf(6,7),intArrayOf(7,7)),
        arrayOf(intArrayOf(5,5),intArrayOf(5,6),intArrayOf(6,6),intArrayOf(7,7),intArrayOf(7,7),intArrayOf(8,8)),
        arrayOf(intArrayOf(7,7),intArrayOf(7,7),intArrayOf(7,8),intArrayOf(8,8),intArrayOf(8,8),intArrayOf(8,8)),
        arrayOf(intArrayOf(8,8),intArrayOf(8,8),intArrayOf(8,8),intArrayOf(8,9),intArrayOf(9,9),intArrayOf(9,9))
    )

    private val RULA_C = arrayOf(
        intArrayOf(1,2,3,3,4,5,5), intArrayOf(2,2,3,4,4,5,5), intArrayOf(3,3,3,4,4,5,6),
        intArrayOf(3,3,3,4,5,6,6), intArrayOf(4,4,4,5,6,7,7), intArrayOf(4,4,5,6,6,7,7),
        intArrayOf(5,5,6,6,7,7,7), intArrayOf(5,5,6,7,7,7,7)
    )

    private val REBA_A = arrayOf(
        arrayOf(intArrayOf(1,2),intArrayOf(1,2)), arrayOf(intArrayOf(2,3),intArrayOf(2,3)),
        arrayOf(intArrayOf(2,4),intArrayOf(3,5)), arrayOf(intArrayOf(3,5),intArrayOf(4,6)))
    private val REBA_B = arrayOf(
        arrayOf(intArrayOf(1,2),intArrayOf(1,2)), arrayOf(intArrayOf(1,2),intArrayOf(2,3)),
        arrayOf(intArrayOf(3,4),intArrayOf(4,5)), arrayOf(intArrayOf(4,5),intArrayOf(5,6)))
    private val REBA_C = arrayOf(
        intArrayOf(1,1,1,2,3,3,4,5,6,7,7,7), intArrayOf(1,2,2,3,4,4,5,6,6,7,7,8),
        intArrayOf(2,3,3,3,4,5,6,7,7,8,8,8), intArrayOf(3,4,4,4,5,6,7,8,8,9,9,9),
        intArrayOf(4,4,4,5,6,7,8,8,9,9,9,9), intArrayOf(6,6,6,7,8,8,9,9,10,10,10,10),
        intArrayOf(7,7,7,8,9,9,9,10,10,11,11,11), intArrayOf(8,8,8,9,10,10,10,10,10,11,11,11),
        intArrayOf(9,9,9,10,10,10,11,11,11,12,12,12), intArrayOf(10,10,10,11,11,11,11,12,12,12,12,12),
        intArrayOf(11,11,11,11,12,12,12,12,12,12,12,12), intArrayOf(12,12,12,12,12,12,12,12,12,12,12,12))
}
