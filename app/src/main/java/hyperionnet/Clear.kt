package hyperionnet

import com.google.flatbuffers.FlatBufferBuilder
import com.google.flatbuffers.Table
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("unused")
class Clear : Table() {
    fun __init(_i: Int, _bb: ByteBuffer) {
        __reset(_i, _bb)
    }

    fun __assign(_i: Int, _bb: ByteBuffer): Clear {
        __init(_i, _bb)
        return this
    }

    fun priority(): Int {
        val o = __offset(4)
        return if (o != 0) bb.getInt(o + bb_pos) else 0
    }

    companion object {
        fun getRootAsClear(_bb: ByteBuffer): Clear {
            return getRootAsClear(_bb, Clear())
        }

        fun getRootAsClear(_bb: ByteBuffer, obj: Clear): Clear {
            _bb.order(ByteOrder.LITTLE_ENDIAN)
            return obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)
        }

        fun createClear(builder: FlatBufferBuilder, priority: Int): Int {
            builder.startTable(1)
            addPriority(builder, priority)
            return endClear(builder)
        }

        fun startClear(builder: FlatBufferBuilder) {
            builder.startTable(1)
        }

        fun addPriority(builder: FlatBufferBuilder, priority: Int) {
            builder.addInt(0, priority, 0)
        }

        fun endClear(builder: FlatBufferBuilder): Int {
            return builder.endTable()
        }
    }
}
