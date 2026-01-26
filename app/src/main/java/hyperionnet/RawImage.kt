package hyperionnet

import com.google.flatbuffers.FlatBufferBuilder
import com.google.flatbuffers.Table
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("unused")
class RawImage : Table() {
    fun __init(_i: Int, _bb: ByteBuffer) {
        __reset(_i, _bb)
    }

    fun __assign(_i: Int, _bb: ByteBuffer): RawImage {
        __init(_i, _bb)
        return this
    }

    fun data(j: Int): Int {
        val o = __offset(4)
        return if (o != 0) bb.get(__vector(o) + j * 1).toInt() and 0xFF else 0
    }

    fun dataLength(): Int {
        val o = __offset(4)
        return if (o != 0) __vector_len(o) else 0
    }

    fun dataAsByteBuffer(): ByteBuffer {
        return __vector_as_bytebuffer(4, 1)
    }

    fun width(): Int {
        val o = __offset(6)
        return if (o != 0) bb.getInt(o + bb_pos) else -1
    }

    fun height(): Int {
        val o = __offset(8)
        return if (o != 0) bb.getInt(o + bb_pos) else -1
    }

    companion object {
        fun getRootAsRawImage(_bb: ByteBuffer): RawImage {
            return getRootAsRawImage(_bb, RawImage())
        }

        fun getRootAsRawImage(_bb: ByteBuffer, obj: RawImage): RawImage {
            _bb.order(ByteOrder.LITTLE_ENDIAN)
            return obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)
        }

        fun createRawImage(builder: FlatBufferBuilder, dataOffset: Int, width: Int, height: Int): Int {
            builder.startTable(3)
            addHeight(builder, height)
            addWidth(builder, width)
            addData(builder, dataOffset)
            return endRawImage(builder)
        }

        fun startRawImage(builder: FlatBufferBuilder) {
            builder.startTable(3)
        }

        fun addData(builder: FlatBufferBuilder, dataOffset: Int) {
            builder.addOffset(0, dataOffset, 0)
        }

        fun createDataVector(builder: FlatBufferBuilder, data: ByteArray): Int {
            return builder.createByteVector(data)
        }

        fun addWidth(builder: FlatBufferBuilder, width: Int) {
            builder.addInt(1, width, -1)
        }

        fun addHeight(builder: FlatBufferBuilder, height: Int) {
            builder.addInt(2, height, -1)
        }

        fun endRawImage(builder: FlatBufferBuilder): Int {
            return builder.endTable()
        }
    }
}
