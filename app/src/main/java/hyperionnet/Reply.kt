package hyperionnet

import com.google.flatbuffers.FlatBufferBuilder
import com.google.flatbuffers.Table
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Suppress("unused")
class Reply : Table() {
    fun __init(_i: Int, _bb: ByteBuffer) {
        __reset(_i, _bb)
    }

    fun __assign(_i: Int, _bb: ByteBuffer): Reply {
        __init(_i, _bb)
        return this
    }

    fun error(): String? {
        val o = __offset(4)
        return if (o != 0) __string(o + bb_pos) else null
    }

    fun video(): Int {
        val o = __offset(6)
        return if (o != 0) bb.getInt(o + bb_pos) else -1
    }

    fun registered(): Int {
        val o = __offset(8)
        return if (o != 0) bb.getInt(o + bb_pos) else -1
    }

    companion object {
        fun getRootAsReply(_bb: ByteBuffer): Reply {
            return getRootAsReply(_bb, Reply())
        }

        fun getRootAsReply(_bb: ByteBuffer, obj: Reply): Reply {
            _bb.order(ByteOrder.LITTLE_ENDIAN)
            return obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)
        }

        fun createReply(builder: FlatBufferBuilder, errorOffset: Int, video: Int, registered: Int): Int {
            builder.startTable(3)
            addRegistered(builder, registered)
            addVideo(builder, video)
            addError(builder, errorOffset)
            return endReply(builder)
        }

        fun startReply(builder: FlatBufferBuilder) {
            builder.startTable(3)
        }

        fun addError(builder: FlatBufferBuilder, errorOffset: Int) {
            builder.addOffset(0, errorOffset, 0)
        }

        fun addVideo(builder: FlatBufferBuilder, video: Int) {
            builder.addInt(1, video, -1)
        }

        fun addRegistered(builder: FlatBufferBuilder, registered: Int) {
            builder.addInt(2, registered, -1)
        }

        fun endReply(builder: FlatBufferBuilder): Int {
            return builder.endTable()
        }

        fun finishReplyBuffer(builder: FlatBufferBuilder, offset: Int) {
            builder.finish(offset)
        }
    }
}
