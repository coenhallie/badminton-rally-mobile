package com.badmintontracker.analysis.raw

/**
 * The RawInference wire format: little-endian, fixed width, no framing.
 *
 * Byte order is pinned rather than left to the platform because the writer is
 * Swift and the reader is Kotlin. Kotlin multiplatform has no ByteBuffer and
 * reaching for a platform buffer through expect/actual would let the two
 * targets disagree, so the byte handling is explicit here and shared.
 *
 * Layout:
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
 */
object RawInferenceCodec {

    private val MAGIC = byteArrayOf('R'.code.toByte(), 'A'.code.toByte(), 'W'.code.toByte(), 'I'.code.toByte())
    const val VERSION: Int = 1

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
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): RawInference {
        val r = Reader(bytes)
        val magic = r.bytes(4)
        require(magic.contentEquals(MAGIC)) {
            "not a RawInference stream: expected magic RAWI, got ${magic.decodeToString()}"
        }
        val version = r.int()
        require(version == VERSION) {
            "unsupported RawInference version $version, this build reads $VERSION"
        }
        val header = RawHeader(
            version = version,
            fps = r.double(),
            totalFrames = r.int(),
            videoWidth = r.int(),
            videoHeight = r.int(),
            modelVersion = r.string(),
        )
        val frameCount = r.int()
        val frames = ArrayList<RawFrame>(frameCount)
        repeat(frameCount) {
            val frame = r.int()
            val timestamp = r.double()
            val shuttle = if (r.byte().toInt() == 0) null else RawShuttle(
                x = r.float(), y = r.float(), confidence = r.float(),
                visible = r.byte().toInt() != 0,
            )
            val boxes = List(r.int()) { r.box() }
            val persons = List(r.int()) {
                val box = r.box()
                RawPerson(box, List(r.int()) { RawKeypoint(r.float(), r.float(), r.float()) })
            }
            frames.add(RawFrame(frame, timestamp, shuttle, boxes, persons))
        }
        return RawInference(header, frames)
    }

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
            require(pos + n <= buf.size) {
                "truncated RawInference stream: wanted $n bytes at $pos, have ${buf.size}"
            }
            val at = pos
            pos += n
            return at
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
    }
}
