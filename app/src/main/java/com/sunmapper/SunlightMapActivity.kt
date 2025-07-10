package com.sunmapper


import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.media.Image
import java.io.ByteArrayOutputStream


// coroutines & lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Anchor
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.Scene
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.rendering.ViewRenderable
import com.google.ar.sceneform.rendering.RenderableDefinition
import com.google.ar.sceneform.rendering.Vertex

import com.google.ar.sceneform.ux.ArFragment
import kotlin.math.sqrt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.util.Locale
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlin.math.min
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

import com.google.ar.core.Config
import android.graphics.Color as AndroidColor

import com.google.ar.sceneform.collision.Ray
import java.time.LocalDate
import java.time.LocalTime

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.opengl.Matrix
import io.ktor.server.netty.NettyApplicationEngine
import kotlin.math.abs


/** Inverse (conjugate) of a unit quaternion */
fun Quaternion.inverse(): Quaternion =
    Quaternion( -x, -y, -z, w )

/** Rotate a Vector3 by this quaternion */
fun Quaternion.rotate(vec: Vector3): Vector3 =
    Quaternion.rotateVector(this, vec)



class SunlightMapActivity : AppCompatActivity(), Scene.OnUpdateListener,View.OnClickListener

{
    private lateinit var arFragment: ArFragment

    //    private lateinit var unitDropDown: Spinner
    private val units: Array<String> = arrayOf(UNIT_CENTIMETER, UNIT_METER, UNIT_FEET, UNIT_INCHES)
    private val cursorAnchor = mutableListOf<WrappedAnchor>()
    private lateinit var dockedAnchor: WrappedAnchor

    // throttle state:
    private var lastCaptureMs = 0L



    private lateinit var defaultAnchor: ModelRenderable //new anchor
    private lateinit var floorAnchor: ModelRenderable

    private var cursorRenderer: ModelRenderable? = null
    lateinit var anchorNode: AnchorNode
    var dockedAnchorNode: AnchorNode? = null
    lateinit var lineNode: Node
    var textNode: AnchorNode? = null
    lateinit var frame: Frame
    lateinit var hitResultList: List<HitResult>
    var firstHitResult: HitResult? = null
    var point1: Vector3? = null
    var point2: Vector3? = null


    var freezeAnchors = false
    var distanceCardViewRenderer: ViewRenderable? = null
    private var distanceWithUnits = ""
    private var distanceInMeters = 0f

    // How long (ms) a candidate must stick around before we trust it:
    private val PLANE_STABLE_THRESHOLD = 1_500L

    // The last ARCore Plane we saw on any frame (or null)
    private var candidatePlane: Plane? = null
    private var candidateSince: Long = 0L


    // The one we’ve “locked in” and will serve via /plane
    private var stablePlane: Plane? = null

    private var cameraCalibrated = false

    private lateinit var captureButton: Button
    private var captureJob: Job? = null


    private lateinit var overlayDistance: TextView

    private lateinit var overlay: BBoxOverlayView

    private var cornerRenderable: ModelRenderable? = null
    private val cornerNodes = arrayOfNulls<AnchorNode>(4)
    private val lineNodes = mutableListOf<Node>()
    private var greenLineMaterial: Material? = null



    private var imgW = 0f
    private var imgH = 0f
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val LOCATION_REQUEST = 42
    private lateinit var fusedLocationClient: FusedLocationProviderClient



    private var sunMarkerNode: Node? = null
    private var sunMarkerRenderable: ModelRenderable? = null




    // SensorManager for TYPE_ROTATION_VECTOR
    private lateinit var sensorManager: SensorManager
    private var rotVectorSensor: Sensor? = null




    private var geoAnchorNode: AnchorNode? = null
    private val cardinalDistance = 2f
    private lateinit var cardinalRenderable: ViewRenderable


    private var selectedDate = LocalDate.now()
    private var selectedHour = LocalTime.now().hour
    var selectedMinute = LocalTime.now().minute


    private var floorPatchNode: Node? = null

    private lateinit var nsdManager: NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private lateinit var ktorServer: NettyApplicationEngine


    private val CAMERA_PERMISSION = Manifest.permission.CAMERA
    companion object {
        private const val PERMISSION_REQUEST_CODE = 0x123
    }



    private fun registerMdnsService() {
        nsdManager = getSystemService(NSD_SERVICE) as NsdManager

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.d("NSD", "Registered ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.e("NSD", "Registration failed: $error")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.d("NSD", "Unregistered ${info.serviceName}")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.e("NSD", "Unregistration failed: $error")
            }
        }

        val svc = NsdServiceInfo().apply {
            serviceName = "SunlightMapper"              // your human-readable name
            serviceType = "_sunlight-zone._tcp."        // pick a unique type
            port = 8080                                 // same port as your embeddedServer
        }
        nsdManager.registerService(svc,
            NsdManager.PROTOCOL_DNS_SD,
            registrationListener!!)
    }




    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_measurement)


        arFragment = supportFragmentManager.findFragmentById(R.id.sceneform_fragment) as ArFragment
        arFragment.planeDiscoveryController?.hide()
        arFragment.planeDiscoveryController?.setInstructionView(null)
//        arFragment.planeDiscoveryController?.show()
        arFragment.arSceneView?.scene?.addOnUpdateListener(this)
        arFragment.arSceneView.planeRenderer.isVisible = true //this was commented before


//        findViewById<ImageView>(R.id.dockButton)?.setOnClickListener(this)
//        findViewById<ImageView>(R.id.lockButton)?.setOnClickListener(this)
        findViewById<ImageView>(R.id.clearButton)
            .setOnClickListener {
                SunMapperApp.instance.resetState()
                recreate()
            }

        overlayDistance = findViewById(R.id.overlay_distance)

        captureButton = findViewById(R.id.captureButton)



        overlay = findViewById(R.id.bboxOverlay)





        ViewRenderable.builder()
            .setView(this, R.layout.text_layout)    // reuse your existing text_layout
            .build()
            .thenAccept { renderable ->
                renderable.isShadowCaster = false
                renderable.isShadowReceiver = false
                cardinalRenderable = renderable


            }
            .exceptionally {
                Log.e("Measurement", "Failed to create cardinalRenderable", it)
                null
            }





        MaterialFactory.makeOpaqueWithColor(
            this,
            Color(1f, 1f, 0f)  // pure yellow base
        ).thenAccept { mat ->
            // 1) make it glow by giving it an emissive color
            mat.setFloat3("emissiveFactor", Vector3(1f, 1f, 0f))
            // 2) make it shiny/bright by reducing roughness
            mat.setFloat("roughnessFactor", 0.1f)
            // 3) optionally add a bit of metalness so it reflects light
            mat.setFloat("metallicFactor", 0.2f)

            sunMarkerRenderable = ShapeFactory.makeSphere(
                0.15f,                      // 15 cm radius
                Vector3.zero(),             // at origin; we’ll position it later
                mat
            ).apply {
                isShadowCaster = false
                isShadowReceiver = false
            }
        }



        arFragment.arSceneView.post {
            // compute and store the aspect ratio
            val vw = arFragment.arSceneView.width
            val vh = arFragment.arSceneView.height
            SunMapperApp.instance.viewAspect = vw.toFloat() / vh.toFloat()
        }


        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_REQUEST
            )
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        fusedLocationClient.lastLocation
            .addOnSuccessListener { loc: Location? ->
                loc?.let {
                    SunMapperApp.instance.deviceLat = it.latitude
                    SunMapperApp.instance.deviceLon = it.longitude

                    // Compute magnetic declination
                    val geoField = GeomagneticField(
                        it.latitude.toFloat(),
                        it.longitude.toFloat(),
                        it.altitude.toFloat(),
                        System.currentTimeMillis()
                    )
                    SunMapperApp.instance.magneticDeclination = geoField.declination
                }
            }



        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)


        // 1) Date picker
        val dateButton = findViewById<Button>(R.id.dateButton)
        dateButton.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                    SunMapperApp.instance.selectedDate = selectedDate.toString()

                    lifecycleScope.launch {
                        broadcastHandshake()
                    }
                },
                selectedDate.year,
                selectedDate.monthValue - 1,
                selectedDate.dayOfMonth
            ).show()
        }

        val timeLabel  = findViewById<TextView>(R.id.timeLabel)
        val timeSlider = findViewById<SeekBar>(R.id.timeSlider)

        // initialize to now
        val initProgress = selectedHour * 60 + selectedMinute
        timeSlider.progress = initProgress
        timeLabel.text     = "Time: %02d:%02d".format(selectedHour, selectedMinute)

        timeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, prog: Int, fromUser: Boolean) {
                if (!fromUser) return  // only respond to actual user drags

                // 1) Update local hour/minute
                selectedHour   = prog / 60
                selectedMinute = prog % 60

                // 2) Update label immediately
                timeLabel.text = "Time: %02d:%02d".format(selectedHour, selectedMinute)

                // 3) Sync into singleton & broadcast right away
                SunMapperApp.instance.apply {
                    selectedHour   = this@SunlightMapActivity.selectedHour
                    selectedMinute = this@SunlightMapActivity.selectedMinute
                }
                lifecycleScope.launch {
                    broadcastHandshake()
                }
                val hasFloor   = SunMapperApp.instance.savedFloorPlane != null
                val hasCorners = cornerNodes.count { it != null } >= 4

                if (hasFloor && hasCorners) {
                    updateSunMarker()
                    placeBeam()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { /* no-op */ }
            override fun onStopTrackingTouch(sb: SeekBar) {/* no-op */}
        })





        val nowButton  = findViewById<Button>(R.id.nowButton)
        nowButton.setOnClickListener {
            // 1) Grab the real‐world current local time
            val now = LocalTime.now()
            val prog = now.hour * 60 + now.minute

            // 2) Snap the slider & label
            timeSlider.progress = prog
            selectedHour   = now.hour
            selectedMinute = now.minute
            timeLabel.text = "Time: %02d:%02d".format(selectedHour, selectedMinute)

            // 3) Sync into your singleton
            SunMapperApp.instance.apply {
                selectedHour   = this@SunlightMapActivity.selectedHour
                selectedMinute = this@SunlightMapActivity.selectedMinute
            }

            // 4) Kick off the handshake & immediate redraw
            lifecycleScope.launch {
                broadcastHandshake()
            }
            updateSunMarker()
            // Only draw if we have floor + corners:
            if (SunMapperApp.instance.savedFloorPlane != null &&
                cornerNodes.count { it != null } >= 4) {

                placeBeam()
            }
        }









        MaterialFactory.makeOpaqueWithColor(this, Color(0f, 1f, 0f))
            .thenAccept { mat ->
                greenLineMaterial = mat
            }



        // 1) Build our corner‐sphere renderable:
        MaterialFactory.makeOpaqueWithColor(this, Color(AndroidColor.RED))
            .thenAccept { mat ->
                Log.d("cornerRenderable", "cornerRenderable material is ready")
                cornerRenderable = ShapeFactory.makeSphere(
                    /* radius = */ 0.02f,
                    Vector3.zero(),
                    mat
                ).apply {
                    isShadowCaster = false
                    isShadowReceiver = false
                }
                Log.d("cornerRenderable", "✅ cornerRenderable ShapeFactory.makeSphere(...) completed")

                // 2) **Now** cornerRenderable is ready: start observing bbox LiveData
                (application as SunMapperApp).bbox.observe(this) { box ->
                    if (box != null){
                        placeCorners(box)
                    }
                }
            }
            .exceptionally { throwable ->
                Log.e("cornerRenderable", "cornerRenderable failed", throwable)
                null
            }

//        (application as SunMapperApp).bbox.observe(this) { box ->
//            // box.x0..x1, box.y0..y1 ∈ [0,1] relative to the *camera* image
//            val ix0 = box.x0 * imgW
//            val iy0 = box.y0 * imgH
//            val ix1 = box.x1 * imgW
//            val iy1 = box.y1 * imgH
//
//            // now apply the scale + offset
//            val sx0 = ix0 * scale + offsetX
//            val sy0 = iy0 * scale + offsetY
//            val sx1 = ix1 * scale + offsetX
//            val sy1 = iy1 * scale + offsetY
//
//            Log.d("Measurement/bbox", "Drawing BBox px: [$sx0,$sy0]→[$sx1,$sy1]")
//            overlay.updateBBox(sx0, sy0, sx1, sy1)
//        }





        captureButton.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startFrameCapture()
                    v.isPressed = true
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    lifecycleScope.launch {
                        stopFrameCapture()
                    }
                    v.performClick()    // fires the "click" for accessibility
                    true
                }

                else -> false
            }
        }


//        val placeBeamBtn = findViewById<Button>(R.id.placeBeamButton)
//        placeBeamBtn.setOnClickListener {
//            placeBeam()
//        }

        findViewById<Button>(R.id.saveFloorButton).setOnClickListener {
            SunMapperApp.instance.savedFloorPlane = SunMapperApp.instance.currentTrackedPlane
            Log.d("Measurement", "savedFloorPlane = ${SunMapperApp.instance.savedFloorPlane}")
            Toast.makeText(this, "Saved current plane as floor!", Toast.LENGTH_SHORT).show()
        }







        // 1) Default (white) anchor
        MaterialFactory.makeTransparentWithColor(
            this,
            Color(AndroidColor.WHITE)
        )
            .thenAccept { material ->
                defaultAnchor = ShapeFactory.makeCylinder(
                    0.035f,
                    0.001f,
                    Vector3.zero(),
                    material
                ).apply {
                    isShadowCaster = false
                    isShadowReceiver = false
                }
            }
            .exceptionally { throwable ->
                AlertDialog.Builder(this)
                    .setTitle("Error")
                    .setMessage("Failed to create default anchor: ${throwable.message}")
                    .show()
                null
            }

        // 2) Floor (green) anchor
        MaterialFactory.makeTransparentWithColor(
            this,
            Color(AndroidColor.GREEN)
        )
            .thenAccept { material ->
                floorAnchor = ShapeFactory.makeCylinder(
                    0.035f,
                    0.001f,
                    Vector3.zero(),
                    material
                ).apply {
                    isShadowCaster = false
                    isShadowReceiver = false
                }
            }
            .exceptionally { throwable ->
                AlertDialog.Builder(this)
                    .setTitle("Error")
                    .setMessage("Failed to create floor anchor: ${throwable.message}")
                    .show()
                null
            }




        MaterialFactory.makeTransparentWithColor(
            this,
            Color(AndroidColor.MAGENTA)
        )
            .thenAccept { material: Material? ->
                cursorRenderer = ShapeFactory.makeSphere(
                    0.01f,
                    Vector3.zero(),
                    material
                )
                cursorRenderer!!.isShadowCaster = false
                cursorRenderer!!.isShadowReceiver = false
            }
            .exceptionally {
                val builder = AlertDialog.Builder(this)
                builder.setMessage(it.message).setTitle("Error")
                val dialog = builder.create()
                dialog.show()
                return@exceptionally null
            }





        ViewRenderable
            .builder()
            .setView(this, R.layout.text_layout)
            .build()
            .thenAccept {
                distanceCardViewRenderer = it
                distanceCardViewRenderer!!.isShadowCaster = false
                distanceCardViewRenderer!!.isShadowReceiver = false
            }
            .exceptionally {
                val builder = AlertDialog.Builder(this)
                builder.setMessage(it.message).setTitle("Error")
                val dialog = builder.create()
                dialog.show()
                return@exceptionally null
            }

        registerMdnsService()
//        embeddedServer(Netty, port = 8080) {
//            module()
//        }.start()
        ktorServer = embeddedServer(Netty, port = 8080) { module() }
        ktorServer.start(wait = false)

    }




    override fun onResume() {
        super.onResume()

        // 1. Collect any missing permissions
        val permsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permsNeeded += Manifest.permission.CAMERA
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permsNeeded += Manifest.permission.ACCESS_FINE_LOCATION
        }

        // 2. If any are missing, ask for them and stop here
        if (permsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permsNeeded.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
            return
        }

        // 3. We have camera + fine-location → resume AR and configure geospatial
        arFragment.onResume()
        arFragment.arSceneView.session?.let { session ->
            session.configure(Config(session).apply {
                updateMode     = Config.UpdateMode.LATEST_CAMERA_IMAGE
                geospatialMode = Config.GeospatialMode.ENABLED
            })
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check that *all* requested perms were granted
            val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allGranted) {
                // Try again now that we have permissions
                onResume()
            } else {
                // User denied → show rationale or direct them to Settings
                Toast.makeText(
                    this,
                    "Camera and location permissions are required for AR Geospatial.",
                    Toast.LENGTH_LONG
                ).show()
                // You can remain in the Activity and let them go to Settings…
            }
        }
    }






//    override fun onResume() {
//        super.onResume()
//        arFragment.onResume()
//
//        // Enable Geospatial Mode in ARCore session configuration
//        val session = arFragment.arSceneView.session!!
//        val config = Config(session).apply {
//            // Tell ARCore to use the latest camera image mode for optimal performance
//            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
//            // Enable Geospatial mode to access real-world location and orientation
//            geospatialMode = Config.GeospatialMode.ENABLED
//        }
//        session.configure(config)
//
//    }

    override fun onPause() {
        super.onPause()

    }


    @SuppressLint("ResourceType")
    override fun onUpdate(p0: FrameTime?) {
        val frame = arFragment.arSceneView.arFrame ?: return

        val now = System.currentTimeMillis()
        val centerX = arFragment.arSceneView.width * 0.5f
        val centerY = arFragment.arSceneView.height * 0.5f


        val session = arFragment.arSceneView.session ?: return

        // Get the Earth object from the ARCore session. This provides geospatial information.
        val earth = session.earth ?: return
        val gp = earth.cameraGeospatialPose

        val camY = arFragment.arSceneView.scene.camera.worldPosition.y
        val camX = arFragment.arSceneView.scene.camera.worldPosition.x
        val camZ = arFragment.arSceneView.scene.camera.worldPosition.z
        Log.d("Measurement/cam", String.format("camY=%.6f, camX=%.6f, camZ=%.6f", camY, camX, camZ))


        // only once your renderable is ready…
        if (::cardinalRenderable.isInitialized && sunMarkerRenderable != null) {
            val lat = gp.latitude
            val lon = gp.longitude
            val alt = gp.altitude    // raw GPS altitude → ARCore places at camera height

            // 1) create brand-new anchor at current lat/lon/alt
            val newAnchor = earth.createAnchor(lat, lon, alt, 0f, 0f, 0f, 1f)

            if (geoAnchorNode == null) {
                // first frame: make the node and parent it
                geoAnchorNode = AnchorNode(newAnchor).apply {
                    setParent(arFragment.arSceneView.scene)
                }
                Toast.makeText(this, "Geo-anchor placed!", Toast.LENGTH_SHORT).show()
                placeCardinalMarkers(geoAnchorNode!!)


            } else {
                // subsequent frames: detach the old anchor and swap in the new one
                geoAnchorNode!!.anchor?.detach()
                geoAnchorNode!!.anchor = newAnchor
            }
            updateSunMarker()

            // 2) (Optional) keep your cardinal labels billboarding:
            geoAnchorNode!!.children.forEach { node ->
                val camPos = arFragment.arSceneView.scene.camera.worldPosition
                val toCam = Vector3.subtract(camPos, node.worldPosition).normalized()
                node.worldRotation = Quaternion.lookRotation(toCam, Vector3.up())
            }
        }





        if (!cameraCalibrated) {
            // 1) Read the camera intrinsics from ARCore
            val intrinsics = frame.camera.imageIntrinsics
            val fx = intrinsics.focalLength[0]
            val fy = intrinsics.focalLength[1]
            val cx = intrinsics.principalPoint[0]
            val cy = intrinsics.principalPoint[1]

            // 2) Store them in your Application singleton
            val app = SunMapperApp.instance
            app.intrinsicsMatrix = listOf(
                listOf(fx, 0f, cx),
                listOf(0f, fy, cy),
                listOf(0f, 0f, 1f)
            )

            // 3) If you don’t have a distortion model yet, use zeros for now
            app.distCoeffs = listOf(0f, 0f, 0f, 0f, 0f)

            val (imgW, imgH) = intrinsics.imageDimensions
            val viewW = arFragment.arSceneView.width.toFloat()
            val viewH = arFragment.arSceneView.height.toFloat()
            val scale = min(viewW / imgW, viewH / imgH)
            val dispW = imgW * scale
            val dispH = imgH * scale
            val offsetX = (viewW - dispW) * 0.5f
            val offsetY = (viewH - dispH) * 0.5f

            cameraCalibrated = true
            Log.d("Measurement", "Calibrated camera: fx=$fx, fy=$fy, cx=$cx, cy=$cy")
        }


//        System.gc()
//        runOnUiThread {

        if (!freezeAnchors && frame.camera.trackingState == TrackingState.TRACKING) {

            hitResultList = frame.hitTest(centerX, centerY)
            firstHitResult =
                hitResultList.firstOrNull { hit ->
                    when (val trackable = hit.trackable!!) {
                        is Plane -> trackable.isPoseInPolygon(hit.hitPose) &&
                                calculateDistanceToPlane(hit.hitPose, frame.camera.pose) > 0

                        is Point -> trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                        is InstantPlacementPoint -> false
                        is DepthPoint -> true
                        else -> false
                    }
                }
            val hit = firstHitResult

            if (hit != null) {

                // Decide if this is the floor plane
                val plane = hit.trackable as? Plane

                val isFloor = plane != null &&
                        (plane == SunMapperApp.instance.savedFloorPlane)

                // Pick white vs green cursor
                val chosenAnchor = if (isFloor) floorAnchor else defaultAnchor

                if (plane != null) {
                    // Debounce start/reset
                    if (plane != candidatePlane) {
                        candidatePlane = plane
                        candidateSince = now
                    } else if (now - candidateSince >= PLANE_STABLE_THRESHOLD) {
                        // Once stable, publish once
                        stablePlane = plane
                        SunMapperApp.instance.currentTrackedPlane = stablePlane
                        Log.d("Plane", "Reporting plane under reticle: $plane")


                    }
                }

                if (cursorAnchor.size >= 1) {
                    //cursorAnchor[0].anchor.detach()
                    cursorAnchor.removeAt(0)
                    arFragment.arSceneView.scene.removeChild(anchorNode)
                }
                try {
                    cursorAnchor.add(
                        WrappedAnchor(
                            firstHitResult!!.createAnchor(),
                            firstHitResult!!.trackable
                        )
                    )
                } catch (e: Exception) {
//                        return@runOnUiThread
                    Log.e("Measurement", "Anchor creation failed", e)
                    return
                }



                anchorNode = AnchorNode(cursorAnchor[0].anchor)
                anchorNode.renderable = chosenAnchor
                anchorNode.setParent(arFragment.arSceneView.scene)

                val camPos = arFragment.arSceneView.scene.camera.worldPosition
                val retPos = anchorNode.worldPosition
                distanceInMeters = Vector3.subtract(camPos, retPos).length()


                // 1) Grab the ARCore camera, not the Sceneform one:
                val arCamera = frame.camera

                // 2) Allocate matrices if you haven’t yet (reuse for performance):
                val projmtx = FloatArray(16)
                val viewmtx = FloatArray(16)

                // 3) Pull ARCore’s projection & view matrices:
                arCamera.getProjectionMatrix(projmtx, /*offset=*/0, /*near=*/0.1f, /*far=*/100f)
                arCamera.getViewMatrix(viewmtx, /*offset=*/0)

                // 4) Project the reticle’s world position into clip space:
                val worldPos = anchorNode.worldPosition
                val worldVec = floatArrayOf(worldPos.x, worldPos.y, worldPos.z, 1f)
                val tempVec = FloatArray(4)
                Matrix.multiplyMV(tempVec, 0, viewmtx, 0, worldVec, 0)
                Matrix.multiplyMV(worldVec, 0, projmtx, 0, tempVec, 0)

                // 5) NDC → screen
                val ndcX = worldVec[0] / worldVec[3]
                val ndcY = worldVec[1] / worldVec[3]
                val screenX = (ndcX * 0.5f + 0.5f) * arFragment.arSceneView.width
                val screenY = (0.5f - ndcY * 0.5f) * arFragment.arSceneView.height

                // 6) Position & update your overlay TextView:
                runOnUiThread {
                    overlayDistance.apply {
                        text = String.format(Locale.getDefault(), "%.2f m", distanceInMeters)
                        x = screenX - width / 2f
                        y = screenY - height     // just above the reticle
                        visibility = View.VISIBLE
                    }
                }



                Log.e("HitResultList", anchorNode.anchor?.pose.toString())


                if (dockedAnchorNode != null) {
                    point1 = anchorNode.worldPosition
                    point2 = dockedAnchorNode!!.worldPosition
                    val difference = Vector3.subtract(point1, point2)
                    val directionFromTopToBottom = difference.normalized()
                    val rotationFromAToB =
                        Quaternion.lookRotation(directionFromTopToBottom, Vector3.up())
                    MaterialFactory.makeOpaqueWithColor(
                        applicationContext,
                        Color(AndroidColor.BLUE)
                    )
                        .thenAccept { material: Material? ->
                            val model = ShapeFactory.makeCube(
                                Vector3(.005f, .00f, difference.length()),
                                Vector3.zero(), material
                            )
                            model!!.isShadowCaster = false
                            model.isShadowReceiver = false
                            if (::lineNode.isInitialized) {
                                dockedAnchorNode!!.removeChild(lineNode)
                            }
                            lineNode = Node()
                            lineNode.setParent(dockedAnchorNode)
                            lineNode.renderable = model
                            lineNode.worldPosition = Vector3.add(point1, point2).scaled(.5f)
                            lineNode.worldRotation = rotationFromAToB

                            try {
                                if (::dockedAnchor.isInitialized) {
                                    distanceInMeters = computeDistance(
                                        dockedAnchor.anchor,
                                        cursorAnchor[0].anchor
                                    )

                                }


                            } catch (e: InterruptedException) {
                                e.printStackTrace()
                            }
                        }
                }
            } else {
                if (cursorAnchor.size >= 1) {
                    //cursorAnchor[0].anchor.detach()
                    cursorAnchor.removeAt(0)
                    arFragment.arSceneView.scene.removeChild(anchorNode)
                }
                cursorAnchor.add(
                    WrappedAnchor(
                        arFragment.arSceneView?.session!!.createAnchor(
                            frame.getCamera().getPose()
                                .compose(Pose.makeTranslation(0f, 0f, -1f))
                                .extractTranslation()
                        ),
                        null
                    )
                )

                anchorNode = AnchorNode(cursorAnchor[0].anchor)
                anchorNode.renderable = cursorRenderer
                anchorNode.setParent(arFragment.arSceneView.scene)

                runOnUiThread {
                    overlayDistance.apply {
                        visibility = View.GONE
                    }
                }

            }
        } else if (freezeAnchors) {
            /**** for text in ****/
            if (textNode == null) {
                Log.e("Anchor Pose", anchorNode.anchor!!.pose.toString())
                val distanceAnchorPose = anchorNode.anchor!!.pose
                textNode = AnchorNode(
                    arFragment.arSceneView?.session!!.createAnchor(
                        distanceAnchorPose
                            .compose(
                                Pose.makeRotation(
                                    0f,
                                    distanceAnchorPose.qy(),
                                    distanceAnchorPose.qz(),
                                    distanceAnchorPose.qw()
                                )
                            )
                            .extractTranslation()
                    )
                )
                textNode!!.renderable = distanceCardViewRenderer
                textNode!!.setParent(arFragment.arSceneView.scene)

            }


        }

    }


    fun nv21FromImage(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2          // 2 planes at ¼ resolution

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val rowStride = image.planes[1].rowStride
        val pixelStride = image.planes[1].pixelStride

        val nv21 = ByteArray(ySize + uvSize)

        // 1) copy Y plane entirely
        yBuffer.get(nv21, 0, ySize)

        // 2) interleave VU into the remainder
        var pos = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val index = row * rowStride + col * pixelStride
                nv21[pos++] = vBuffer.get(index)   // V
                nv21[pos++] = uBuffer.get(index)   // U
            }
        }

        return nv21
    }




    private fun computeDistance(anchor1: Anchor, anchor2: Anchor): Float {
        val dx: Float = anchor1.pose.tx() - anchor2.pose.tx()
        val dy: Float = anchor1.pose.ty() - anchor2.pose.ty()
        val dz: Float = anchor1.pose.tz() - anchor2.pose.tz()
        val distanceMeters =
            sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()

        return distanceMeters
    }



    private fun calculateDistanceToPlane(planePose: Pose, cameraPose: Pose): Float {
        val normal = FloatArray(3)
        val cameraX = cameraPose.tx()
        val cameraY = cameraPose.ty()
        val cameraZ = cameraPose.tz()
        planePose.getTransformedAxis(1, 1.0f, normal, 0)
        return (cameraX - planePose.tx()) * normal[0] + ((cameraY - planePose.ty()) * normal[1]
                ) + ((cameraZ - planePose.tz()) * normal[2])
    }

    private data class WrappedAnchor(
        val anchor: Anchor,
        val trackable: Trackable?,
    )

    override fun onClick(v: View?) {

        if (v?.id == R.id.clearButton) {
            freezeAnchors = false
            if (dockedAnchorNode != null) {
                arFragment.arSceneView.scene.removeChild(dockedAnchorNode)
                if (textNode != null) arFragment.arSceneView.scene.removeChild(textNode)
                dockedAnchorNode = null
                textNode = null
            }

            distanceInMeters = 0f
            distanceWithUnits = ""
//            findViewById<LinearLayout>(R.id.distanceLabel).visibility = View.GONE
        }
    }





    private fun updateSunMarker() {
        val parent = geoAnchorNode ?: return
        val dot = sunMarkerRenderable ?: return

        // read ENU vector
        val e = SunMapperApp.instance.sunEnuEast.toFloat()
        val n = SunMapperApp.instance.sunEnuNorth.toFloat()
        val u = SunMapperApp.instance.sunEnuUp.toFloat()

        // convert to anchor-local and scale
        val localSun = Vector3(e, u, -n).scaled(5f)

        if (sunMarkerNode == null) {
            sunMarkerNode = Node().apply {
                setParent(parent)
                renderable = dot
            }
        }
        // always update its localPosition
        sunMarkerNode!!.localPosition = localSun

        // billboard so it faces the camera
        val camPos = arFragment.arSceneView.scene.camera.worldPosition
        val worldPos = sunMarkerNode!!.worldPosition
        val toCam = Vector3.subtract(camPos, worldPos).normalized()
        sunMarkerNode!!.worldRotation = Quaternion.lookRotation(toCam, Vector3.up())
    }



    private fun startFrameCapture() {
        if (captureJob?.isActive == true) return
        SunMapperApp.instance.isCapturingFrameStream = true

        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            val sceneView = arFragment.arSceneView
            val vw = sceneView.width
            val vh = sceneView.height
            val viewAR = vw.toFloat() / vh.toFloat()

            while (isActive) {
                val img = try {
                    sceneView.arFrame?.acquireCameraImage()
                } catch (_: Exception) {
                    null
                }

                img?.use { image ->
                    val iw = image.width
                    val ih = image.height
                    val imgAR = iw.toFloat() / ih.toFloat()

                    // 1) compute cropW, cropH so ROI_AR == viewAR
                    val (cropW, cropH) = if (imgAR > viewAR) {
                        // sensor is wider → fit height, crop width
                        val h = ih
                        val w = (h * viewAR).toInt()
                        w to h
                    } else {
                        // sensor is taller → fit width, crop height
                        val w = iw
                        val h = (w / viewAR).toInt()
                        w to h
                    }

                    // 2) center‐crop bounds
                    val x0 = ((iw - cropW) / 2).coerceAtLeast(0)
                    val y0 = ((ih - cropH) / 2).coerceAtLeast(0)
                    val roi = Rect(x0, y0, x0 + cropW, y0 + cropH)

                    // 3) compress ROI to JPEG and send
                    val nv21 = nv21FromImage(image)
                    val jpeg = ByteArrayOutputStream().use { baos ->
                        YuvImage(nv21, ImageFormat.NV21, iw, ih, null)
                            .compressToJpeg(roi, 80, baos)
                        baos.toByteArray()
                    }
                    SunMapperApp.instance.offerFrame(jpeg)
                }

                delay(500)  //
            }
        }
    }


    private suspend fun stopFrameCapture() {
        captureJob?.cancel()
        captureJob = null

        // turn off the stream and close any open WS/frame sessions immediately
        SunMapperApp.instance.isCapturingFrameStream = false
//        closeAllFrameSessions()  // this is the helper in your KtorServer.kt
    }






    private fun placeCorners(box: BBox) {
        Log.d("SunMapper/corner", "placeCorners() called; cornerRenderable = $cornerRenderable")
        val dot = cornerRenderable ?: return
        val sceneView = arFragment.arSceneView
        val scene = sceneView.scene
        val camera = scene.camera
        val session = sceneView.session ?: return

        // 1) Remove any existing corner‐anchors (red dots):
        for (i in cornerNodes.indices) {
            cornerNodes[i]?.let { old ->
                old.anchor?.detach()
                old.setParent(null)
                cornerNodes[i] = null
            }
        }

        // 2) Remove any existing edge‐nodes (old green lines):
        lineNodes.forEach { it.setParent(null) }
        lineNodes.clear()

        // 3) Compute the four screen‐space points (px,py) exactly as before:
        val vw = sceneView.width.toFloat()
        val vh = sceneView.height.toFloat()
        val screenPts = listOf(
            box.x0 to box.y0, // top-left
            box.x1 to box.y0, // top-right
            box.x1 to box.y1, // bottom-right
            box.x0 to box.y1  // bottom-left
        ).map { (nx, ny) -> (nx * vw) to (ny * vh) }

        // 4) Intersect each with the “infinite plane,” place 4 red spheres,
        //    and immediately remember their raw worldPt for anchors below.
        val plane = SunMapperApp.instance.currentTrackedPlane ?: return
        val planePose = plane.centerPose
        val n = FloatArray(3)
        planePose.getTransformedAxis(1, 1f, n, 0)
        val planeNormal = Vector3(n[0], n[1], n[2]).normalized()

        val worldHitPts = mutableListOf<Pose>()  // we’ll store Poses for each corner
        screenPts.forEachIndexed { i, (px, py) ->
            val ray: Ray = camera.screenPointToRay(px, py)
            val worldPt = FloatArray(3)
            if (intersectRayPlane(ray, planePose, planeNormal, worldPt)) {
                // (a) Create the Anchor + AnchorNode for the red dot:
                val hitPose = Pose.makeTranslation(worldPt[0], worldPt[1], worldPt[2])
                cornerNodes[i] = AnchorNode(session.createAnchor(hitPose)).apply {
                    renderable = dot
                    setParent(scene)
                }
                // (b) Record the Pose so we can read the exact worldPosition later:
                worldHitPts.add(hitPose)
            } else {
                // If any one corner fails, bail out entirely:
                return
            }
        }

        // 5) Now that all 4 anchors exist, wait one frame so that Sceneform updates
        //    the AnchorNode.worldPosition. In that next frame, we’ll draw the 4 edges.
        scene.addOnUpdateListener(object : Scene.OnUpdateListener {
            override fun onUpdate(frameTime: FrameTime) {
                // (a) Remove this listener immediately so it only runs once:
                scene.removeOnUpdateListener(this)

                // (b) If greenLineMaterial isn’t ready, skip drawing for now:
                val mat = greenLineMaterial ?: return

                // (c) Read the FOUR anchorNode.worldPosition values now that they’re up-to-date:
                val cornerPositions = cornerNodes.map { it!!.worldPosition }

                // (d) For each consecutive pair (0→1, 1→2, 2→3, 3→0), draw a line:
                for (i in cornerPositions.indices) {
                    val start = cornerPositions[i]
                    val end = cornerPositions[(i + 1) % cornerPositions.size]
                    drawStraightLineBetween(start, end, mat, scene)
                }
            }
        })
    }

    private fun drawStraightLineBetween(
        start: Vector3,
        end: Vector3,
        mat: Material,
        parentScene: Scene
    ) {
        // 1) Compute vector from start → end, and its length:
        val diff = Vector3.subtract(end, start)
        val length = diff.length().takeIf { it > 0f } ?: return

        // 2) Create a very thin cylinder of exactly that length:
        val cylinder = ShapeFactory.makeCylinder(
            /* radius  = */ 0.005f,      // 5 mm thick
            /* height  = */ length,      // cylinder’s height == distance
            /* center  = */ Vector3.zero(),
            /* material=*/ mat
        ).apply {
            isShadowCaster = false
            isShadowReceiver = false
        }

        // 3) Compute midpoint = (start + end)/2:
        val midpoint = Vector3.add(start, end).scaled(0.5f)

        // 4) Compute the rotation that sends local +Y → (end - start):
        val direction = diff.normalized()
        val orientation = Quaternion.rotationBetweenVectors(Vector3.up(), direction)

        // 5) Create a Node, set its transform, and attach the cylinder:
        val lineNode = Node().apply {
            setParent(parentScene)
            worldPosition = midpoint
            worldRotation = orientation
            renderable = cylinder
        }

        // 6) Remember it so we can remove it next time:
        lineNodes.add(lineNode)
    }














//    private fun placeCorners(box: BBox) {
//        val dot = cornerRenderable ?: return
//        val sceneView = arFragment.arSceneView
//        val scene = sceneView.scene
//        val camera = scene.camera
//        val session = sceneView.session ?: return
//
//
//        // 1) Remove any existing corner nodes:
//        for (i in cornerNodes.indices) {
//            cornerNodes[i]?.let { old ->
//                old.anchor?.detach()
//                old.setParent(null)
//                cornerNodes[i] = null
//            }
//        }
//
//
//        // 1) Grab the one Plane under your reticle
//        val plane = SunMapperApp.instance.currentTrackedPlane ?: return
//        val planePose = plane.centerPose
//        // In ARCore, the Pose's Y axis is the plane normal:
//        val n = FloatArray(3)
//        planePose.getTransformedAxis(
//            /* axis = */ 1,
//            /* scale = */ 1f,
//            /* out   = */ n,
//            /* offset=*/ 0
//        )
//        val planeNormal = Vector3(n[0], n[1], n[2]).normalized()
//
//        // 2) Compute your 4 window‐corner screen points (px,py):
//        val vw = sceneView.width.toFloat()
//        val vh = sceneView.height.toFloat()
//        val screenPts = listOf(
//            box.x0 to box.y0,  // top-left
//            box.x1 to box.y0,  // top-right
//            box.x1 to box.y1,  // bottom-right
//            box.x0 to box.y1   // bottom-left
//        ).map { (nx, ny) -> nx * vw to ny * vh }
//
//        // 3) For each corner, ray‐cast into that infinite plane:
//        screenPts.forEachIndexed { i, (px, py) ->
//            // build a Ray through the pixel
//            val ray: Ray = camera.screenPointToRay(px, py)
//
//            // intersect it with your infinite plane
//            val worldPt = FloatArray(3)
//            if (intersectRayPlane(ray, planePose, planeNormal, worldPt)) {
//                // place an Anchor at the intersection
//                val hitPose = Pose.makeTranslation(
//                    worldPt[0], worldPt[1], worldPt[2]
//                )
//                cornerNodes[i] = AnchorNode(session.createAnchor(hitPose)).apply {
//                    renderable = dot
//                    setParent(scene)
//                }
//            }
//        }
//    }

    // helper to intersect Ray with infinite ARCore Plane:
// returns true + writes world‐coords into outPt[0..2]
    fun intersectRayPlane(
        ray: Ray,
        planePose: Pose,
        planeNormal: Vector3,
        outPt: FloatArray
    ): Boolean {
        val O = ray.origin
        val D = ray.direction.normalized()
        val P0 = Vector3(planePose.tx(), planePose.ty(), planePose.tz())
        val N = planeNormal

        val denom = Vector3.dot(D, N)
        if (abs(denom) < 1e-6f) return false  // parallel

        val numer = Vector3.dot(Vector3.subtract(P0, O), N)
        val t = numer / denom
        if (t < 0f) return false                          // behind camera

        val hit = Vector3.add(O, D.scaled(t))
        outPt[0] = hit.x; outPt[1] = hit.y; outPt[2] = hit.z
        return true
    }


    override fun onDestroy() {
        super.onDestroy()
        // clean up anchors
        ktorServer.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        registrationListener?.let { nsdManager.unregisterService(it) }
//        cornerNodes.filterNotNull().forEach {
//            it.anchor?.detach()
//        }
    }


    private fun placeCardinalMarkers(root: AnchorNode) {
        val letters = listOf("N", "E", "S", "W")
        val positions = listOf(
            Vector3(0f, 0f, -cardinalDistance),
            Vector3(cardinalDistance, 0f, 0f),
            Vector3(0f, 0f, cardinalDistance),
            Vector3(-cardinalDistance, 0f, 0f)
        )

        letters.zip(positions).forEach { (letter, pos) ->
            // 1) inflate or create a fresh TextView
            val tv = TextView(this).apply {
                text = letter
                textSize = 16f
                setBackgroundColor(AndroidColor.RED)
                setPadding(12, 6, 12, 6)
            }

            // 2) build a brand-new ViewRenderable around it
            ViewRenderable.builder()
                .setView(this, tv)
                .build()
                .thenAccept { renderable ->
                    
                    renderable.isShadowCaster = false
                    renderable.isShadowReceiver = false
                    // 3) attach it
                    Node().apply {
                        setParent(root)
                        localPosition = pos
                        localScale = Vector3(2f, 2f, 2f)
                        this.renderable = renderable
                        // (if you still want billboarding, add this node to a list and rotate each frame)
                    }
                }
                .exceptionally { t ->
                    Log.e("Measurement", "Failed to build label $letter", t)
                    null
                }
        }
    }


    private fun placeBeam() {
        // 1) grab your saved floor plane
        val floorPlane = SunMapperApp.instance.savedFloorPlane
        if (floorPlane == null) return Toast.makeText(this, "Select a floor plane first", Toast.LENGTH_SHORT).show()
        val fY = floorPlane.centerPose.ty()

        // 2) make sure your geo-anchor is ready
        val geo = geoAnchorNode ?: return Toast.makeText(this, "Geo-anchor not ready", Toast.LENGTH_SHORT).show()

        // 3) collect corners …
        val corners = cornerNodes.mapNotNull { it?.worldPosition }
        if (corners.size < 4) return Toast.makeText(this, "Need 4 corners first", Toast.LENGTH_SHORT).show()

        // 4) build sun-vector and rayDir …
        val e = SunMapperApp.instance.sunEnuEast.toFloat()
        val n = SunMapperApp.instance.sunEnuNorth.toFloat()
        val u = SunMapperApp.instance.sunEnuUp.toFloat()
        val localSun    = Vector3(e, u, -n).normalized()
        val worldSunDir = Quaternion.rotateVector(geo.worldRotation, localSun).normalized()
        val rayDir      = worldSunDir.scaled(-1f)

        // 5) intersect each corner…
        val floorPoints = corners.map { corner ->
            val t = (fY - corner.y) / rayDir.y
            Vector3.add(corner, rayDir.scaled(t))
        }

        // 6) compute center and local verts…
        val cx = floorPoints.map { it.x }.average().toFloat()
        val cy = floorPoints.map { it.y }.average().toFloat()
        val cz = floorPoints.map { it.z }.average().toFloat()
        val floorCenter = Vector3(cx, cy, cz)
        val localVerts  = floorPoints.map { Vector3.subtract(it, floorCenter) }

        // 7) build material & mesh
        MaterialFactory.makeTransparentWithColor(this, Color(1f,1f,0.8f,0.3f))
            .thenAccept { mat ->
                // build vertices & indices exactly as before…
                val vb = Vertex.builder()
                fun v(p: Vector3, u: Float, v: Float) = vb
                    .setPosition(p)
                    .setNormal(Vector3.up())
                    .setUvCoordinate(Vertex.UvCoordinate(u, v))
                    .build()

                val verts = listOf(
                    v(localVerts[0], 0f, 0f),
                    v(localVerts[1], 1f, 0f),
                    v(localVerts[2], 1f, 1f),
                    v(localVerts[3], 0f, 1f)
                )
                val indices = listOf(0,1,2,  0,2,3)
                val submesh = RenderableDefinition.Submesh.builder()
                    .setTriangleIndices(indices)
                    .setMaterial(mat)
                    .build()
                val def = RenderableDefinition.builder()
                    .setVertices(verts)
                    .setSubmeshes(listOf(submesh))
                    .build()

                ModelRenderable.builder()
                    .setSource(def)
                    .build()
                    .thenAccept { patchRenderable ->
                        patchRenderable.isShadowCaster = false
                        patchRenderable.isShadowReceiver = false
                        // **reuse or create** floorPatchNode
                        if (floorPatchNode == null) {
                            floorPatchNode = Node().apply {
                                setParent(arFragment.arSceneView.scene)
                            }
                        }
                        floorPatchNode!!.apply {
                            worldPosition = floorCenter
                            worldRotation = Quaternion.identity() // it's flat, no rotation
                            renderable    = patchRenderable
                        }
                    }
            }
    }
}









