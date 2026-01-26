package hyperionnet

import com.google.flatbuffers.FlatBufferBuilder
import com.google.flatbuffers.Table
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("unused")
class Color : Table() {
    fun __init(_i: Int, _bb: ByteBuffer) {
        __reset(_i, _bb)
    }

    fun __assign(_i: Int, _bb: ByteBuffer): Color {
        __init(_i, _bb)
        return this
    }

    fun data(): Int {
        val o = __offset(4)
        return if (o != 0) bb.getInt(o + bb_pos) else -1
    }

    fun duration(): Int {
        val o = __offset(6)
        return if (o != 0) bb.getInt(o + bb_pos) else -1
    }

    companion object {
        fun getRootAsColor(_bb: ByteBuffer): Color {
            return getRootAsColor(_bb, Color())
        }

        fun getRootAsColor(_bb: ByteBuffer, obj: Color): Color {
            _bb.order(ByteOrder.LITTLE_ENDIAN)
            return obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)
        }

        fun createColor(builder: FlatBufferBuilder, data: Int, duration: Int): Int {
            builder.startTable(2)
            addDuration(builder, duration)
            addData(builder, data)
            return endColor(builder)
        }

        fun startColor(builder: FlatBufferBuilder) {
            builder.startTable(2)
        }

        fun addData(builder: FlatBufferBuilder, data: Int) {
            builder.addInt(0, data, -1)
        }

        fun addDuration(builder: FlatBufferBuilder, duration: Int) {
            builder.addInt(1, duration, -1)
        }

        fun endColor(builder: FlatBufferBuilder): Int {
            return builder.endTable()
        }
    }
}
