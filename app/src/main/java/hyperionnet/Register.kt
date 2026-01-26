package hyperionnet

import com.google.flatbuffers.FlatBufferBuilder
import com.google.flatbuffers.Table
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("unused")
class Register : Table() {
    fun __init(_i: Int, _bb: ByteBuffer) {
        __reset(_i, _bb)
    }

    fun __assign(_i: Int, _bb: ByteBuffer): Register {
        __init(_i, _bb)
        return this
    }

    fun origin(): String? {
        val o = __offset(4)
        return if (o != 0) __string(o + bb_pos) else null
    }

    fun originAsByteBuffer(): ByteBuffer {
        return __vector_as_bytebuffer(4, 1)
    }

    fun originInByteBuffer(_bb: ByteBuffer): ByteBuffer {
        return __vector_in_bytebuffer(_bb, 4, 1)
    }

    fun priority(): Int {
        val o = __offset(6)
        return if (o != 0) bb.getInt(o + bb_pos) else 0
    }

    companion object {
        fun getRootAsRegister(_bb: ByteBuffer): Register {
            return getRootAsRegister(_bb, Register())
        }

        fun getRootAsRegister(_bb: ByteBuffer, obj: Register): Register {
            _bb.order(ByteOrder.LITTLE_ENDIAN)
            return obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)
        }

        fun createRegister(builder: FlatBufferBuilder, originOffset: Int, priority: Int): Int {
            builder.startTable(2)
            addPriority(builder, priority)
            addOrigin(builder, originOffset)
            return endRegister(builder)
        }

        fun startRegister(builder: FlatBufferBuilder) {
            builder.startTable(2)
        }

        fun addOrigin(builder: FlatBufferBuilder, originOffset: Int) {
            builder.addOffset(0, originOffset, 0)
        }

        fun addPriority(builder: FlatBufferBuilder, priority: Int) {
            builder.addInt(1, priority, 0)
        }

        fun endRegister(builder: FlatBufferBuilder): Int {
            val o = builder.endTable()
            builder.required(o, 4) // origin
            return o
        }
    }
}
