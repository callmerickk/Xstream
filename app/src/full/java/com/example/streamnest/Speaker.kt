package com.example.streamnest

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Subtitles › Follow the speaker (beta): a few times a second a small copy of the picture is checked for faces (on the
 * phone, nothing leaves it). Each face's lips are followed; the face whose mouth moves most is talking, and its line
 * shows under it (left / right of the centre). One face or nobody clearly talking → the line stays in the middle.
 */
object SpeakerTracker {
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                // Landmarks, not contours: ML Kit draws contours for the biggest face only, so with two people talking
                // there was never a second face to compare and the line never moved.
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .enableTracking()
                .setMinFaceSize(0.1f)
                .build()
        )
    }
    /** Mouth opening (share of the face height) per tracked face, the last few looks. */
    private val mouths = HashMap<Int, ArrayDeque<Float>>()
    private val lastSeen = HashMap<Int, Long>()

    fun reset() { mouths.clear(); lastSeen.clear() }

    /** Where the speaker is across the picture (0 = left edge, 1 = right edge), or null = keep the line centred. */
    suspend fun speakerX(frame: Bitmap): Float? {
        val small = if (frame.width > 400) Bitmap.createScaledBitmap(frame, 400, (400f * frame.height / frame.width).toInt().coerceAtLeast(1), true) else frame
        val faces = suspendCancellableCoroutine { c ->
            detector.process(InputImage.fromBitmap(small, 0))
                .addOnSuccessListener { c.resume(it) }
                .addOnFailureListener { c.resume(emptyList()) }
        }
        val now = System.currentTimeMillis()
        val seen = faces.mapNotNull { f ->
            val id = f.trackingId ?: return@mapNotNull null
            val h = f.boundingBox.height().coerceAtLeast(1).toFloat()
            val bottom = f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position ?: return@mapNotNull null
            val left = f.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return@mapNotNull null
            val right = f.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return@mapNotNull null
            // The jaw moves against the nose while talking: lower lip to nose base (falls back to the mouth corners).
            val nose = f.getLandmark(FaceLandmark.NOSE_BASE)?.position
            val open = if (nose != null) (bottom.y - nose.y) / h else (bottom.y - (left.y + right.y) / 2f) / h
            val q = mouths.getOrPut(id) { ArrayDeque() }
            q.addLast(open); while (q.size > 8) q.removeFirst()
            lastSeen[id] = now
            Triple(id, f.boundingBox.exactCenterX() / small.width, q)
        }
        if (small !== frame) small.recycle()
        lastSeen.filterValues { now - it > 4_000 }.keys.toList().forEach { mouths.remove(it); lastSeen.remove(it) }
        if (seen.isEmpty()) return null
        // How much each mouth moved lately (spread of its opening); the talker moves clearly more than the others.
        val moving = seen.filter { it.third.size >= 3 }.map { (_, x, q) ->
            val m = q.average().toFloat(); x to kotlin.math.sqrt(q.map { (it - m) * (it - m) }.average()).toFloat()
        }.sortedByDescending { it.second }
        if (moving.isEmpty()) return null
        val (bx, bs) = moving[0]
        // One face on screen: follow it only when it is clearly talking and away from the middle.
        if (moving.size == 1) return if (bs > 0.012f && kotlin.math.abs(bx - 0.5f) > 0.12f) bx.coerceIn(0f, 1f) else null
        val second = moving[1].second
        return if (bs > 0.008f && bs > second * 1.4f) bx.coerceIn(0f, 1f) else null
    }
}
