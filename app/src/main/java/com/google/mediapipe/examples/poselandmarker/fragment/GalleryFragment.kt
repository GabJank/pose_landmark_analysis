/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.poselandmarker.fragment

import android.Manifest
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.scale
import com.google.android.material.bottomsheet.BottomSheetBehavior
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.doAfterTextChanged
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.CapturedPoseStore
import com.google.mediapipe.examples.poselandmarker.AssessmentHistoryStore
import com.google.mediapipe.examples.poselandmarker.AssessmentPhotos
import com.google.mediapipe.examples.poselandmarker.enablePhotoSwipe
import com.google.mediapipe.examples.poselandmarker.AssessmentRecords
import com.google.mediapipe.examples.poselandmarker.PoseAngleCalculator
import com.google.mediapipe.examples.poselandmarker.ErgonomicAssessment
import com.google.mediapipe.examples.poselandmarker.ErgonomicInputs
import com.google.mediapipe.examples.poselandmarker.displayText
import com.google.mediapipe.examples.poselandmarker.RulaAdjustments
import com.google.mediapipe.examples.poselandmarker.OwasDialog
import com.google.mediapipe.examples.poselandmarker.OwasObservation
import com.google.mediapipe.examples.poselandmarker.RulaAdjustmentsDialog
import com.google.mediapipe.examples.poselandmarker.RulaTwoViewSession
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentGalleryBinding
import com.google.mediapipe.examples.poselandmarker.databinding.DialogSaveAssessmentBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    private enum class RulaView { FRONTAL, LATERAL }
    private data class ViewSnapshot(
        val bitmap: Bitmap,
        val result: PoseLandmarkerResult
    )

    enum class MediaType {
        IMAGE,
        VIDEO,
        UNKNOWN
    }

    private var _fragmentGalleryBinding: FragmentGalleryBinding? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraPreview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var liveAnalysis: ImageAnalysis? = null
    private var liveExecutor: ExecutorService? = null
    private var liveHelper: Array<PoseLandmarkerHelper?>? = null
    private var cameraActive = false
    private var cameraGeneration = 0
    private var cameraFacing = CameraSelector.LENS_FACING_BACK
    private var showAssessmentDetails = false
    private var savingAssessment = false
    private var imageProcessing = false
    private var animatingPhoto = false
    private var historyDialog: AlertDialog? = null
    private var rulaAdjustments = RulaAdjustments()
    private var owasObservation: OwasObservation? = null
    private var latestAngles: com.google.mediapipe.examples.poselandmarker.PoseAngles? = null
    private var rulaSession = RulaTwoViewSession()
    private var pendingRulaView = RulaView.FRONTAL
    private var frontalSnapshot: ViewSnapshot? = null
    private var lateralSnapshot: ViewSnapshot? = null
    private val fragmentGalleryBinding
        get() = _fragmentGalleryBinding!!
    private val viewModel: MainViewModel by activityViewModels()

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ScheduledExecutorService

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            // Handle the returned Uri
            uri?.let { mediaUri ->
                fragmentGalleryBinding.bottomSheetLayout.ergonomicResultLabel.text =
                    if (pendingRulaView == RulaView.FRONTAL)
                        "RULA: analisando foto frontal"
                    else "RULA: analisando foto lateral"
                when (val mediaType = loadMediaType(mediaUri)) {
                    MediaType.IMAGE -> runDetectionOnImage(mediaUri)
                    MediaType.VIDEO -> Toast.makeText(
                        requireContext(),
                        "Selecione uma foto para a avaliação em duas vistas.",
                        Toast.LENGTH_SHORT
                    ).show()
                    MediaType.UNKNOWN -> {
                        updateDisplayView(mediaType)
                        Toast.makeText(
                            requireContext(),
                            "Unsupported data type.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCameraCaptureInternal()
            } else {
                Toast.makeText(
                    requireContext(),
                    "A permissão da câmera é necessária apenas para tirar uma foto.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        imageProcessing = false
        _fragmentGalleryBinding =
            FragmentGalleryBinding.inflate(inflater, container, false)

        return fragmentGalleryBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        parentFragmentManager.setFragmentResultListener(
            CAPTURE_RESULT_KEY,
            viewLifecycleOwner
        ) { _, result ->
            pendingRulaView = if (result.getString(CAPTURE_VIEW_KEY) == "lateral") {
                RulaView.LATERAL
            } else {
                RulaView.FRONTAL
            }
            result.getString(CAPTURE_URI_KEY)?.let { key ->
                val captured = CapturedPoseStore.remove(key)
                if (captured != null) {
                    val snapshot = ViewSnapshot(captured.bitmap, captured.result)
                    if (pendingRulaView == RulaView.FRONTAL) frontalSnapshot = snapshot
                    else lateralSnapshot = snapshot
                    showSnapshot(snapshot)
                    updateAngleLabels(captured.result, captured.angles)
                } else {
                    runDetectionOnImage(Uri.parse(key))
                }
            }
        }
        fragmentGalleryBinding.bottomSheetLayout.owasButton.setOnClickListener {
            OwasDialog.show(requireContext(), latestAngles) { result ->
                owasObservation = result
                fragmentGalleryBinding.bottomSheetLayout.owasResultLabel.text = result.displayText()
            }
        }
        owasObservation?.let { fragmentGalleryBinding.bottomSheetLayout.owasResultLabel.text = it.displayText() }
        fragmentGalleryBinding.bottomSheetLayout.rulaAdjustmentsButton.setOnClickListener {
            RulaAdjustmentsDialog.show(requireContext(), rulaAdjustments) {
                rulaAdjustments = it
                refreshErgonomicLabel()
            }
        }
        fragmentGalleryBinding.bottomSheetLayout.repeatedStaticInput.setOnCheckedChangeListener { _, _ ->
            refreshErgonomicLabel()
        }
        fragmentGalleryBinding.bottomSheetLayout.legsUnsupportedInput.setOnCheckedChangeListener { _, _ ->
            refreshErgonomicLabel()
        }
        fragmentGalleryBinding.bottomSheetLayout.poorCouplingInput.setOnCheckedChangeListener { _, _ ->
            refreshErgonomicLabel()
        }
        fragmentGalleryBinding.bottomSheetLayout.loadKgInput.doAfterTextChanged {
            refreshErgonomicLabel()
        }
        fragmentGalleryBinding.selectFrontalButton.setOnClickListener {
            selectView(RulaView.FRONTAL)
        }
        fragmentGalleryBinding.selectLateralButton.setOnClickListener {
            selectView(RulaView.LATERAL)
        }
        fragmentGalleryBinding.photoPreview.enablePhotoSwipe(
            canSwipe = { !cameraActive && !imageProcessing && !animatingPhoto && frontalSnapshot != null && lateralSnapshot != null }
        ) { direction ->
            val target = when {
                direction > 0 && pendingRulaView == RulaView.FRONTAL -> RulaView.LATERAL
                direction < 0 && pendingRulaView == RulaView.LATERAL -> RulaView.FRONTAL
                else -> null
            }
            if (target != null) animatePhotoTransition(target, direction)
        }
        fragmentGalleryBinding.toggleCameraButton.setOnClickListener {
            if (cameraActive) stopCamera() else launchCameraCapture()
        }
        fragmentGalleryBinding.captureButton.setOnClickListener { capturePhoto() }
        fragmentGalleryBinding.switchCameraButton.setOnClickListener {
            cameraFacing = if (cameraFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        fragmentGalleryBinding.addOrReplacePhotoButton.setOnClickListener {
            showPhotoSourceChooser()
        }

        val sheet = fragmentGalleryBinding.bottomSheetLayout
        val behavior = BottomSheetBehavior.from(sheet.root)
        sheet.sheetToggleButton.setOnClickListener {
            behavior.state = if (behavior.state == BottomSheetBehavior.STATE_EXPANDED)
                BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_EXPANDED
        }
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                sheet.sheetToggleButton.text = if (newState == BottomSheetBehavior.STATE_EXPANDED)
                    "Voltar às fotos" else "Dados e resultados"
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) = Unit
        })
        sheet.detailsButton.setOnClickListener {
            showAssessmentDetails = !showAssessmentDetails
            refreshErgonomicLabel()
        }
        sheet.saveAssessmentButton.setOnClickListener { saveAssessment() }
        sheet.assessmentHistoryButton.setOnClickListener { startActivity(android.content.Intent(requireContext(), com.google.mediapipe.examples.poselandmarker.AssessmentHistoryActivity::class.java)) }
        initBottomSheetControls()
        updatePhotoControls()
        refreshErgonomicLabel()
    }

    private fun selectView(view: RulaView) {
        pendingRulaView = view
        updatePhotoControls()

        val snapshot = if (view == RulaView.FRONTAL) frontalSnapshot else lateralSnapshot
        if (snapshot == null) {
            showPhotoSourceChooser()
        } else {
            showSnapshot(snapshot)
        }
    }

    private fun animatePhotoTransition(target: RulaView, direction: Int) {
        val binding = fragmentGalleryBinding
        val preview = binding.photoPreview
        val startingView = pendingRulaView
        val offset = 48 * resources.displayMetrics.density * direction
        animatingPhoto = true
        preview.animate().translationX(-offset).alpha(0f).setDuration(130).withEndAction {
            if (_fragmentGalleryBinding !== binding) return@withEndAction
            if (!cameraActive && !imageProcessing && pendingRulaView == startingView) selectView(target)
            preview.translationX = offset
            preview.animate().translationX(0f).alpha(1f).setDuration(170).withEndAction {
                animatingPhoto = false
            }.start()
        }.start()
    }

    private fun updatePhotoControls() {
        val frontal = pendingRulaView == RulaView.FRONTAL
        val snapshot = if (frontal) frontalSnapshot else lateralSnapshot
        fragmentGalleryBinding.selectFrontalButton.isChecked = frontal
        fragmentGalleryBinding.selectLateralButton.isChecked = !frontal
        fragmentGalleryBinding.selectFrontalButton.text =
            if (rulaSession.frontal != null) "Frontal ✓" else "Frontal"
        fragmentGalleryBinding.selectLateralButton.text =
            if (rulaSession.lateral != null) "Lateral ✓" else "Lateral"
        val count = listOfNotNull(rulaSession.frontal, rulaSession.lateral).size
        fragmentGalleryBinding.photoCountLabel.text = "FOTOS DA POSTURA  ·  $count de 2"
        val action = if (snapshot == null) "Adicionar" else "Trocar"
        val view = if (frontal) "frontal" else "lateral"
        fragmentGalleryBinding.addOrReplacePhotoButton.text = "$action foto $view"
    }

    private fun showSnapshot(snapshot: ViewSnapshot) {
        val binding = fragmentGalleryBinding
        updateDisplayView(MediaType.IMAGE)
        fragmentGalleryBinding.imageResult.setImageBitmap(snapshot.bitmap)
        fragmentGalleryBinding.overlay.post {
            if (_fragmentGalleryBinding !== binding) return@post
            fragmentGalleryBinding.overlay.setResults(
                snapshot.result,
                snapshot.bitmap.height,
                snapshot.bitmap.width,
                RunningMode.IMAGE
            )
        }
    }

    private fun showPhotoSourceChooser() {
        val viewName = if (pendingRulaView == RulaView.FRONTAL) "frontal" else "lateral"
        AlertDialog.Builder(requireContext())
            .setTitle("Adicionar foto $viewName")
            .setItems(arrayOf("Tirar foto", "Escolher da galeria")) { _, option ->
                if (option == 0) launchCameraCapture() else {
                    stopCamera()
                    getContent.launch(arrayOf("image/*"))
                }
            }
            .show()
    }

    private fun launchCameraCapture() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchCameraCaptureInternal()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCameraCaptureInternal() = startCamera()

    private fun startCamera() {
        stopCamera()
        cameraActive = true
        val generation = ++cameraGeneration
        fragmentGalleryBinding.cameraPreview.visibility = View.VISIBLE
        fragmentGalleryBinding.cameraControls.visibility = View.VISIBLE
        fragmentGalleryBinding.liveOverlay.visibility = View.VISIBLE
        fragmentGalleryBinding.liveStatus.visibility = View.VISIBLE
        fragmentGalleryBinding.liveStatus.text = "Procurando postura…"
        fragmentGalleryBinding.toggleCameraButton.text = "Desativar câmera"
        fragmentGalleryBinding.captureButton.isEnabled = false
        val future = ProcessCameraProvider.getInstance(requireContext())
        future.addListener({
            if (!cameraActive || generation != cameraGeneration || _fragmentGalleryBinding == null) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                cameraPreview?.let { provider.unbind(it) }
                imageCapture?.let { provider.unbind(it) }
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(fragmentGalleryBinding.cameraPreview.surfaceProvider)
                }
                val capture = ImageCapture.Builder().build()
                val executor = Executors.newSingleThreadExecutor()
                liveExecutor = executor
                val helperSlot = arrayOfNulls<PoseLandmarkerHelper>(1)
                liveHelper = helperSlot
                val context = requireContext().applicationContext
                val facingFront = cameraFacing == CameraSelector.LENS_FACING_FRONT
                executor.execute {
                    helperSlot[0] = PoseLandmarkerHelper(
                        context = context,
                        runningMode = RunningMode.LIVE_STREAM,
                        currentModel = PoseLandmarkerHelper.MODEL_POSE_LANDMARKER_FULL,
                        currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU,
                        poseLandmarkerHelperListener = object : PoseLandmarkerHelper.LandmarkerListener {
                            override fun onError(error: String, errorCode: Int) {
                                activity?.runOnUiThread {
                                    if (cameraActive && generation == cameraGeneration && _fragmentGalleryBinding != null)
                                        fragmentGalleryBinding.liveStatus.text = "Erro na análise da postura: $error"
                                }
                            }
                            override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
                                activity?.runOnUiThread {
                                    if (cameraActive && generation == cameraGeneration && _fragmentGalleryBinding != null)
                                        showLiveResult(resultBundle)
                                }
                            }
                        }
                    )
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(fragmentGalleryBinding.cameraPreview.display.rotation)
                    .build().also { useCase ->
                        useCase.setAnalyzer(executor) { image ->
                            val helper = helperSlot[0]
                            if (helper == null) image.close() else helper.detectLiveStream(image, facingFront)
                        }
                    }
                liveAnalysis = analysis
                cameraPreview = preview
                imageCapture = capture
                provider.bindToLifecycle(viewLifecycleOwner, CameraSelector.Builder().requireLensFacing(cameraFacing).build(), preview, capture, analysis)
                fragmentGalleryBinding.captureButton.isEnabled = true
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao iniciar câmera", e)
                stopCamera()
                Toast.makeText(requireContext(), "Não foi possível iniciar a câmera.", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun stopCamera() {
        cameraActive = false
        cameraGeneration++
        cameraPreview?.let { cameraProvider?.unbind(it) }
        imageCapture?.let { cameraProvider?.unbind(it) }
        liveAnalysis?.clearAnalyzer()
        liveAnalysis?.let { cameraProvider?.unbind(it) }
        liveAnalysis = null
        val helperSlot = liveHelper
        liveHelper = null
        liveExecutor?.let { executor ->
            executor.execute { helperSlot?.get(0)?.clearPoseLandmarker() }
            executor.shutdown()
        }
        liveExecutor = null
        cameraPreview = null
        imageCapture = null
        _fragmentGalleryBinding?.let {
            it.cameraPreview.visibility = View.GONE
            it.cameraControls.visibility = View.GONE
            it.liveOverlay.clear()
            it.liveOverlay.visibility = View.GONE
            it.liveStatus.visibility = View.GONE
            it.toggleCameraButton.text = "Ativar câmera"
        }
    }

    private fun showLiveResult(bundle: PoseLandmarkerHelper.ResultBundle) {
        val result = bundle.results.firstOrNull()
        if (result == null || result.landmarks().isEmpty()) {
            fragmentGalleryBinding.liveOverlay.clear()
            fragmentGalleryBinding.liveStatus.text = "Nenhuma postura detectada. Enquadre o corpo inteiro."
            return
        }
        fragmentGalleryBinding.liveOverlay.setResults(result, bundle.inputImageHeight, bundle.inputImageWidth, RunningMode.LIVE_STREAM)
        val angles = PoseAngleCalculator.calculate(result)
        fragmentGalleryBinding.liveStatus.text = if (angles == null) {
            "Postura detectada. Mostre ombros, quadris e pelo menos um braço."
        } else {
            val assessment = ErgonomicAssessment.calculate(angles, ergonomicInputs())
            val shoulder = if (angles.leftArmDetected) angles.leftShoulder else angles.rightShoulder
            val elbow = if (angles.leftArmDetected) angles.leftElbow else angles.rightElbow
            String.format(Locale.US, "Postura em tempo real · Ombro: %.0f° · Cotovelo: %.0f°\nRULA: %d/7 · REBA simplificado: %d/15", shoulder, elbow, assessment.rula.worst.final.score, assessment.reba.score)
        }
    }

    private fun capturePhoto() {
        val binding = fragmentGalleryBinding
        val capture = imageCapture ?: return
        val context = requireContext()
        val directory = File(context.cacheDir, "assessment_photos").apply { mkdirs() }
        val photo = try { File.createTempFile("rula_capture_", ".jpg", directory) } catch (e: Exception) {
            Toast.makeText(context, "Não foi possível salvar a foto.", Toast.LENGTH_SHORT).show()
            return
        }
        fragmentGalleryBinding.captureButton.isEnabled = false
        setUiEnabled(false)
        fragmentGalleryBinding.switchCameraButton.isEnabled = false
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(photo).build(), ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (_fragmentGalleryBinding !== binding) return
                    fragmentGalleryBinding.switchCameraButton.isEnabled = true
                    stopCamera()
                    runDetectionOnImage(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photo))
                }
                override fun onError(exception: ImageCaptureException) {
                    photo.delete()
                    if (_fragmentGalleryBinding !== binding) return
                    setUiEnabled(true)
                    fragmentGalleryBinding.toggleCameraButton.isEnabled = true
                    fragmentGalleryBinding.switchCameraButton.isEnabled = true
                    fragmentGalleryBinding.captureButton.isEnabled = cameraActive
                    Toast.makeText(context, "Não foi possível tirar a foto.", Toast.LENGTH_SHORT).show()
                }
            })
    }

    override fun onPause() {
        if (fragmentGalleryBinding.videoView.isPlaying) {
            fragmentGalleryBinding.videoView.stopPlayback()
        }
        fragmentGalleryBinding.videoView.visibility = View.GONE
        super.onPause()
    }

    override fun onDestroyView() {
        _fragmentGalleryBinding?.photoPreview?.animate()?.cancel()
        animatingPhoto = false
        historyDialog?.dismiss()
        historyDialog = null
        stopCamera()
        if (::backgroundExecutor.isInitialized) backgroundExecutor.shutdown()
        _fragmentGalleryBinding = null
        super.onDestroyView()
    }

    private fun saveAssessment() {
        if (savingAssessment || imageProcessing || latestAngles == null || !validLoad()) return
        val session = rulaSession.copy()
        val inputs = ergonomicInputs()
        val owas = owasObservation
        val frontal = frontalSnapshot ?: return
        val lateral = lateralSnapshot ?: return
        val fields = DialogSaveAssessmentBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.save_assessment)
            .setView(fields.root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar", null)
            .create()
        historyDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = fields.testCodeInput.text.toString().trim()
                if (!code.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,31}"))) {
                    fields.root.error = getString(R.string.assessment_code_error)
                    return@setOnClickListener
                }
                val record = try {
                    AssessmentRecords.create(code, session, inputs, owas)
                } catch (error: IllegalArgumentException) {
                    fields.root.error = error.message
                    return@setOnClickListener
                }
                val appContext = requireContext().applicationContext
                dialog.dismiss()
                savingAssessment = true
                refreshErgonomicLabel()
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val photos = AssessmentPhotos(appContext)
                            photos.save(record.id, frontal.bitmap, frontal.result.landmarks(), lateral.bitmap, lateral.result.landmarks())
                            try {
                                AssessmentHistoryStore(appContext).use { it.save(record) }
                            } catch (error: Exception) {
                                photos.delete(record.id)
                                throw error
                            }
                        }
                        Toast.makeText(appContext, R.string.assessment_saved, Toast.LENGTH_SHORT).show()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e(TAG, "Unable to save assessment", error)
                        Toast.makeText(appContext, R.string.assessment_save_error, Toast.LENGTH_LONG).show()
                    } finally {
                        savingAssessment = false
                        if (_fragmentGalleryBinding != null) refreshErgonomicLabel()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun validLoad(): Boolean {
        val raw = fragmentGalleryBinding.bottomSheetLayout.loadKgInput.text.toString().trim()
        val number = raw.replace(',', '.').toDoubleOrNull()
        return raw.isEmpty() || (number != null && number.isFinite() && number >= 0.0)
    }

    private fun initBottomSheetControls() {
        // init bottom sheet settings
        fragmentGalleryBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPoseDetectionConfidence
            )
        fragmentGalleryBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPoseTrackingConfidence
            )
        fragmentGalleryBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPosePresenceConfidence
            )

        // When clicked, lower detection score threshold floor
        fragmentGalleryBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (viewModel.currentMinPoseDetectionConfidence >= 0.2) {
                viewModel.setMinPoseDetectionConfidence(viewModel.currentMinPoseDetectionConfidence - 0.1f)
                updateControlsUi()
            }
        }

        // When clicked, raise detection score threshold floor
        fragmentGalleryBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (viewModel.currentMinPoseDetectionConfidence <= 0.8) {
                viewModel.setMinPoseDetectionConfidence(viewModel.currentMinPoseDetectionConfidence + 0.1f)
                updateControlsUi()
            }
        }

        // When clicked, lower pose tracking score threshold floor
        fragmentGalleryBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (viewModel.currentMinPoseTrackingConfidence >= 0.2) {
                viewModel.setMinPoseTrackingConfidence(
                    viewModel.currentMinPoseTrackingConfidence - 0.1f
                )
                updateControlsUi()
            }
        }

        // When clicked, raise pose tracking score threshold floor
        fragmentGalleryBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (viewModel.currentMinPoseTrackingConfidence <= 0.8) {
                viewModel.setMinPoseTrackingConfidence(
                    viewModel.currentMinPoseTrackingConfidence + 0.1f
                )
                updateControlsUi()
            }
        }

        // When clicked, lower pose presence score threshold floor
        fragmentGalleryBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (viewModel.currentMinPosePresenceConfidence >= 0.2) {
                viewModel.setMinPosePresenceConfidence(
                    viewModel.currentMinPosePresenceConfidence - 0.1f
                )
                updateControlsUi()
            }
        }

        // When clicked, raise pose presence score threshold floor
        fragmentGalleryBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (viewModel.currentMinPosePresenceConfidence <= 0.8) {
                viewModel.setMinPosePresenceConfidence(
                    viewModel.currentMinPosePresenceConfidence + 0.1f
                )
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference. Current options are CPU
        // GPU, and NNAPI
        fragmentGalleryBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate,
            false
        )
        fragmentGalleryBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    p2: Int,
                    p3: Long
                ) {

                    viewModel.setDelegate(p2)
                    updateControlsUi()
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }

        // When clicked, change the underlying model used for object detection
        fragmentGalleryBinding.bottomSheetLayout.spinnerModel.setSelection(
            viewModel.currentModel,
            false
        )
        fragmentGalleryBinding.bottomSheetLayout.spinnerModel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?,
                    p1: View?,
                    p2: Int,
                    p3: Long
                ) {
                    viewModel.setModel(p2)
                    updateControlsUi()
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    // Update the values displayed in the bottom sheet. Reset detector.
    private fun updateControlsUi() {
        if (fragmentGalleryBinding.videoView.isPlaying) {
            fragmentGalleryBinding.videoView.stopPlayback()
        }
        fragmentGalleryBinding.videoView.visibility = View.GONE
        fragmentGalleryBinding.imageResult.visibility = View.GONE
        fragmentGalleryBinding.overlay.clear()
        fragmentGalleryBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPoseDetectionConfidence
            )
        fragmentGalleryBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPoseTrackingConfidence
            )
        fragmentGalleryBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPosePresenceConfidence
            )

        fragmentGalleryBinding.overlay.clear()
        fragmentGalleryBinding.tvPlaceholder.visibility = View.VISIBLE
    }

    // Each analysis owns its helper and only updates the view that requested it.
    private fun runDetectionOnImage(uri: Uri) {
        val binding = fragmentGalleryBinding
        val appContext = requireContext().applicationContext
        val host = requireActivity()
        setUiEnabled(false)
        updateDisplayView(MediaType.IMAGE)

        fun showFailure() = host.runOnUiThread {
            if (_fragmentGalleryBinding !== binding) return@runOnUiThread
            clearPendingViewResult()
            setUiEnabled(true)
            Toast.makeText(appContext, "Não foi possível analisar a foto. Tente novamente.", Toast.LENGTH_LONG).show()
        }

        val bitmap = try {
            val image = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(appContext.contentResolver, uri))
            } else {
                MediaStore.Images.Media.getBitmap(appContext.contentResolver, uri)
            }
            requireNotNull(image.copy(Bitmap.Config.ARGB_8888, true))
        } catch (error: Exception) {
            Log.e(TAG, "Unable to read image", error)
            showFailure()
            return
        }
        binding.imageResult.setImageBitmap(bitmap)
        val executor = Executors.newSingleThreadScheduledExecutor()
        backgroundExecutor = executor
        executor.execute {
            var helper: PoseLandmarkerHelper? = null
            try {
                helper = PoseLandmarkerHelper(
                    context = appContext,
                    runningMode = RunningMode.IMAGE,
                    currentModel = PoseLandmarkerHelper.MODEL_POSE_LANDMARKER_FULL,
                    currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU
                )
                var detection = helper.detectImage(bitmap)
                if (detection?.results?.firstOrNull()?.landmarks()?.isEmpty() != false) {
                    helper.clearPoseLandmarker()
                    helper = PoseLandmarkerHelper(
                        context = appContext,
                        runningMode = RunningMode.IMAGE,
                        currentModel = PoseLandmarkerHelper.MODEL_POSE_LANDMARKER_HEAVY,
                        currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU
                    )
                    detection = helper.detectImage(bitmap)
                }
                val result = detection
                if (result == null) {
                    showFailure()
                } else host.runOnUiThread {
                    if (_fragmentGalleryBinding !== binding) return@runOnUiThread
                    val pose = result.results.firstOrNull()
                    if (pose == null || pose.landmarks().isEmpty()) {
                        clearPendingViewResult()
                        setUiEnabled(true)
                        binding.bottomSheetLayout.ergonomicResultLabel.text = "RULA: nenhuma postura encontrada nessa foto"
                        return@runOnUiThread
                    }
                    val snapshot = ViewSnapshot(bitmap, pose)
                    if (pendingRulaView == RulaView.FRONTAL) frontalSnapshot = snapshot
                    else lateralSnapshot = snapshot
                    showSnapshot(snapshot)
                    updateAngleLabels(pose)
                    setUiEnabled(true)
                    binding.bottomSheetLayout.inferenceTimeVal.text = String.format("%d ms", result.inferenceTime)
                }
            } catch (error: Exception) {
                Log.e(TAG, "Unable to analyze image", error)
                showFailure()
            } finally {
                helper?.clearPoseLandmarker()
                executor.shutdown()
            }
        }
    }

    private fun runDetectionOnVideo(uri: Uri) {
        val binding = fragmentGalleryBinding
        val appContext = requireContext().applicationContext
        val host = requireActivity()
        setUiEnabled(false)
        updateDisplayView(MediaType.VIDEO)

        with(fragmentGalleryBinding.videoView) {
            setVideoURI(uri)
            // mute the audio
            setOnPreparedListener { it.setVolume(0f, 0f) }
            requestFocus()
        }

        backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
        val executor = backgroundExecutor
        executor.execute {

            val videoHelper =
                PoseLandmarkerHelper(
                    context = appContext,
                    runningMode = RunningMode.VIDEO,
                    minPoseDetectionConfidence = PoseLandmarkerHelper.DEFAULT_POSE_DETECTION_CONFIDENCE,
                    minPoseTrackingConfidence = PoseLandmarkerHelper.DEFAULT_POSE_TRACKING_CONFIDENCE,
                    minPosePresenceConfidence = PoseLandmarkerHelper.DEFAULT_POSE_PRESENCE_CONFIDENCE,
                    currentModel = PoseLandmarkerHelper.MODEL_POSE_LANDMARKER_FULL,
                    currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU
                )

            host.runOnUiThread {
                if (_fragmentGalleryBinding !== binding) return@runOnUiThread
                fragmentGalleryBinding.videoView.visibility = View.GONE
                fragmentGalleryBinding.progress.visibility = View.VISIBLE
            }

            videoHelper.detectVideoFile(uri, VIDEO_INTERVAL_MS)
                ?.let { resultBundle ->
                    host.runOnUiThread {
                        if (_fragmentGalleryBinding !== binding) return@runOnUiThread
                        displayVideoResult(resultBundle, executor)
                    }
                }
                ?: run { Log.e(TAG, "Error running pose landmarker.") }

            videoHelper.clearPoseLandmarker()
        }
    }

    // Setup and display the video.
    private fun displayVideoResult(result: PoseLandmarkerHelper.ResultBundle, executor: ScheduledExecutorService) {
        val binding = fragmentGalleryBinding

        fragmentGalleryBinding.videoView.visibility = View.VISIBLE
        fragmentGalleryBinding.progress.visibility = View.GONE

        fragmentGalleryBinding.videoView.start()
        val videoStartTimeMs = SystemClock.uptimeMillis()

        executor.scheduleAtFixedRate(
            {
                activity?.runOnUiThread {
                    if (_fragmentGalleryBinding !== binding) return@runOnUiThread
                    val videoElapsedTimeMs =
                        SystemClock.uptimeMillis() - videoStartTimeMs
                    val resultIndex =
                        videoElapsedTimeMs.div(VIDEO_INTERVAL_MS).toInt()

                    if (resultIndex >= result.results.size || fragmentGalleryBinding.videoView.visibility == View.GONE) {
                        // The video playback has finished so we stop drawing bounding boxes
                        executor.shutdown()
                    } else {
                        fragmentGalleryBinding.overlay.setResults(
                            result.results[resultIndex],
                            result.inputImageHeight,
                            result.inputImageWidth,
                            RunningMode.VIDEO
                        )
                        updateAngleLabels(result.results[resultIndex])

                        setUiEnabled(true)

                        fragmentGalleryBinding.bottomSheetLayout.inferenceTimeVal.text =
                            String.format("%d ms", result.inferenceTime)
                    }
                }
            },
            0,
            VIDEO_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun updateAngleLabels(
        result: PoseLandmarkerResult,
        capturedAngles: com.google.mediapipe.examples.poselandmarker.PoseAngles? = null
    ) {
        val angles = capturedAngles ?: PoseAngleCalculator.calculate(result)
        clearOwasObservation()
        if (angles != null) {
            rulaSession = when (pendingRulaView) {
                RulaView.FRONTAL -> rulaSession.copy(frontal = angles)
                RulaView.LATERAL -> rulaSession.copy(lateral = angles)
            }
            fragmentGalleryBinding.selectFrontalButton.text =
                if (rulaSession.frontal != null) "Frontal ✓" else "Foto frontal"
            fragmentGalleryBinding.selectLateralButton.text =
                if (rulaSession.lateral != null) "Lateral ✓" else "Foto lateral"
            latestAngles = rulaSession.combinedAngles()
            latestAngles?.let {
                rulaAdjustments = RulaAdjustmentsDialog.suggestions(it)
            }
        } else {
            clearPendingViewResult()
        }

        updatePhotoControls()
        if (latestAngles == null) {
            fragmentGalleryBinding.bottomSheetLayout.ergonomicResultLabel.text = twoViewStatus(angles != null)
        }
        refreshErgonomicLabel()

        fragmentGalleryBinding.bottomSheetLayout.leftShoulderLabel.text =
            latestAngles?.takeIf { it.leftArmDetected }?.let {
                String.format(Locale.US, "Ombro esquerdo: %.1f°", it.leftShoulder)
            } ?: "Ombro esquerdo: --"
        fragmentGalleryBinding.bottomSheetLayout.rightShoulderLabel.text =
            latestAngles?.takeIf { it.rightArmDetected }?.let {
                String.format(Locale.US, "Ombro direito: %.1f°", it.rightShoulder)
            } ?: "Ombro direito: --"
        fragmentGalleryBinding.bottomSheetLayout.leftElbowLabel.text =
            latestAngles?.takeIf { it.leftArmDetected }?.let {
                String.format(Locale.US, "Cotovelo esquerdo: %.1f°", it.leftElbow)
            } ?: "Cotovelo esquerdo: --"
        fragmentGalleryBinding.bottomSheetLayout.rightElbowLabel.text =
            latestAngles?.takeIf { it.rightArmDetected }?.let {
                String.format(Locale.US, "Cotovelo direito: %.1f°", it.rightElbow)
            } ?: "Cotovelo direito: --"
    }

    private fun twoViewStatus(lastPhotoWasValid: Boolean): String {
        if (!lastPhotoWasValid) return "Nenhuma postura detectada. Tente outra foto com o corpo visível."
        val missing = when {
            rulaSession.frontal == null -> "frontal"
            rulaSession.lateral == null -> "lateral"
            else -> return "Combinando as duas fotos…"
        }
        return "Foto salva. Adicione também a foto $missing para ver RULA e REBA."
    }

    private fun clearPendingViewResult() {
        clearOwasObservation()
        if (pendingRulaView == RulaView.FRONTAL) {
            frontalSnapshot = null
            rulaSession = rulaSession.copy(frontal = null)
            fragmentGalleryBinding.selectFrontalButton.text = "Frontal"
        } else {
            lateralSnapshot = null
            rulaSession = rulaSession.copy(lateral = null)
            fragmentGalleryBinding.selectLateralButton.text = "Lateral"
        }
        latestAngles = rulaSession.combinedAngles()
        updatePhotoControls()
        refreshErgonomicLabel()
        fragmentGalleryBinding.overlay.clear()
    }

    private fun clearOwasObservation() {
        owasObservation = null
        fragmentGalleryBinding.bottomSheetLayout.owasResultLabel.text = "Sua classificação aparecerá aqui."
    }

    private fun ergonomicInputs() = ErgonomicInputs(
        repeatedOrStatic = fragmentGalleryBinding.bottomSheetLayout.repeatedStaticInput.isChecked,
        legsUnsupported = fragmentGalleryBinding.bottomSheetLayout.legsUnsupportedInput.isChecked,
        poorCoupling = fragmentGalleryBinding.bottomSheetLayout.poorCouplingInput.isChecked,
        loadKg = fragmentGalleryBinding.bottomSheetLayout.loadKgInput.text.toString()
            .replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
        adjustments = rulaAdjustments
    )

    private fun refreshErgonomicLabel() {
        val sheet = fragmentGalleryBinding.bottomSheetLayout
        val angles = latestAngles
        sheet.saveAssessmentButton.isEnabled = angles != null && !imageProcessing && !savingAssessment && validLoad()
        sheet.loadKgInput.error = if (validLoad()) null else getString(R.string.assessment_invalid_load)
        sheet.resultsSummary.visibility = if (angles == null) View.GONE else View.VISIBLE
        sheet.detailsButton.visibility = if (angles == null) View.GONE else View.VISIBLE
        sheet.ergonomicResultLabel.visibility =
            if (angles == null || showAssessmentDetails) View.VISIBLE else View.GONE
        sheet.detailsButton.text = if (showAssessmentDetails) "Ocultar detalhes" else "Ver detalhes da avaliação"
        if (angles == null) return
        val result = ErgonomicAssessment.calculate(angles, ergonomicInputs())
        val score = result.rula.worst.final
        sheet.rulaSummary.text = buildSpannedString {
            bold { append("RULA\n") }
            scale(1.5f) { bold { append("${score.score}/7  ·  ${score.risk}\n") } }
            append("${score.action}\n")
            append("Esquerdo: ${result.rula.left?.final?.score ?: "não avaliado"}  ·  Direito: ${result.rula.right?.final?.score ?: "não avaliado"}")
        }
        sheet.rebaSummary.text = buildSpannedString {
            bold { append("REBA simplificado\n") }
            scale(1.5f) { bold { append("${result.reba.score}/15  ·  ${result.reba.risk}\n") } }
            append("${result.reba.action}\nRequer avaliação completa do método.")
        }
        sheet.ergonomicResultLabel.text = result.displayText()
    }

    private fun updateDisplayView(mediaType: MediaType) {
        fragmentGalleryBinding.imageResult.visibility =
            if (mediaType == MediaType.IMAGE) View.VISIBLE else View.GONE
        fragmentGalleryBinding.videoView.visibility =
            if (mediaType == MediaType.VIDEO) View.VISIBLE else View.GONE
        fragmentGalleryBinding.tvPlaceholder.visibility =
            if (mediaType == MediaType.UNKNOWN) View.VISIBLE else View.GONE
    }

    // Check the type of media that user selected.
    private fun loadMediaType(uri: Uri): MediaType {
        val mimeType = context?.contentResolver?.getType(uri)
        mimeType?.let {
            if (mimeType.startsWith("image")) return MediaType.IMAGE
            if (mimeType.startsWith("video")) return MediaType.VIDEO
        }

        return MediaType.UNKNOWN
    }

    private fun setUiEnabled(enabled: Boolean) {
        imageProcessing = !enabled
        fragmentGalleryBinding.toggleCameraButton.isEnabled = enabled
        fragmentGalleryBinding.captureButton.isEnabled = enabled && cameraActive
        fragmentGalleryBinding.bottomSheetLayout.saveAssessmentButton.isEnabled =
            enabled && latestAngles != null && !savingAssessment && validLoad()
        fragmentGalleryBinding.selectFrontalButton.isEnabled = enabled
        fragmentGalleryBinding.selectLateralButton.isEnabled = enabled
        fragmentGalleryBinding.addOrReplacePhotoButton.isEnabled = enabled
        fragmentGalleryBinding.bottomSheetLayout.detectionThresholdMinus.isEnabled =
            enabled
        fragmentGalleryBinding.bottomSheetLayout.detectionThresholdPlus.isEnabled =
            enabled
        fragmentGalleryBinding.bottomSheetLayout.trackingThresholdMinus.isEnabled =
            enabled
        fragmentGalleryBinding.bottomSheetLayout.trackingThresholdPlus.isEnabled =
            enabled
        fragmentGalleryBinding.bottomSheetLayout.presenceThresholdMinus.isEnabled =
            enabled
        fragmentGalleryBinding.bottomSheetLayout.presenceThresholdPlus.isEnabled =
            enabled
        fragmentGalleryBinding.bottomSheetLayout.spinnerDelegate.isEnabled =
            enabled
    }

    private fun classifyingError() {
        val binding = _fragmentGalleryBinding ?: return
        activity?.runOnUiThread {
            if (_fragmentGalleryBinding !== binding) return@runOnUiThread
            fragmentGalleryBinding.progress.visibility = View.GONE
            setUiEnabled(true)
            updateDisplayView(MediaType.UNKNOWN)
        }
    }

    override fun onError(error: String, errorCode: Int) {
        val binding = _fragmentGalleryBinding ?: return
        classifyingError()
        activity?.runOnUiThread {
            if (_fragmentGalleryBinding !== binding) return@runOnUiThread
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == PoseLandmarkerHelper.GPU_ERROR) {
                fragmentGalleryBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    PoseLandmarkerHelper.DELEGATE_CPU,
                    false
                )
            }
        }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        // no-op
    }

    companion object {
        private const val TAG = "GalleryFragment"
        const val CAPTURE_RESULT_KEY = "captured_pose_photo"
        const val CAPTURE_URI_KEY = "captured_pose_uri"
        const val CAPTURE_VIEW_KEY = "captured_pose_view"

        // Value used to get frames at specific intervals for inference (e.g. every 300ms)
        private const val VIDEO_INTERVAL_MS = 300L
    }
}
