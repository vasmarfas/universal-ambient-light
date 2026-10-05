package com.vasmarfas.UniversalAmbientLight.common.network

import com.google.flatbuffers.FlatBufferBuilder
import hyperionnet.Command
import hyperionnet.Image
import hyperionnet.ImageType
import hyperionnet.RawImage
import hyperionnet.Request
import java.lang.management.ManagementFactory
import org.junit.Test

class HyperionAllocProbeTest {

    private fun build(builder: FlatBufferBuilder, data: ByteArray, w: Int, h: Int) {
        val dataOffset = RawImage.createDataVector(builder, data)
        val raw = RawImage.createRawImage(builder, dataOffset, w, h)
        val image = Image.createImage(builder, ImageType.RawImage, raw, -1)
        val request = Request.createRequest(builder, Command.Image, image)
        Request.finishRequestBuffer(builder, request)
    }

    @Test
    fun probe() {
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val w = 1920
        val h = 1080
        val frame = ByteArray(w * h * 3)
        val frames = 20
        val sink = java.io.ByteArrayOutputStream(0)

        val before = mx.getThreadAllocatedBytes(id)
        repeat(frames) {
            val b = FlatBufferBuilder(1024)
            build(b, frame, w, h)
            val bb = b.dataBuffer()
            val copy = ByteArray(bb.remaining())
            bb.get(copy)
            sink.reset()
        }
        val old = (mx.getThreadAllocatedBytes(id) - before) / frames

        val shared = FlatBufferBuilder(1024)
        build(shared, frame, w, h)
        val before2 = mx.getThreadAllocatedBytes(id)
        repeat(frames) {
            shared.clear()
            build(shared, frame, w, h)
            shared.dataBuffer()
        }
        val new = (mx.getThreadAllocatedBytes(id) - before2) / frames
        println("PROBE frame=${frame.size} old=$old new=$new bytes/frame")
    }
}
