package com.google.mediapipe.examples.poselandmarker

import java.util.Collections
import java.util.UUID

data class SavedAssessment(
    val id: String,
    val savedAt: Long,
    val testCode: String,
    val values: Map<String, String>
)

data class OwasObservation(val back: Int, val arms: Int, val legs: Int, val load: Int) {
    val result: ErgonomicScore get() = OwasAssessment.calculate(back, arms, legs, load)
    fun displayText(): String {
        val score = result
        return "OWAS $back$arms$legs$load — categoria ${score.score} (${score.risk})\n${score.action}\nUma observação; avalie também a frequência das posturas na tarefa."
    }
}

object AssessmentRecords {
    private val codePattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")
    internal val angleColumns = listOf(
        "ombro_esquerdo_graus", "ombro_direito_graus", "cotovelo_esquerdo_graus",
        "cotovelo_direito_graus", "punho_esquerdo_graus", "punho_direito_graus",
        "pescoco_graus", "tronco_graus", "joelho_esquerdo_graus", "joelho_direito_graus",
        "pernas_detectadas", "braco_esquerdo_detectado", "braco_direito_detectado",
        "braco_esquerdo_confiavel", "braco_direito_confiavel",
        "braco_esquerdo_estimado_frontal", "braco_direito_estimado_frontal"
    )
    internal val inputColumns = listOf(
        "carga_kg", "atividade_repetitiva_estatica", "pernas_sem_apoio", "pega_ruim",
        "ombro_esquerdo_elevado", "ombro_direito_elevado", "braco_esquerdo_abduzido",
        "braco_direito_abduzido", "braco_esquerdo_apoiado", "braco_direito_apoiado",
        "antebraco_esquerdo_fora", "antebraco_direito_fora", "punho_esquerdo_torcido",
        "punho_direito_torcido", "pescoco_rotacionado", "pescoco_inclinado_lateralmente",
        "tronco_rotacionado", "tronco_inclinado_lateralmente"
    )
    internal val sideColumns = listOf(
        "score", "braco", "antebraco", "punho", "giro_punho", "postura_a", "grupo_a",
        "pescoco", "tronco", "pernas", "postura_b", "grupo_b", "atividade", "carga"
    )
    val valueColumns: List<String> =
        listOf("versao_registro", "rula_score", "rula_risk", "rula_action", "rula_side", "rula_parcial",
            "reba_simplificado_score", "reba_simplificado_risk", "reba_simplificado_action",
            "owas_costas", "owas_bracos", "owas_pernas", "owas_carga", "owas_categoria", "owas_risk", "owas_action") +
            listOf("rula_left", "rula_right").flatMap { prefix -> sideColumns.map { "${prefix}_$it" } } +
            inputColumns +
            listOf("frontal", "lateral", "combinado").flatMap { prefix -> angleColumns.map { "${prefix}_$it" } }

    fun create(testCode: String, session: RulaTwoViewSession, inputs: ErgonomicInputs, owas: OwasObservation? = null): SavedAssessment {
        val code = testCode.trim()
        require(codePattern.matches(code)) {
            "Use um código de 1 a 32 caracteres, começando por letra ou número, com letras, números, hífen ou sublinhado."
        }
        require(inputs.loadKg.isFinite() && inputs.loadKg >= 0) { "Informe uma carga válida, maior ou igual a zero." }
        val combined = requireNotNull(session.combinedAngles()) { "Adicione as fotos frontal e lateral antes de salvar." }
        require(combined.leftArmDetected || combined.rightArmDetected) { "Não há pontos suficientes para salvar a avaliação." }
        val fields = linkedMapOf<String, String>()
        fields["versao_registro"] = "2"
        addAngles(fields, "frontal", requireNotNull(session.frontal))
        addAngles(fields, "lateral", requireNotNull(session.lateral))
        addAngles(fields, "combinado", combined)

        val a = inputs.adjustments
        val inputValues = listOf(
            inputs.loadKg, inputs.repeatedOrStatic, inputs.legsUnsupported, inputs.poorCoupling,
            a.leftShoulderRaised, a.rightShoulderRaised, a.leftArmAbducted, a.rightArmAbducted,
            a.leftArmSupported, a.rightArmSupported, a.leftArmAcrossOrOutside, a.rightArmAcrossOrOutside,
            a.leftWristTwisted, a.rightWristTwisted, a.neckTwisted, a.neckSideBending,
            a.trunkTwisted, a.trunkSideBending
        )
        inputColumns.zip(inputValues).forEach { (name, value) -> fields[name] = value.toString() }
        val assessment = ErgonomicAssessment.calculate(combined, inputs)
        val rula = assessment.rula
        fields["reba_simplificado_score"] = assessment.reba.score.toString()
        fields["reba_simplificado_risk"] = assessment.reba.risk
        fields["reba_simplificado_action"] = assessment.reba.action
        val owasResult = owas?.result
        fields["owas_costas"] = owas?.back?.toString().orEmpty()
        fields["owas_bracos"] = owas?.arms?.toString().orEmpty()
        fields["owas_pernas"] = owas?.legs?.toString().orEmpty()
        fields["owas_carga"] = owas?.load?.toString().orEmpty()
        fields["owas_categoria"] = owasResult?.score?.toString().orEmpty()
        fields["owas_risk"] = owasResult?.risk.orEmpty()
        fields["owas_action"] = owasResult?.action.orEmpty()
        fields["rula_score"] = rula.worst.final.score.toString()
        fields["rula_risk"] = rula.worst.final.risk
        fields["rula_action"] = rula.worst.final.action
        fields["rula_side"] = rula.worst.side
        fields["rula_parcial"] = (rula.left == null || rula.right == null).toString()
        addSide(fields, "rula_left", rula.left)
        addSide(fields, "rula_right", rula.right)
        // Store the result as it was saved; later changes to scoring must not rewrite history.
        return SavedAssessment(UUID.randomUUID().toString(), System.currentTimeMillis(), code,
            Collections.unmodifiableMap(LinkedHashMap(fields)))
    }

    private fun addAngles(fields: MutableMap<String, String>, prefix: String, p: PoseAngles) {
        val angles = listOf(
            p.leftShoulder.takeIf { p.leftArmDetected }, p.rightShoulder.takeIf { p.rightArmDetected },
            p.leftElbow.takeIf { p.leftArmDetected }, p.rightElbow.takeIf { p.rightArmDetected },
            p.leftWrist.takeIf { p.leftArmDetected }, p.rightWrist.takeIf { p.rightArmDetected },
            p.neck, p.trunk, p.leftKnee.takeIf { p.legsDetected }, p.rightKnee.takeIf { p.legsDetected }
        )
        require(angles.filterNotNull().all { it.isFinite() }) { "Há ângulos inválidos nesta avaliação." }
        val flags = listOf(p.legsDetected, p.leftArmDetected, p.rightArmDetected,
            p.leftArmReliable, p.rightArmReliable, p.leftArmEstimatedFromFrontal, p.rightArmEstimatedFromFrontal)
        val values = angles.map { it?.toString().orEmpty() } + flags.map { it.toString() }
        angleColumns.zip(values).forEach { (name, value) -> fields["${prefix}_$name"] = value }
    }

    private fun addSide(fields: MutableMap<String, String>, prefix: String, side: RulaSideResult?) {
        val values = side?.let { listOf(it.final.score, it.upperArm, it.lowerArm, it.wrist,
            it.wristTwist, it.postureA, it.groupA, it.neck, it.trunk, it.legs, it.postureB,
            it.groupB, it.muscleUse, it.forceLoad) }
        sideColumns.forEachIndexed { index, name -> fields["${prefix}_$name"] = values?.get(index)?.toString().orEmpty() }
    }
}
