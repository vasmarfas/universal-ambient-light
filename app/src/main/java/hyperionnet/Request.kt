package hyperionnet

import com.google.flatbuffers.FlatBufferBuilder
import com.google.flatbuffers.Table
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("unused")
class Request : Table() {
    fun __init(_i: Int, _bb: ByteBuffer) {
        __reset(_i, _bb)
    }

    fun __assign(_i: Int, _bb: ByteBuffer): Request {
        __init(_i, _bb)
        return this
    }

    fun commandType(): Byte {
        val o = __offset(4)
        return if (o != 0) bb.get(o + bb_pos) else 0
    }

    fun command(obj: Table): Table? {
        val o = __offset(6)
        return if (o != 0) __union(obj, o + bb_pos) else null
    }

    companion object {
        fun getRootAsRequest(_bb: ByteBuffer): Request {
            return getRootAsRequest(_bb, Request())
        }

        fun getRootAsRequest(_bb: ByteBuffer, obj: Request): Request {
            _bb.order(ByteOrder.LITTLE_ENDIAN)
            return obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)
        }

        fun createRequest(builder: FlatBufferBuilder, commandType: Byte, commandOffset: Int): Int {
            builder.startTable(2)
            addCommand(builder, commandOffset)
            addCommandType(builder, commandType)
            return endRequest(builder)
        }

        fun startRequest(builder: FlatBufferBuilder) {
            builder.startTable(2)
        }

        fun addCommandType(builder: FlatBufferBuilder, commandType: Byte) {
            builder.addByte(0, commandType, 0)
        }

        fun addCommand(builder: FlatBufferBuilder, commandOffset: Int) {
            builder.addOffset(1, commandOffset, 0)
        }

        fun endRequest(builder: FlatBufferBuilder): Int {
            val o = builder.endTable()
            builder.required(o, 6) // command
            return o
        }

        fun finishRequestBuffer(builder: FlatBufferBuilder, offset: Int) {
            builder.finish(offset)
        }
    }
}
