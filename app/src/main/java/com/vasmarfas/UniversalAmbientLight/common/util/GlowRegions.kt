package com.vasmarfas.UniversalAmbientLight.common.util

/**
 * Разметка сетки яркости с камеры телефона для автоподбора задержки: какие ячейки - экран
 * ТВ, а какие - стена со свечением ленты вокруг него.
 *
 * Экран берётся с отступом от краёв панели, а свечение - с зазором от них: рамка ТВ и блик
 * от яркого экрана в объективе меняются вместе с картинкой и смешали бы два ряда. Кольцо
 * свечения симметрично вокруг экрана, поэтому построчное считывание матрицы камеры (rolling
 * shutter) сдвигает оба ряда одинаково и на ответ не влияет.
 *
 * [corners] - TL, TR, BR, BL экрана в долях кадра сетки.
 */
class GlowRegions(val cols: Int, val rows: Int, corners: FloatArray) {

    val screenCells: IntArray
    val glowCells: IntArray

    init {
        val cx = (corners[0] + corners[2] + corners[4] + corners[6]) / 4
        val cy = (corners[1] + corners[3] + corners[5] + corners[7]) / 4
        val inner = scaled(corners, cx, cy, SCREEN_SCALE)
        val gap = scaled(corners, cx, cy, GAP_SCALE)
        val outer = scaled(corners, cx, cy, GLOW_SCALE)
        val screen = ArrayList<Int>()
        val glow = ArrayList<Int>()
        for (row in 0 until rows) {
            val y = (row + 0.5f) / rows
            for (col in 0 until cols) {
                val x = (col + 0.5f) / cols
                when {
                    inside(inner, x, y) -> screen += row * cols + col
                    inside(outer, x, y) && !inside(gap, x, y) -> glow += row * cols + col
                }
            }
        }
        screenCells = screen.toIntArray()
        glowCells = glow.toIntArray()
    }

    /** Хватает ли ячеек на оба ряда: ТВ во весь кадр оставляет стене слишком мало места. */
    val usable: Boolean
        get() = screenCells.size >= MIN_CELLS && glowCells.size >= MIN_CELLS

    fun screenMean(grid: IntArray): Float = mean(grid, screenCells)

    fun glowMean(grid: IntArray): Float = mean(grid, glowCells)

    private fun mean(grid: IntArray, cells: IntArray): Float {
        if (cells.isEmpty()) return 0f
        var sum = 0L
        for (cell in cells) sum += grid[cell]
        return sum.toFloat() / cells.size
    }

    companion object {
        private const val SCREEN_SCALE = 0.85f
        private const val GAP_SCALE = 1.1f
        private const val GLOW_SCALE = 1.5f
        private const val MIN_CELLS = 40

        private fun scaled(corners: FloatArray, cx: Float, cy: Float, k: Float) = FloatArray(8) {
            if (it % 2 == 0) cx + (corners[it] - cx) * k else cy + (corners[it] - cy) * k
        }

        /** Точка внутри выпуклого четырёхугольника: по одну сторону от всех его сторон. */
        internal fun inside(quad: FloatArray, x: Float, y: Float): Boolean {
            var sign = 0
            for (i in 0 until 4) {
                val x1 = quad[i * 2]
                val y1 = quad[i * 2 + 1]
                val x2 = quad[(i * 2 + 2) % 8]
                val y2 = quad[(i * 2 + 3) % 8]
                val cross = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1)
                val side = if (cross > 0) 1 else if (cross < 0) -1 else 0
                if (side == 0) continue
                if (sign == 0) sign = side else if (side != sign) return false
            }
            return true
        }
    }
}
