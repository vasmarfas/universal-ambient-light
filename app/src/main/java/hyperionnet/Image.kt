package hyperionnet

import com.google.flatbuffers.FlatBufferBuilder
import com.google.flatbuffers.Table
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("unused")
class Image : Table() {
    fun __init(_i: Int, _bb: ByteBuffer) {
        __reset(_i, _bb)
    }

    fun __assign(_i: Int, _bb: ByteBuffer): Image {
        __init(_i, _bb)
        return this
    }

    fun dataType(): Byte {
        val o = __offset(4)
        return if (o != 0) bb.get(o + bb_pos) else 0
    }

    fun data(obj: Table): Table? {
        val o = __offset(6)
        return if (o != 0) __union(obj, o + bb_pos) else null
    }

    fun duration(): Int {
        val o = __offset(8)
        return if (o != 0) bb.getInt(o + bb_pos) else -1
    }

    companion object {
        fun getRootAsImage(_bb: ByteBuffer): Image {
            return getRootAsImage(_bb, Image())
        }

        fun getRootAsImage(_bb: ByteBuffer, obj: Image): Image {
            _bb.order(ByteOrder.LITTLE_ENDIAN)
            return obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)
        }

        fun createImage(builder: FlatBufferBuilder, dataType: Byte, dataOffset: Int, duration: Int): Int {
            builder.startTable(3)
            addDuration(builder, duration)
            addData(builder, dataOffset)
            addDataType(builder, dataType)
            return endImage(builder)
        }

        fun startImage(builder: FlatBufferBuilder) {
            builder.startTable(3)
        }

        fun addDataType(builder: FlatBufferBuilder, dataType: Byte) {
            builder.addByte(0, dataType, 0)
        }

        fun addData(builder: FlatBufferBuilder, dataOffset: Int) {
            builder.addOffset(1, dataOffset, 0)
        }

        fun addDuration(builder: FlatBufferBuilder, duration: Int) {
            builder.addInt(2, duration, -1)
        }

        fun endImage(builder: FlatBufferBuilder): Int {
            val o = builder.endTable()
            builder.required(o, 6) // data
            return o
        }
    }
}
