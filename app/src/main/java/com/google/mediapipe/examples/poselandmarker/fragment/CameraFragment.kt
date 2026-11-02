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

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.core.widget.doAfterTextChanged
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import com.google.mediapipe.examples.poselandmarker.PoseLandmarkerHelper
import com.google.mediapipe.examples.poselandmarker.PoseAngleCalculator
import com.google.mediapipe.examples.poselandmarker.PoseAngles
import com.google.mediapipe.examples.poselandmarker.ErgonomicAssessment
import com.google.mediapipe.examples.poselandmarker.ErgonomicInputs
import com.google.mediapipe.examples.poselandmarker.displayText
import com.google.mediapipe.examples.poselandmarker.RulaAdjustments
import com.google.mediapipe.examples.poselandmarker.RulaAdjustmentsDialog
import com.google.mediapipe.examples.poselandmarker.MainViewModel
import com.google.mediapipe.examples.poselandmarker.CapturedPoseFrame
import com.google.mediapipe.examples.poselandmarker.CapturedPoseStore
import com.google.mediapipe.examples.poselandmarker.R
import com.google.mediapipe.examples.poselandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
import java.io.FileOutputStream

class CameraFragment : Fragment(), PoseLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Pose Landmarker"
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_BACK
    private var frontalCamera = CameraSelector.LENS_FACING_FRONT
    private var smoothedAngles: PoseAngles? = null
    private var rulaAdjustments = RulaAdjustments()
    private var suggestionsInitialized = false
    private var latestAnalyzedBitmap: android.graphics.Bitmap? = null
    private var latestAnalyzedResult: PoseLandmarkerResult? = null

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        // Start the PoseLandmarkerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if(this::poseLandmarkerHelper.isInitialized) {
                if (poseLandmarkerHelper.isClose()) {
                    poseLandmarkerHelper.setupPoseLandmarker()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if(this::poseLandmarkerHelper.isInitialized) {
            viewModel.setMinPoseDetectionConfidence(poseLandmarkerHelper.minPoseDetectionConfidence)
            viewModel.setMinPoseTrackingConfidence(poseLandmarkerHelper.minPoseTrackingConfidence)
            viewModel.setMinPosePresenceConfidence(poseLandmarkerHelper.minPosePresenceConfidence)
            viewModel.setDelegate(poseLandmarkerHelper.currentDelegate)

            // Close the PoseLandmarkerHelper and release resources
            backgroundExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        fragmentCameraBinding.buttonSwitchCamera.setOnClickListener {
            cameraFacing =
                if (cameraFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }

            smoothedAngles = null
            latestAnalyzedBitmap = null
            latestAnalyzedResult = null
            bindCameraUseCases()
        }
        fragmentCameraBinding.buttonCapturePose.setOnClickListener {
            captureCurrentFrame()
        }

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {
            // Set up the camera and its use cases
            setUpCamera()
        }

        // Create the PoseLandmarkerHelper that will handle the inference
        backgroundExecutor.execute {
            poseLandmarkerHelper = PoseLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minPoseDetectionConfidence = PoseLandmarkerHelper.DEFAULT_POSE_DETECTION_CONFIDENCE,
                minPoseTrackingConfidence = PoseLandmarkerHelper.DEFAULT_POSE_TRACKING_CONFIDENCE,
                minPosePresenceConfidence = PoseLandmarkerHelper.DEFAULT_POSE_PRESENCE_CONFIDENCE,
                currentModel = PoseLandmarkerHelper.MODEL_POSE_LANDMARKER_FULL,
                currentDelegate = PoseLandmarkerHelper.DELEGATE_CPU,
                poseLandmarkerHelperListener = this
            )
        }

        // Attach listeners to UI control widgets
        initBottomSheetControls()
        fragmentCameraBinding.bottomSheetLayout.rulaAdjustmentsButton.setOnClickListener {
            RulaAdjustmentsDialog.show(requireContext(), rulaAdjustments) {
                rulaAdjustments = it
                refreshErgonomicLabel()
            }
        }
        fragmentCameraBinding.bottomSheetLayout.repeatedStaticInput.setOnCheckedChangeListener { _, _ ->
            refreshErgonomicLabel()
        }
        fragmentCameraBinding.bottomSheetLayout.legsUnsupportedInput.setOnCheckedChangeListener { _, _ ->
            refreshErgonomicLabel()
        }
        fragmentCameraBinding.bottomSheetLayout.poorCouplingInput.setOnCheckedChangeListener { _, _ ->
            refreshErgonomicLabel()
        }
        fragmentCameraBinding.bottomSheetLayout.loadKgInput.doAfterTextChanged {
            refreshErgonomicLabel()
        }
    }

    private fun captureCurrentFrame() {
        val bitmap = latestAnalyzedBitmap
        val result = latestAnalyzedResult
        val angles = smoothedAngles
        if (bitmap == null || result == null || angles == null) {
            Toast.makeText(requireContext(), "Aguarde a detecção da postura.", Toast.LENGTH_SHORT).show()
            return
        }

        fragmentCameraBinding.buttonCapturePose.isEnabled = false
        fragmentCameraBinding.captureStatus.text = "Salvando foto..."
        backgroundExecutor.execute {
            val directory = File(requireContext().cacheDir, "assessment_photos")
            if (!directory.exists()) directory.mkdirs()
            val photo = File.createTempFile("rula_capture_", ".jpg", directory)
            FileOutputStream(photo).use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, stream)
            }
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                photo
            )
            CapturedPoseStore.put(uri.toString(), CapturedPoseFrame(bitmap, result, angles))
            activity?.runOnUiThread {
                parentFragmentManager.setFragmentResult(
                    GalleryFragment.CAPTURE_RESULT_KEY,
                    bundleOf(
                        GalleryFragment.CAPTURE_URI_KEY to uri.toString(),
                        GalleryFragment.CAPTURE_VIEW_KEY to
                            (arguments?.getString(GalleryFragment.CAPTURE_VIEW_KEY) ?: "frontal")
                    )
                )
                findNavController().popBackStack()
            }
        }
    }

    private fun initBottomSheetControls() {
        // init bottom sheet settings

        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPoseDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPoseTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinPosePresenceConfidence
            )

        // When clicked, lower pose detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (poseLandmarkerHelper.minPoseDetectionConfidence >= 0.2) {
                poseLandmarkerHelper.minPoseDetectionConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise pose detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (poseLandmarkerHelper.minPoseDetectionConfidence <= 0.8) {
                poseLandmarkerHelper.minPoseDetectionConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower pose tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (poseLandmarkerHelper.minPoseTrackingConfidence >= 0.2) {
                poseLandmarkerHelper.minPoseTrackingConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise pose tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (poseLandmarkerHelper.minPoseTrackingConfidence <= 0.8) {
                poseLandmarkerHelper.minPoseTrackingConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower pose presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (poseLandmarkerHelper.minPosePresenceConfidence >= 0.2) {
                poseLandmarkerHelper.minPosePresenceConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise pose presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (poseLandmarkerHelper.minPosePresenceConfidence <= 0.8) {
                poseLandmarkerHelper.minPosePresenceConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference.
        // Current options are CPU and GPU
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate, false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    try {
                        poseLandmarkerHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch(e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "PoseLandmarkerHelper has not been initialized yet.")
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }

        // When clicked, change the underlying model used for object detection
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.setSelection(
            viewModel.currentModel,
            false
        )
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.onItemSelectedListener =
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

    // Update the values displayed in the bottom sheet. Reset Poselandmarker
    // helper.
    private fun updateControlsUi() {
        if(this::poseLandmarkerHelper.isInitialized) {
            fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
                String.format(
                    Locale.US,
                    "%.2f",
                    poseLandmarkerHelper.minPoseDetectionConfidence
                )
            fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
                String.format(
                    Locale.US,
                    "%.2f",
                    poseLandmarkerHelper.minPoseTrackingConfidence
                )
            fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
                String.format(
                    Locale.US,
                    "%.2f",
                    poseLandmarkerHelper.minPosePresenceConfidence
                )

            // Needs to be cleared instead of reinitialized because the GPU
            // delegate needs to be initialized on the thread using it when applicable
            backgroundExecutor.execute {
                poseLandmarkerHelper.clearPoseLandmarker()
                poseLandmarkerHelper.setupPoseLandmarker()
            }
            fragmentCameraBinding.overlay.clear()
        }
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            )
            .build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        detectPose(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectPose(imageProxy: ImageProxy) {
        if(this::poseLandmarkerHelper.isInitialized) {
            poseLandmarkerHelper.detectLiveStream(
                imageProxy = imageProxy,
                isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after pose have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView
    override fun onResults(
        resultBundle: PoseLandmarkerHelper.ResultBundle
    ) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                val result = resultBundle.results.firstOrNull()
                if (result == null || result.landmarks().isEmpty()) {
                    smoothedAngles = null
                    fragmentCameraBinding.buttonCapturePose.isEnabled = false
                    fragmentCameraBinding.captureStatus.text = "Nenhuma postura detectada"
                    return@runOnUiThread
                }

                fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                    String.format("%d ms", resultBundle.inferenceTime)

                // Pass necessary information to OverlayView for drawing on the canvas
                fragmentCameraBinding.overlay.setResults(
                    result,
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    RunningMode.LIVE_STREAM
                )

                val measured = PoseAngleCalculator.calculate(result)
                val capturable = measured != null && resultBundle.inputBitmap != null
                fragmentCameraBinding.buttonCapturePose.isEnabled = capturable
                fragmentCameraBinding.captureStatus.text =
                    if (capturable) "Postura detectada — pode capturar"
                    else "Mostre ombros, quadris e pelo menos um braço"

                measured?.let { measuredAngles ->
                    resultBundle.inputBitmap?.let { latestAnalyzedBitmap = it }
                    latestAnalyzedResult = result
                    if (!suggestionsInitialized) {
                        rulaAdjustments = RulaAdjustmentsDialog.suggestions(measuredAngles)
                        suggestionsInitialized = true
                    }
                    val angles = smoothAngles(measuredAngles)
                    fragmentCameraBinding.bottomSheetLayout.leftShoulderLabel.text =
                        if (angles.leftArmDetected) String.format(Locale.US, "Ombro esquerdo: %.1f°", angles.leftShoulder) else "Ombro esquerdo: --"
                    fragmentCameraBinding.bottomSheetLayout.rightShoulderLabel.text =
                        if (angles.rightArmDetected) String.format(Locale.US, "Ombro direito: %.1f°", angles.rightShoulder) else "Ombro direito: --"
                    fragmentCameraBinding.bottomSheetLayout.leftElbowLabel.text =
                        if (angles.leftArmDetected) String.format(Locale.US, "Cotovelo esquerdo: %.1f°", angles.leftElbow) else "Cotovelo esquerdo: --"
                    fragmentCameraBinding.bottomSheetLayout.rightElbowLabel.text =
                        if (angles.rightArmDetected) String.format(Locale.US, "Cotovelo direito: %.1f°", angles.rightElbow) else "Cotovelo direito: --"
                }

                smoothedAngles?.let { angles ->
                    val ergonomic = ErgonomicAssessment.calculate(angles, ergonomicInputs())
                    fragmentCameraBinding.bottomSheetLayout.ergonomicResultLabel.text = ergonomic.displayText()
                }

                // Force a redraw
                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

    private fun smoothAngles(measured: PoseAngles): PoseAngles {
        val previous = smoothedAngles
        if (previous == null) {
            smoothedAngles = measured
            return measured
        }

        // Lower values make the display steadier; higher values react faster.
        val alpha = 0.35
        fun filter(old: Double, new: Double) = old + alpha * (new - old)

        return PoseAngles(
            leftShoulder = filter(previous.leftShoulder, measured.leftShoulder),
            rightShoulder = filter(previous.rightShoulder, measured.rightShoulder),
            leftElbow = filter(previous.leftElbow, measured.leftElbow),
            rightElbow = filter(previous.rightElbow, measured.rightElbow),
            leftWrist = filter(previous.leftWrist, measured.leftWrist),
            rightWrist = filter(previous.rightWrist, measured.rightWrist),
            neck = filter(previous.neck, measured.neck),
            trunk = filter(previous.trunk, measured.trunk),
            leftKnee = filter(previous.leftKnee, measured.leftKnee),
            rightKnee = filter(previous.rightKnee, measured.rightKnee),
            legsDetected = measured.legsDetected,
            suggestedLeftArmAbducted = measured.suggestedLeftArmAbducted,
            suggestedRightArmAbducted = measured.suggestedRightArmAbducted,
            suggestedNeckSideBending = measured.suggestedNeckSideBending,
            suggestedTrunkSideBending = measured.suggestedTrunkSideBending,
            leftArmDetected = measured.leftArmDetected,
            rightArmDetected = measured.rightArmDetected
        ).also { smoothedAngles = it }
    }

    private fun ergonomicInputs() = ErgonomicInputs(
        repeatedOrStatic = fragmentCameraBinding.bottomSheetLayout.repeatedStaticInput.isChecked,
        legsUnsupported = fragmentCameraBinding.bottomSheetLayout.legsUnsupportedInput.isChecked,
        poorCoupling = fragmentCameraBinding.bottomSheetLayout.poorCouplingInput.isChecked,
        loadKg = fragmentCameraBinding.bottomSheetLayout.loadKgInput.text.toString()
            .replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
        adjustments = rulaAdjustments
    )

    private fun refreshErgonomicLabel() {
        smoothedAngles?.let {
            fragmentCameraBinding.bottomSheetLayout.ergonomicResultLabel.text =
                ErgonomicAssessment.calculate(it, ergonomicInputs()).displayText()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == PoseLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    PoseLandmarkerHelper.DELEGATE_CPU, false
                )
            }
        }
    }
}
