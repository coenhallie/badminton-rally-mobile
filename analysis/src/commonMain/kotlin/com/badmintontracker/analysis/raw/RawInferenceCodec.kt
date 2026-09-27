package com.badmintontracker.analysis.raw

import com.badmintontracker.analysis.camera.CameraMotion
import com.badmintontracker.analysis.camera.CameraSample

/**
 * The RawInference wire format: little-endian, fixed width, no framing.
 *
 * Byte order is pinned rather than left to the platform because the writer is
 * Swift and the reader is Kotlin. Kotlin multiplatform has no ByteBuffer and
 * reaching for a platform buffer through expect/actual would let the two
 * targets disagree, so the byte handling is explicit here and shared.
 *
 * Layout (version 1):
 *   magic        4 bytes  "RAWI"
 *   version      int32
 *   fps          float64
 *   totalFrames  int32
 *   videoWidth   int32
 *   videoHeight  int32
 *   modelVersion int32 length + UTF-8 bytes
 *   frameCount   int32
 *   per frame:   frame int32, timestamp float64, shuttleFlag int8,
 *                [shuttle], boxCount int32, [boxes], personCount int32,
 *                [persons]
 *
 * Version 2 appends a camera section after the frames:
 *   hasCamera         int8
 *   [if hasCamera]:
 *     referenceTimestamp float64
 *     count              int32
 *     per sample: timestamp float64, then h00 h01 h02 h10 h11 h12 h20 h21 as
 *       eight float64 (h22 is always 1.0 and is not written)
 *
 * `decode` reads both version 1 and version 2 and rejects any trailing bytes;
 * `encode` always writes version 2.
 */
object RawInferenceCodec {

    private val MAGIC = byteArrayOf('R'.code.toByte(), 'A'.code.toByte(), 'W'.code.toByte(), 'I'.code.toByte())
    const val VERSION: Int = 2
    val READABLE_VERSIONS: Set<Int> = setOf(1, VERSION)

    const val VERSION_OFFSET: Int = 4
    const val TOTAL_FRAMES_OFFSET: Int = 16

    fun encode(inference: RawInference): ByteArray {
        val out = Writer()
        out.bytes(MAGIC)
        out.int(VERSION)
        out.double(inference.header.fps)
        out.int(inference.header.totalFrames)
        out.int(inference.header.videoWidth)
        out.int(inference.header.videoHeight)
        out.string(inference.header.modelVersion)
        out.int(inference.frames.size)
        for (f in inference.frames) {
            out.int(f.frame)
            out.double(f.timestamp)
            val s = f.shuttle
            out.byte(if (s == null) 0 else 1)
            if (s != null) {
                out.float(s.x); out.float(s.y); out.float(s.confidence)
                out.byte(if (s.visible) 1 else 0)
            }
            out.int(f.boxes.size)
            for (b in f.boxes) out.box(b)
            out.int(f.persons.size)
            for (p in f.persons) {
                out.box(p.box)
                out.int(p.keypoints.size)
                for (k in p.keypoints) { out.float(k.x); out.float(k.y); out.float(k.confidence) }
            }
        }
        val camera = inference.camera
        out.byte(if (camera == null) 0 else 1)
        if (camera != null) {
            out.double(camera.referenceTimestamp)
            out.int(camera.samples.size)
            for (s in camera.samples) {
                out.double(s.timestamp)
                val m = s.referenceToFrame
                out.double(m[0][0]); out.double(m[0][1]); out.double(m[0][2])
                out.double(m[1][0]); out.double(m[1][1]); out.double(m[1][2])
                out.double(m[2][0]); out.double(m[2][1])
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): RawInference {
        val r = Reader(bytes)
        val magic = r.bytes(4)
        require(magic.contentEquals(MAGIC)) {
            "not a RawInference stream: expected magic RAWI, got ${magic.decodeToString()}"
        }
        val version = r.int()
        require(version in READABLE_VERSIONS) {
            "unsupported RawInference version $version, this build reads $READABLE_VERSIONS"
        }
        val header = RawHeader(
            version = version,
            fps = r.double(),
            totalFrames = r.int(),
            videoWidth = r.int(),
            videoHeight = r.int(),
            modelVersion = r.string(),
        )
        val frameCount = r.count(FRAME_MIN_BYTES)
        val frames = ArrayList<RawFrame>(frameCount)
        repeat(frameCount) {
            val frame = r.int()
            val timestamp = r.double()
            val shuttle = if (r.byte().toInt() == 0) null else RawShuttle(
                x = r.float(), y = r.float(), confidence = r.float(),
                visible = r.byte().toInt() != 0,
            )
            val boxes = List(r.count(BOX_BYTES)) { r.box() }
            val persons = List(r.count(PERSON_MIN_BYTES)) {
                val box = r.box()
                RawPerson(box, List(r.count(KEYPOINT_BYTES)) { RawKeypoint(r.float(), r.float(), r.float()) })
            }
            frames.add(RawFrame(frame, timestamp, shuttle, boxes, persons))
        }
        var camera: CameraMotion? = null
        if (version == 2) {
            val hasCamera = r.byte().toInt() != 0
            if (hasCamera) {
                val referenceTimestamp = r.double()
                val sampleCount = r.count(CAMERA_SAMPLE_BYTES)
                val samples = List(sampleCount) {
                    val timestamp = r.double()
                    val h00 = r.double(); val h01 = r.double(); val h02 = r.double()
                    val h10 = r.double(); val h11 = r.double(); val h12 = r.double()
                    val h20 = r.double(); val h21 = r.double()
                    CameraSample(
                        timestamp,
                        listOf(
                            listOf(h00, h01, h02),
                            listOf(h10, h11, h12),
                            listOf(h20, h21, 1.0),
                        ),
                    )
                }
                camera = CameraMotion(referenceTimestamp, samples)
            }
        }
        require(r.remaining() == 0) { "trailing bytes after RawInference stream" }
        return RawInference(header, frames, camera)
    }


    // The least each record can occupy, for [Reader.count].
    private const val BOX_BYTES = 4 + 5 * 4
    private const val KEYPOINT_BYTES = 3 * 4
    /** A box and a keypoint count. */
    private const val PERSON_MIN_BYTES = BOX_BYTES + 4
    /** Frame index, timestamp, the shuttle flag, and the two counts. */
    private const val FRAME_MIN_BYTES = 4 + 8 + 1 + 4 + 4
    /** A camera sample: timestamp plus the eight written homography entries, all float64. */
    private const val CAMERA_SAMPLE_BYTES = 8 + 8 * 8
    private class Writer {
        private var buf = ByteArray(1024)
        private var size = 0

        private fun ensure(extra: Int) {
            if (size + extra <= buf.size) return
            var cap = buf.size
            while (cap < size + extra) cap *= 2
            buf = buf.copyOf(cap)
        }

        fun byte(v: Int) { ensure(1); buf[size++] = v.toByte() }

        fun bytes(v: ByteArray) {
            ensure(v.size); v.copyInto(buf, size); size += v.size
        }

        fun int(v: Int) {
            ensure(4)
            for (i in 0 until 4) buf[size++] = ((v ushr (8 * i)) and 0xFF).toByte()
        }

        fun long(v: Long) {
            ensure(8)
            for (i in 0 until 8) buf[size++] = ((v ushr (8 * i)) and 0xFF).toByte()
        }

        fun float(v: Float) = int(v.toRawBits())
        fun double(v: Double) = long(v.toRawBits())

        fun string(v: String) {
            val encoded = v.encodeToByteArray()
            int(encoded.size)
            bytes(encoded)
        }

        fun box(b: RawBox) {
            int(b.classId); float(b.confidence)
            float(b.x1); float(b.y1); float(b.x2); float(b.y2)
        }

        fun toByteArray(): ByteArray = buf.copyOf(size)
    }

    private class Reader(private val buf: ByteArray) {
        private var pos = 0

        // Every read goes through this. A truncated stream must fail loudly:
        // decoding a half-written file into a plausible shorter video would
        // move every rally boundary with nothing reporting why.
        private fun take(n: Int): Int {
            // n against what is LEFT, not pos + n against the size: that sum wraps
            // negative for a large n, and a wrapped sum passes.
            require(n >= 0 && n <= buf.size - pos) {
                "truncated RawInference stream: wanted $n bytes at $pos, have ${buf.size}"
            }
            val at = pos
            pos += n
            return at
        }

        /**
         * A count of records that follow, each at least [minRecordBytes] long.
         *
         * Checked against the bytes that are actually left BEFORE anyone sizes a
         * list by it. These streams come off the network, and an unchecked count
         * reaches an allocation: a flipped one asked for two billion slots, and
         * the app died of OutOfMemoryError before [take] could call it truncated.
         */
        fun count(minRecordBytes: Int): Int {
            val n = int()
            require(n >= 0 && n.toLong() * minRecordBytes <= buf.size - pos) {
                "truncated RawInference stream: $n records of at least $minRecordBytes bytes " +
                    "claimed at $pos, have ${buf.size}"
            }
            return n
        }

        fun byte(): Byte = buf[take(1)]

        fun bytes(n: Int): ByteArray {
            val at = take(n)
            return buf.copyOfRange(at, at + n)
        }

        fun int(): Int {
            val at = take(4)
            var v = 0
            for (i in 0 until 4) v = v or ((buf[at + i].toInt() and 0xFF) shl (8 * i))
            return v
        }

        fun long(): Long {
            val at = take(8)
            var v = 0L
            for (i in 0 until 8) v = v or ((buf[at + i].toLong() and 0xFF) shl (8 * i))
            return v
        }

        fun float(): Float = Float.fromBits(int())
        fun double(): Double = Double.fromBits(long())

        fun string(): String = bytes(int()).decodeToString()

        fun box(): RawBox = RawBox(int(), float(), float(), float(), float(), float())

        /** Bytes not yet consumed, for the trailing-bytes check at the end of [decode]. */
        fun remaining(): Int = buf.size - pos
    }
}
