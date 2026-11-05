package com.google.mediapipe.examples.poselandmarker

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssessmentHistoryActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var exportButton: MaterialButton
    private lateinit var clearButton: MaterialButton
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) exportHistory(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(16))
            setBackgroundColor(getColor(R.color.ui_background))
        }
        root.addView(MaterialButton(this).apply {
            text = "Voltar"
            setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "Histórico de avaliações"
            textSize = 22f
            setTextColor(getColor(R.color.ui_ink))
            setPadding(0, dp(20), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = "Toque em uma linha para ver os detalhes. REBA*: resultado simplificado."
            setTextColor(getColor(R.color.ui_muted))
            setPadding(0, 0, 0, dp(12))
        })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        exportButton = MaterialButton(this).apply {
            text = getString(R.string.assessment_export)
            isEnabled = false
            setOnClickListener {
                export.launch("avaliacoes_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv")
            }
        }
        root.addView(exportButton)
        clearButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setText(R.string.assessment_clear_history)
            isEnabled = false
            setOnClickListener { confirmClearHistory() }
        }
        root.addView(clearButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        setContentView(root)
        loadHistory()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun date(value: Long) = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(value))

    private fun loadHistory() {
        lifecycleScope.launch {
            try {
                val records = withContext(Dispatchers.IO) { AssessmentHistoryStore(applicationContext).use { it.all() } }
                content.removeAllViews()
                exportButton.isEnabled = records.isNotEmpty()
                clearButton.isEnabled = records.isNotEmpty()
                if (records.isEmpty()) {
                    content.addView(TextView(this@AssessmentHistoryActivity).apply {
                        setText(R.string.assessment_history_empty)
                        setPadding(0, dp(16), 0, dp(16))
                    })
                    return@launch
                }
                val table = LinearLayout(this@AssessmentHistoryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                fun addRow(cells: List<String>, record: SavedAssessment? = null) {
                    val row = TableRow(this@AssessmentHistoryActivity)
                    cells.forEachIndexed { index, value ->
                        row.addView(TextView(this@AssessmentHistoryActivity).apply {
                            text = value
                            textSize = 12f
                            setTextColor(getColor(R.color.ui_ink))
                            setPadding(dp(3), dp(12), dp(3), dp(12))
                            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT,
                                if (index == 0) 1.4f else if (index == 1) 1.3f else 0.8f)
                            if (record == null) setTypeface(typeface, android.graphics.Typeface.BOLD)
                        })
                    }
                    if (record != null) {
                        row.isFocusable = true
                        row.setBackgroundResource(android.R.drawable.list_selector_background)
                        row.setOnClickListener { showDetails(record) }
                    }
                    table.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
                addRow(listOf("Código", "Data e hora", "RULA", "REBA*", "OWAS"))
                records.forEach { record ->
                    fun value(key: String) = record.values[key].takeUnless { it.isNullOrBlank() } ?: "—"
                    fun score(key: String, maximum: Int) = record.values[key]
                        .takeUnless { it.isNullOrBlank() }?.let { "$it/$maximum" } ?: "—"
                    addRow(listOf(record.testCode, date(record.savedAt).replace(" ", "\n"), score("rula_score", 7),
                        score("reba_simplificado_score", 15), value("owas_categoria")), record)
                }
                content.addView(table)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("AssessmentHistory", "Unable to read history", error)
                Toast.makeText(this@AssessmentHistoryActivity, R.string.assessment_history_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showDetails(record: SavedAssessment) {
        val text = TextView(this).apply {
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setTextIsSelectable(true)
            text = buildString {
                append("Salva em ${date(record.savedAt)}\n")
                append("RULA ${record.values["rula_score"]}/7 · ${record.values["rula_risk"]}\n")
                append("${record.values["rula_action"]}\n\n")
                append("REBA simplificado: ${record.values["reba_simplificado_score"].takeUnless { it.isNullOrBlank() } ?: "não registrado"}\n")
                append("OWAS: ${record.values["owas_categoria"].takeUnless { it.isNullOrBlank() } ?: "não registrado"}\n\n")
                record.values.forEach { (key, value) ->
                    val displayed = when (value) { "true" -> "Sim"; "false" -> "Não"; "" -> "Não disponível"; else -> value }
                    append("${key.replace('_', ' ')}: $displayed\n")
                }
            }
        }
        val dialog = AlertDialog.Builder(this).setTitle(record.testCode)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton("Voltar", null)
            .setNegativeButton(R.string.assessment_delete) { _, _ ->
                AlertDialog.Builder(this).setTitle(R.string.assessment_delete)
                    .setMessage(getString(R.string.assessment_delete_confirm, record.testCode))
                    .setNegativeButton("Cancelar") { _, _ -> showDetails(record) }
                    .setPositiveButton("Excluir") { _, _ -> delete(record.id) }.show()
            }
        if (AssessmentPhotos(applicationContext).available(record.id)) {
            dialog.setNeutralButton("Ver fotos") { _, _ -> choosePhoto(record) }
        } else {
            text.append("\nFotos não disponíveis para esta avaliação.\n")
        }
        dialog.show()
    }

    private fun choosePhoto(record: SavedAssessment) {
        val labels = arrayOf("Frontal sem pontos", "Frontal com pontos", "Lateral sem pontos", "Lateral com pontos")
        var index = 0
        var request = 0
        var busy = false
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val caption = TextView(this).apply {
            textSize = 16f
            setTextColor(getColor(R.color.ui_ink))
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        layout.addView(caption)
        layout.addView(TextView(this).apply {
            text = "Deslize a foto ou use os botões para navegar."
            setTextColor(getColor(R.color.ui_muted))
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        layout.addView(image, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.48f).toInt()
        ))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val previous = MaterialButton(this).apply { text = "Anterior" }
        val next = MaterialButton(this).apply { text = "Próxima" }
        buttons.addView(previous, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(next, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        layout.addView(buttons)
        val dialog = AlertDialog.Builder(this).setTitle("Fotos da avaliação")
            .setView(layout).setNegativeButton("Voltar") { _, _ -> showDetails(record) }.create()
        fun showPhoto(newIndex: Int) {
            if (busy) return
            val oldIndex = index
            index = newIndex.coerceIn(labels.indices)
            val currentRequest = ++request
            val currentIndex = index
            val direction = (index - oldIndex).coerceIn(-1, 1)
            busy = true
            previous.isEnabled = false
            next.isEnabled = false
            fun finishLoading() {
                busy = false
                previous.isEnabled = index > 0
                next.isEnabled = index < labels.lastIndex
            }
            fun updateCaption() {
                caption.text = "${labels[index]} · ${index + 1}/${labels.size}"
                image.contentDescription = labels[index]
            }
            lifecycleScope.launch {
                try {
                    val bitmap = withContext(Dispatchers.IO) { AssessmentPhotos(applicationContext).loadPreview(record.id, currentIndex) }
                    if (!dialog.isShowing || currentRequest != request) {
                        bitmap.recycle()
                    } else if (direction == 0 || image.drawable == null) {
                        image.setImageBitmap(bitmap)
                        updateCaption()
                        finishLoading()
                    } else {
                        val offset = dp(48).toFloat() * direction
                        image.animate().translationX(-offset).alpha(0f).setDuration(130).withEndAction {
                            if (!dialog.isShowing || currentRequest != request) {
                                bitmap.recycle()
                            } else {
                                image.setImageBitmap(bitmap)
                                updateCaption()
                                image.translationX = offset
                                image.animate().translationX(0f).alpha(1f).setDuration(170).withEndAction {
                                    finishLoading()
                                }.start()
                            }
                        }.start()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    Log.e("AssessmentHistory", "Unable to load photo", error)
                    if (dialog.isShowing && currentRequest == request) {
                        index = oldIndex
                        updateCaption()
                        finishLoading()
                        Toast.makeText(this@AssessmentHistoryActivity, "Não foi possível abrir a foto.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        previous.setOnClickListener { showPhoto(index - 1) }
        next.setOnClickListener { showPhoto(index + 1) }
        image.enablePhotoSwipe(canSwipe = { !busy }) { direction ->
            if (index + direction in labels.indices) showPhoto(index + direction)
        }
        dialog.show()
        showPhoto(0)
    }

    private fun delete(id: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AssessmentPhotos(applicationContext).delete(id)
                    AssessmentHistoryStore(applicationContext).use { it.delete(id) }
                }
                loadHistory()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.e("AssessmentHistory", "Unable to delete record", error)
                Toast.makeText(this@AssessmentHistoryActivity, R.string.assessment_delete_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.assessment_clear_history)
            .setMessage(R.string.assessment_clear_confirm)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Apagar tudo") { _, _ ->
                clearButton.isEnabled = false
                exportButton.isEnabled = false
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            AssessmentHistoryStore(applicationContext).use { store ->
                                val photos = AssessmentPhotos(applicationContext)
                                store.all().forEach { photos.delete(it.id) }
                                store.deleteAll()
                            }
                        }
                        loadHistory()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        Log.e("AssessmentHistory", "Unable to clear history", error)
                        Toast.makeText(this@AssessmentHistoryActivity, R.string.assessment_clear_error, Toast.LENGTH_LONG).show()
                        loadHistory()
                    }
                }
            }.show()
    }

    private fun exportHistory(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val records = AssessmentHistoryStore(applicationContext).use { it.all() }
                    val output = contentResolver.openOutputStream(uri, "wt") ?: error("Unable to open export destination")
                    output.bufferedWriter(Charsets.UTF_8).use { AssessmentCsv.write(records, it) }
                }
                Toast.makeText(this@AssessmentHistoryActivity, R.string.assessment_exported, Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.e("AssessmentHistory", "Unable to export history", error)
                Toast.makeText(this@AssessmentHistoryActivity, R.string.assessment_export_error, Toast.LENGTH_LONG).show()
            }
        }
    }
}
