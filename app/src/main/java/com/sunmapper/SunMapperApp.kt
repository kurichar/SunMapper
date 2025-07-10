package com.sunmapper

import android.app.Application
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.ar.core.Plane
import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.LocalDateTime
import java.util.TimeZone

class SunMapperApp : Application() {

    companion object {
        // Singleton reference for global access
        lateinit var instance: SunMapperApp
            private set
    }

    var magneticDeclination: Float = 0f



    var deviceLat: Double? = null
    var deviceLon: Double? = null
    var deviceTz: String?  = null
    var selectedDate: String = ""
    var selectedHour: Int    = 0
    var selectedMinute: Int = 0



    var sunEnuEast:  Double = 0.0
    var sunEnuNorth: Double = 0.0
    var sunEnuUp:    Double = 0.0


    var viewAspect: Float = 1f

//    var viewHeight: Int = 0
//    var viewWidth: Int = 0


    // Camera intrinsics (3×3) and distortion [k1,k2,p1,p2,k3]
    lateinit var intrinsicsMatrix: List<List<Float>>
    lateinit var distCoeffs:       List<Float>

    // Updated each AR frame
    var currentTrackedPlane: Plane? = null

    var savedFloorPlane: Plane? = null

    private val _bbox = MutableLiveData<BBox>()
    val bbox: LiveData<BBox> = _bbox
    var lastBox: BBox? = null


    // a 1‐element buffer, drop the oldest if you emit again before sending
    private val _frameFlow = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frameFlow = _frameFlow.asSharedFlow()


    /** call this from your capture coroutine instead of raw broadcastFrame() */
    fun offerFrame(jpeg: ByteArray) {
        if (!isCapturingFrameStream) return
        _frameFlow.tryEmit(jpeg)
    }


    fun postBBox(box: BBox) {
        lastBox = box
        _bbox.postValue(box)
    }


    @Volatile
    var isCapturingFrameStream: Boolean = false



    fun resetState() {
        magneticDeclination = 0f

        deviceLat = null
        deviceLon = null
        deviceTz = TimeZone.getDefault().id
        selectedDate = LocalDateTime.now().toLocalDate().toString()
        selectedHour = LocalDateTime.now().hour
        selectedMinute = LocalDateTime.now().minute

        sunEnuEast = 0.0
        sunEnuNorth = 0.0
        sunEnuUp = 0.0

        viewAspect = 1f


        // If you’ve already set intrinsicsMatrix/distCoeffs somewhere:
        // You might want to assign them to some placeholder, or re‐load them from storage.
        // For now, let’s clear them:
        if (::intrinsicsMatrix.isInitialized) intrinsicsMatrix = emptyList()
        if (::distCoeffs.isInitialized) distCoeffs = emptyList()

        currentTrackedPlane = null
        savedFloorPlane = null

        // Clear out the existing bounding‐box LiveData:
        lastBox = null
        _bbox.value = null

        // Reset streaming flag:
        isCapturingFrameStream = false

        // frameFlow does not need manual reset; any new subscriber just starts with an empty buffer.
    }


    override fun onCreate() {
        super.onCreate()
        instance = this
        deviceTz = TimeZone.getDefault().id

        // initialize simulate‐time to “now”
        val now = LocalDateTime.now()
        selectedDate = now.toLocalDate().toString()    // e.g. "2025-05-26"
        selectedHour = now.hour
        selectedMinute = now.minute
    }
}
