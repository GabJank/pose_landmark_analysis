package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object OwasDialog {
    fun show(context: Context, angles: PoseAngles?, onApply: (OwasObservation) -> Unit) {
        val fields = listOf(
            "Costas" to listOf("Selecione", "Retas", "Inclinadas", "Torção ou inclinação lateral", "Inclinadas e torcidas/laterais"),
            "Braços" to listOf("Selecione", "Ambos abaixo dos ombros", "Um na altura ou acima dos ombros", "Ambos na altura ou acima dos ombros"),
            "Pernas" to listOf("Selecione", "Sentado", "Em pé, duas pernas retas", "Em pé, peso em uma perna reta", "Duas pernas flexionadas", "Peso em uma perna flexionada", "Ajoelhado", "Andando ou movendo-se"),
            "Carga ou força" to listOf("Selecione", "Até 10 kg", "Mais de 10 até 20 kg", "Mais de 20 kg")
        )
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
        }
        layout.addView(TextView(context).apply {
            text = angles?.let {
                "Referência da imagem: inclinação estimada do tronco ${"%.1f".format(it.trunk)}°. Confirme a postura observada."
            } ?: "Selecione as fotos frontal e lateral para consultar a inclinação estimada do tronco."
        })
        val spinners = fields.map { (label, options) ->
            layout.addView(TextView(context).apply { text = label })
            Spinner(context).also { spinner ->
                spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
                layout.addView(spinner)
            }
        }
        val dialog = AlertDialog.Builder(context).setTitle("OWAS: observar postura")
            .setView(layout).setNegativeButton("Cancelar", null)
            .setPositiveButton("Calcular", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val codes = spinners.map { it.selectedItemPosition }
                if (codes.any { it == 0 }) {
                    android.widget.Toast.makeText(context, "Selecione todas as opções", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    onApply(OwasObservation(codes[0], codes[1], codes[2], codes[3]))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }
}
