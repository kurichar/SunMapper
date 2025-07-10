package com.sunmapper

import android.util.Log
import com.google.ar.core.Plane
import io.ktor.application.*
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.DefaultWebSocketSession
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.pingPeriod
import io.ktor.response.respond
import io.ktor.response.respondText
import io.ktor.routing.*
import io.ktor.websocket.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.time.Duration
import io.ktor.http.cio.websocket.CloseReason
import io.ktor.http.cio.websocket.close
import io.ktor.http.cio.websocket.timeout
import io.ktor.request.receive
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * HTTP server exposing:
 *  • GET /ping   → "pong"
 *  • GET /plane  → JSON with your latest ARCore plane data
 *
 * Launch with:
 * embeddedServer(Netty, port = 8080) { module() }.start()
 */


// ——— keep track of every client listening on /ws/frame ———
private val frameConsumers = mutableSetOf<DefaultWebSocketSession>()
private val consumersMutex = Mutex()

// Represents the sun‐direction in ENU coords
data class SunEnu(val east: Double, val north: Double, val up: Double)

fun Application.module() {
    // install JSON support
    install(ContentNegotiation) {
        gson {
            setPrettyPrinting()
        }
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofDays(1)

    }


    routing {
        get("/ping") {
            call.respondText("pong", ContentType.Text.Plain)
        }

        get("/plane") {
            try {
                val app = SunMapperApp.instance
                val plane = app.currentTrackedPlane

                // Simple, foolproof logging
                val kStatus = runCatching { app.intrinsicsMatrix }.isSuccess
                val distStatus = runCatching { app.distCoeffs }.isSuccess
                Log.d(
                    "Ktor",
                    "Serving /plane → plane=$plane, K initialized? $kStatus, dist initialized? $distStatus"
                )



                if (plane == null) {
                    // no plane yet
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    // build and return the JSON payload
                    val payload = mapOf(
                        "type" to plane.type.name,
                        "K" to app.intrinsicsMatrix,
                        "dist" to app.distCoeffs,
                        "R" to plane.centerPose.rotationQuaternion.toList(),
                        "t" to plane.centerPose.translation.toList(),
                        "n" to plane.planeNormal().toList(),
                        "d" to plane.planeOffset()
                    )
                    call.respond(payload)
                }
            } catch (e: Exception) {
                Log.e("Ktor", "Exception in /plane", e)
                call.respondText(
                    e.stackTraceToString(),
                    ContentType.Text.Plain,
                    HttpStatusCode.InternalServerError
                )
            }

        }



        post("/bbox") {
            val box = call.receive<BBox>()
            Log.d("Ktor/BBox", "🚀 client connected to /bbox got $box")
            val app = SunMapperApp.instance
            app.postBBox(box)
            call.respond(HttpStatusCode.OK)
        }

        post("/sun") {
            // parse JSON body into our data class
            val sun = call.receive<SunEnu>()

            // store it for AR usage
            SunMapperApp.instance.sunEnuEast  = sun.east
            SunMapperApp.instance.sunEnuNorth = sun.north
            SunMapperApp.instance.sunEnuUp    = sun.up

            Log.d("SunVector", "ENU = " +
                    "${SunMapperApp.instance.sunEnuEast}, " +
                    "${SunMapperApp.instance.sunEnuNorth}, " +
                    "${SunMapperApp.instance.sunEnuUp}")

            call.respond(HttpStatusCode.OK)
        }

        /** New WebSocket endpoint for frame streaming **/
        webSocket("/ws/frame") {
            // Register this client
            consumersMutex.withLock { frameConsumers += this }

            // Grab values
            val app = SunMapperApp.instance


            // 3) WAIT for a real fix
            while (app.deviceLat == null || app.deviceLon == null) {
                delay(500)  // suspend for half a second
            }


            broadcastHandshake()


            try {
                // Now just suspend; your SharedFlow collector will push frames
                incoming.consumeEach { /* ignore any client messages */ }
            } finally {
                // Unregister on disconnect
                consumersMutex.withLock { frameConsumers -= this }
            }
        }

        // 1) grab a reference to your App singleton
        val labsApp = SunMapperApp.instance

        // 2) launch a coroutine that will fan-out frames
        launch {
            labsApp.frameFlow.collect { jpegBytes ->
                // wrap in a ByteBuffer and snapshot the sessions under lock
                val buffer = ByteBuffer.wrap(jpegBytes)
                val sessions = consumersMutex.withLock { frameConsumers.toList() }

                // send to each open WS session
                sessions.forEach { sess ->
                    sess.send(Frame.Binary(true, buffer))
                    buffer.rewind()
                }
            }
        }


    }


}



/** Rotate (0,0,1) by the plane's pose to get its normal. */
fun Plane.planeNormal(): FloatArray {
    val p = this.centerPose
    val worldPoint = p.transformPoint(floatArrayOf(0f, 0f, 1f))
    val origin     = p.translation
    return floatArrayOf(
        worldPoint[0] - origin[0],
        worldPoint[1] - origin[1],
        worldPoint[2] - origin[2]
    )
}

/** Compute d for the plane equation n·X + d=0. */
fun Plane.planeOffset(): Float {
    val n = planeNormal()
    val t = centerPose.translation
    return -(n[0]*t[0] + n[1]*t[1] + n[2]*t[2])
}

suspend fun broadcastHandshake() {
    val app = SunMapperApp.instance
    val lat = app.deviceLat
    val lon = app.deviceLon
    val tz  = app.deviceTz
    val date = app.selectedDate    // from LabsArRulerApp
    val hour = app.selectedHour
    val minute = app.selectedMinute

    val props = mutableListOf<String>()
    props += "\"type\":\"handshake\""
    lat?.let { props += "\"lat\":$it" }
    lon?.let { props += "\"lon\":$it" }
    tz?.let  { props += "\"tz\":\"$it\"" }
    props += "\"date\":\"$date\""
    props += "\"hour\":$hour"
    props += "\"minute\":$minute"
    val json = "{${props.joinToString(",")}}"

    // send to every registered WS session
    consumersMutex.withLock {
        frameConsumers.forEach { sess ->
            sess.send(Frame.Text(json))
        }
    }
}