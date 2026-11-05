package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import androidx.appcompat.app.AlertDialog

object RulaAdjustmentsDialog {
    fun suggestions(angles: PoseAngles) = RulaAdjustments(
        leftArmAbducted = angles.suggestedLeftArmAbducted,
        rightArmAbducted = angles.suggestedRightArmAbducted,
        neckSideBending = angles.suggestedNeckSideBending,
        trunkSideBending = angles.suggestedTrunkSideBending
    )

    fun show(context: Context, current: RulaAdjustments, onApply: (RulaAdjustments) -> Unit) {
        val labels = arrayOf(
            "Ombro esquerdo elevado", "Ombro direito elevado",
            "Braço esquerdo abduzido", "Braço direito abduzido",
            "Braço esquerdo apoiado", "Braço direito apoiado",
            "Antebraço esquerdo cruza o corpo ou fica afastado para o lado", "Antebraço direito cruza o corpo ou fica afastado para o lado",
            "Punho esquerdo torcido", "Punho direito torcido",
            "Pescoço rotacionado", "Pescoço inclinado lateralmente",
            "Tronco rotacionado", "Tronco inclinado lateralmente"
        )
        val checked = booleanArrayOf(
            current.leftShoulderRaised, current.rightShoulderRaised,
            current.leftArmAbducted, current.rightArmAbducted,
            current.leftArmSupported, current.rightArmSupported,
            current.leftArmAcrossOrOutside, current.rightArmAcrossOrOutside,
            current.leftWristTwisted, current.rightWristTwisted,
            current.neckTwisted, current.neckSideBending,
            current.trunkTwisted, current.trunkSideBending
        )
        AlertDialog.Builder(context)
            .setTitle("Confirmar ajustes RULA")
            .setMultiChoiceItems(labels, checked) { _, which, selected -> checked[which] = selected }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar") { _, _ ->
                onApply(RulaAdjustments(
                    checked[0], checked[1], checked[2], checked[3], checked[4], checked[5],
                    checked[6], checked[7], checked[8], checked[9], checked[10], checked[11],
                    checked[12], checked[13]
                ))
            }
            .show()
    }
}
