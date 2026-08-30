package com.vasmarfas.UniversalAmbientLight.common.network

/**
 * Очередь кадров под задержку вывода для Hyperion. У WLED и Adalight задержка живёт в
 * сглаживании и хранит цвета светодиодов, а клиенту Hyperion сервер нужен целый кадр -
 * своего сглаживания у него нет, и раньше настройка задержки на него просто не действовала.
 *
 * Кадры шире [MAX_WIDTH] уменьшаются: при 60 кадрах в секунду и задержке в секунду очередь
 * из 4K-кадров заняла бы гигабайт, а серверу для раскладки по ленте хватает и такого.
 * Буферы переиспользуются - поток захвата отдаёт кадр по 30–60 раз в секунду.
 */
internal class FrameDelayLine {

    class Frame(var data: ByteArray, var width: Int, var height: Int, var dueAt: Long)

    private val mQueue = ArrayDeque<Frame>()
    private val mSpare = ArrayDeque<Frame>()

    val size: Int
        @Synchronized get() = mQueue.size

    @Synchronized
    fun push(source: ByteArray, width: Int, height: Int, dueAt: Long) {
        val factor = ((width + MAX_WIDTH - 1) / MAX_WIDTH).coerceAtLeast(1)
        val w = (width / factor).coerceAtLeast(1)
        val h = (height / factor).coerceAtLeast(1)
        val bytes = w * h * 3
        val spare = mSpare.removeFirstOrNull()
        val frame = if (spare != null && spare.data.size == bytes) spare else Frame(ByteArray(bytes), w, h, dueAt)
        frame.width = w
        frame.height = h
        frame.dueAt = dueAt
        if (factor == 1) {
            System.arraycopy(source, 0, frame.data, 0, minOf(bytes, source.size))
        } else {
            downscale(source, width, height, factor, frame)
        }
        mQueue.addLast(frame)
    }

    /**
     * Самый свежий из кадров, чьё время пришло. Созревшие раньше него уже опоздали - они
     * уходят в запас, а не на ленту.
     */
    @Synchronized
    fun takeDue(now: Long): Frame? {
        var due: Frame? = null
        while (mQueue.isNotEmpty() && mQueue.first().dueAt <= now) {
            due?.let { mSpare.addLast(it) }
            due = mQueue.removeFirst()
        }
        return due
    }

    @Synchronized
    fun recycle(frame: Frame) {
        if (mSpare.size < SPARE_LIMIT) mSpare.addLast(frame)
    }

    @Synchronized
    fun clear() {
        while (mQueue.isNotEmpty()) recycle(mQueue.removeFirst())
    }

    private fun downscale(source: ByteArray, width: Int, height: Int, factor: Int, target: Frame) {
        val area = factor * factor
        var out = 0
        for (ty in 0 until target.height) {
            for (tx in 0 until target.width) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0 until factor) {
                    var index = ((ty * factor + dy) * width + tx * factor) * 3
                    for (dx in 0 until factor) {
                        if (index + 2 < source.size) {
                            r += source[index].toInt() and 0xFF
                            g += source[index + 1].toInt() and 0xFF
                            b += source[index + 2].toInt() and 0xFF
                        }
                        index += 3
                    }
                }
                target.data[out++] = (r / area).toByte()
                target.data[out++] = (g / area).toByte()
                target.data[out++] = (b / area).toByte()
            }
        }
    }

    companion object {
        const val MAX_WIDTH = 256
        private const val SPARE_LIMIT = 8
    }
}
