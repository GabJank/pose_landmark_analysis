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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.core.widget.doAfterTextChanged
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.CapturedPoseStore
import com.google.mediapipe.examples.poselandmarker.PoseAngleCalculator
import com.google.mediapipe.examples.poselandmarker.ErgonomicAssessment
import com.google.mediapipe.examples.poselandmarker.ErgonomicInputs
import com.google.mediapipe.examples.poselandmarker.displayText
import com.google.mediapipe.examples.poselandmarker.RulaAdjustments
import com.google.mediapipe.examples.poselandmarker.RulaAdjustmentsDialog
import com.google.mediapipe.examples.poselandmarker.RulaTwoViewSession
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentGalleryBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
    private var rulaAdjustments = RulaAdjustments()
    private var latestAngles: com.google.mediapipe.examples.poselandmarker.PoseAngles? = null
    private var rulaSession = RulaTwoViewSession()
    private var pendingRulaView = RulaView.FRONTAL
    private var frontalSnapshot: ViewSnapshot? = null
    private var lateralSnapshot: ViewSnapshot? = null
    private val fragmentGalleryBinding
        get() = _fragmentGalleryBinding!!
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
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
        fragmentGalleryBinding.addOrReplacePhotoButton.setOnClickListener {
            showPhotoSourceChooser()
        }

        initBottomSheetControls()
    }

    private fun selectView(view: RulaView) {
        pendingRulaView = view
        fragmentGalleryBinding.selectFrontalButton.alpha =
            if (view == RulaView.FRONTAL) 1f else 0.65f
        fragmentGalleryBinding.selectLateralButton.alpha =
            if (view == RulaView.LATERAL) 1f else 0.65f
        fragmentGalleryBinding.addOrReplacePhotoButton.text =
            if (view == RulaView.FRONTAL)
                "Adicionar/substituir foto frontal"
            else "Adicionar/substituir foto lateral"

        val snapshot = if (view == RulaView.FRONTAL) frontalSnapshot else lateralSnapshot
        if (snapshot == null) {
            showPhotoSourceChooser()
        } else {
            showSnapshot(snapshot)
        }
    }

    private fun showSnapshot(snapshot: ViewSnapshot) {
        updateDisplayView(MediaType.IMAGE)
        fragmentGalleryBinding.imageResult.setImageBitmap(snapshot.bitmap)
        fragmentGalleryBinding.overlay.post {
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
                if (option == 0) launchCameraCapture() else getContent.launch(arrayOf("image/*"))
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

    private fun launchCameraCaptureInternal() {
        findNavController().navigate(
            R.id.action_gallery_to_capture,
            bundleOf(
                CAPTURE_VIEW_KEY to if (pendingRulaView == RulaView.FRONTAL) "frontal" else "lateral"
            )
        )
    }

    override fun onPause() {
        if (fragmentGalleryBinding.videoView.isPlaying) {
            fragmentGalleryBinding.videoView.stopPlayback()
        }
        fragmentGalleryBinding.videoView.visibility = View.GONE
        super.onPause()
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
                    poseLandmarkerHelper.currentModel = p2
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

    // Load and display the image.
    private fun runDetectionOnImage(uri: Uri) {
        setUiEnabled(false)
        backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
        updateDisplayView(MediaType.IMAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(
                requireActivity().contentResolver,
                uri
            )
            ImageDecoder.decodeBitmap(source)
        } else {
            MediaStore.Images.Media.getBitmap(
                requireActivity().contentResolver,
                uri
            )
        }
            .copy(Bitmap.Config.ARGB_8888, true)
            ?.let { bitmap ->
                fragmentGalleryBinding.imageResult.setImageBitmap(bitmap)

                // Run pose landmarker on the input image
                backgroundExecutor.execute {

                    poseLandmarkerHelper =
                        PoseLandmarkerHelper(
                            context = requireContext(),
                            runningMode = RunningMode.IMAGE,
                            minPoseDetectionConfidence = PoseLandmarkerHelper.DEFAULT_POSE_DETECTION_CONFIDENCE,
                            minPoseTrackingConfidence = PoseLandmarkerHelper.DEFAULT_POSE_TRACKING_CONFIDENCE,
                            minPosePresenceConfidence = PoseLandmarkerHelper.DEFAULT_POSE_PRESENCE_CONFIDENCE,
                            currentModel = PoseLandmarkerHelper.MODEL_POSE_LANDMARKER_FULL,
                            currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU
                        )

                    var detectionResult = poseLandmarkerHelper.detectImage(bitmap)
                    val firstAttemptEmpty = detectionResult?.results?.firstOrNull()
                        ?.landmarks()?.isEmpty() != false
                    if (firstAttemptEmpty) {
                        poseLandmarkerHelper.clearPoseLandmarker()
                        poseLandmarkerHelper = PoseLandmarkerHelper(
                            context = requireContext(),
                            runningMode = RunningMode.IMAGE,
                            minPoseDetectionConfidence = PoseLandmarkerHelper.DEFAULT_POSE_DETECTION_CONFIDENCE,
                            minPoseTrackingConfidence = PoseLandmarkerHelper.DEFAULT_POSE_TRACKING_CONFIDENCE,
                            minPosePresenceConfidence = PoseLandmarkerHelper.DEFAULT_POSE_PRESENCE_CONFIDENCE,
                            currentModel = PoseLandmarkerHelper.MODEL_POSE_LANDMARKER_HEAVY,
                            currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU
                        )
                        detectionResult = poseLandmarkerHelper.detectImage(bitmap)
                    }

                    detectionResult?.let { result ->
                        activity?.runOnUiThread {
                            val poseResult = result.results.firstOrNull()
                            if (poseResult == null || poseResult.landmarks().isEmpty()) {
                                clearPendingViewResult()
                                setUiEnabled(true)
                                fragmentGalleryBinding.bottomSheetLayout.ergonomicResultLabel.text =
                                    "RULA: nenhuma postura encontrada nessa foto"
                                return@runOnUiThread
                            }

                            val snapshot = ViewSnapshot(bitmap, poseResult)
                            if (pendingRulaView == RulaView.FRONTAL) {
                                frontalSnapshot = snapshot
                            } else {
                                lateralSnapshot = snapshot
                            }
                            showSnapshot(snapshot)
                            updateAngleLabels(poseResult)

                            setUiEnabled(true)
                            fragmentGalleryBinding.bottomSheetLayout.inferenceTimeVal.text =
                                String.format("%d ms", result.inferenceTime)
                        }
                    } ?: run { Log.e(TAG, "Error running pose landmarker.") }

                    poseLandmarkerHelper.clearPoseLandmarker()
                }
            }
    }

    private fun runDetectionOnVideo(uri: Uri) {
        setUiEnabled(false)
        updateDisplayView(MediaType.VIDEO)

        with(fragmentGalleryBinding.videoView) {
            setVideoURI(uri)
            // mute the audio
            setOnPreparedListener { it.setVolume(0f, 0f) }
            requestFocus()
        }

        backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
        backgroundExecutor.execute {

            poseLandmarkerHelper =
                PoseLandmarkerHelper(
                    context = requireContext(),
                    runningMode = RunningMode.VIDEO,
                    minPoseDetectionConfidence = PoseLandmarkerHelper.DEFAULT_POSE_DETECTION_CONFIDENCE,
                    minPoseTrackingConfidence = PoseLandmarkerHelper.DEFAULT_POSE_TRACKING_CONFIDENCE,
                    minPosePresenceConfidence = PoseLandmarkerHelper.DEFAULT_POSE_PRESENCE_CONFIDENCE,
                    currentModel = PoseLandmarkerHelper.MODEL_POSE_LANDMARKER_FULL,
                    currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU
                )

            activity?.runOnUiThread {
                fragmentGalleryBinding.videoView.visibility = View.GONE
                fragmentGalleryBinding.progress.visibility = View.VISIBLE
            }

            poseLandmarkerHelper.detectVideoFile(uri, VIDEO_INTERVAL_MS)
                ?.let { resultBundle ->
                    activity?.runOnUiThread { displayVideoResult(resultBundle) }
                }
                ?: run { Log.e(TAG, "Error running pose landmarker.") }

            poseLandmarkerHelper.clearPoseLandmarker()
        }
    }

    // Setup and display the video.
    private fun displayVideoResult(result: PoseLandmarkerHelper.ResultBundle) {

        fragmentGalleryBinding.videoView.visibility = View.VISIBLE
        fragmentGalleryBinding.progress.visibility = View.GONE

        fragmentGalleryBinding.videoView.start()
        val videoStartTimeMs = SystemClock.uptimeMillis()

        backgroundExecutor.scheduleAtFixedRate(
            {
                activity?.runOnUiThread {
                    val videoElapsedTimeMs =
                        SystemClock.uptimeMillis() - videoStartTimeMs
                    val resultIndex =
                        videoElapsedTimeMs.div(VIDEO_INTERVAL_MS).toInt()

                    if (resultIndex >= result.results.size || fragmentGalleryBinding.videoView.visibility == View.GONE) {
                        // The video playback has finished so we stop drawing bounding boxes
                        backgroundExecutor.shutdown()
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

        fragmentGalleryBinding.bottomSheetLayout.ergonomicResultLabel.text = latestAngles?.let {
            val ergonomic = ErgonomicAssessment.calculate(it, ergonomicInputs())
            "Sessão frontal + lateral completa\n${ergonomic.displayText()}"
        } ?: twoViewStatus(angles != null)

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
        if (!lastPhotoWasValid) return "RULA: postura não detectada nessa foto"
        val missing = when {
            rulaSession.frontal == null -> "frontal"
            rulaSession.lateral == null -> "lateral"
            else -> return "RULA: combinando as duas vistas"
        }
        return "RULA: foto salva; selecione também a foto $missing"
    }

    private fun clearPendingViewResult() {
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
        fragmentGalleryBinding.overlay.clear()
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
        latestAngles?.let {
            fragmentGalleryBinding.bottomSheetLayout.ergonomicResultLabel.text =
                ErgonomicAssessment.calculate(it, ergonomicInputs()).displayText()
        }
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
        activity?.runOnUiThread {
            fragmentGalleryBinding.progress.visibility = View.GONE
            setUiEnabled(true)
            updateDisplayView(MediaType.UNKNOWN)
        }
    }

    override fun onError(error: String, errorCode: Int) {
        classifyingError()
        activity?.runOnUiThread {
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
