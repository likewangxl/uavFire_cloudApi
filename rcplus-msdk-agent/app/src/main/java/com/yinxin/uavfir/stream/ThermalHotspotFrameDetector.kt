package com.yinxin.uavfir.stream

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class ThermalHotspotFrameDetector(
    private val maxGridWidth: Int = 320,
    private val maxGridHeight: Int = 180,
) {
    data class Result(
        val region: ThermalMeasureRegion,
        val maxBrightness: Int,
        val meanBrightness: Double,
        val threshold: Int,
        val componentAreaRatio: Double,
        val detectCostMs: Long,
    )

    fun detect(
        rgba: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
    ): Result? = detectHotspots(rgba, offset, length, width, height, maxResults = 1).firstOrNull()

    fun detectHotspots(
        rgba: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        maxResults: Int = DEFAULT_MAX_HOTSPOTS,
    ): List<Result> {
        val startedAt = System.currentTimeMillis()
        if (width <= 0 || height <= 0 || offset < 0 || length < width * height * RGBA_BYTES) {
            return emptyList()
        }
        if (offset + width * height * RGBA_BYTES > rgba.size) {
            return emptyList()
        }

        val gridWidth = min(width, maxGridWidth)
        val gridHeight = min(height, maxGridHeight)
        if (gridWidth <= 0 || gridHeight <= 0) {
            return emptyList()
        }

        val brightness = IntArray(gridWidth * gridHeight)
        var sum = 0.0
        var sumSq = 0.0
        var maxBrightness = 0
        for (gy in 0 until gridHeight) {
            val sourceY = ((gy + 0.5) * height / gridHeight).toInt().coerceIn(0, height - 1)
            for (gx in 0 until gridWidth) {
                val sourceX = ((gx + 0.5) * width / gridWidth).toInt().coerceIn(0, width - 1)
                val sourceOffset = offset + (sourceY * width + sourceX) * RGBA_BYTES
                val value = luminance(rgba, sourceOffset)
                val index = gy * gridWidth + gx
                brightness[index] = value
                sum += value
                sumSq += value * value
                maxBrightness = max(maxBrightness, value)
            }
        }

        val count = gridWidth * gridHeight
        val mean = sum / count
        val variance = max(0.0, sumSq / count - mean * mean)
        val stdDev = sqrt(variance)
        val threshold = max(MIN_HOT_BRIGHTNESS, (mean + stdDev * STDDEV_MULTIPLIER).toInt())
            .coerceAtMost(maxBrightness - MIN_HOT_DELTA)
        if (maxBrightness < MIN_HOT_BRIGHTNESS || threshold <= 0) {
            return emptyList()
        }

        val hot = BooleanArray(count) { brightness[it] >= threshold }
        val visited = BooleanArray(count)
        val queue = IntArray(count)
        val components = mutableListOf<Component>()
        for (index in 0 until count) {
            if (!hot[index] || visited[index]) {
                continue
            }
            val component = collectComponent(index, hot, visited, queue, brightness, gridWidth, gridHeight)
            if (!component.accepted(count)) {
                continue
            }
            components += component
        }

        return components
            .sortedByDescending { it.score }
            .take(maxResults.coerceAtLeast(1))
            .map { component ->
                Result(
                    region = component.toRegion(gridWidth, gridHeight),
                    maxBrightness = component.maxBrightness,
                    meanBrightness = mean,
                    threshold = threshold,
                    componentAreaRatio = component.area.toDouble() / count,
                    detectCostMs = System.currentTimeMillis() - startedAt,
                )
            }
    }

    private fun collectComponent(
        start: Int,
        hot: BooleanArray,
        visited: BooleanArray,
        queue: IntArray,
        brightness: IntArray,
        gridWidth: Int,
        gridHeight: Int,
    ): Component {
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true
        var area = 0
        var minX = gridWidth
        var minY = gridHeight
        var maxX = 0
        var maxY = 0
        var maxBrightness = 0

        while (head < tail) {
            val index = queue[head++]
            val x = index % gridWidth
            val y = index / gridWidth
            area += 1
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            maxBrightness = max(maxBrightness, brightness[index])

            enqueueNeighbor(x - 1, y, gridWidth, gridHeight, hot, visited, queue, tail)?.let { tail = it }
            enqueueNeighbor(x + 1, y, gridWidth, gridHeight, hot, visited, queue, tail)?.let { tail = it }
            enqueueNeighbor(x, y - 1, gridWidth, gridHeight, hot, visited, queue, tail)?.let { tail = it }
            enqueueNeighbor(x, y + 1, gridWidth, gridHeight, hot, visited, queue, tail)?.let { tail = it }
        }

        return Component(area, minX, minY, maxX, maxY, maxBrightness)
    }

    private fun enqueueNeighbor(
        x: Int,
        y: Int,
        gridWidth: Int,
        gridHeight: Int,
        hot: BooleanArray,
        visited: BooleanArray,
        queue: IntArray,
        tail: Int,
    ): Int? {
        if (x !in 0 until gridWidth || y !in 0 until gridHeight) {
            return null
        }
        val index = y * gridWidth + x
        if (!hot[index] || visited[index]) {
            return null
        }
        visited[index] = true
        queue[tail] = index
        return tail + 1
    }

    private fun Component.accepted(totalArea: Int): Boolean {
        val ratio = area.toDouble() / totalArea
        val width = maxX - minX + 1
        val height = maxY - minY + 1
        return area >= MIN_COMPONENT_AREA &&
            ratio <= MAX_COMPONENT_AREA_RATIO &&
            width >= MIN_COMPONENT_SIDE &&
            height >= MIN_COMPONENT_SIDE
    }

    private fun Component.toRegion(gridWidth: Int, gridHeight: Int): ThermalMeasureRegion {
        val padX = max(MIN_REGION_GRID_PAD, ((maxX - minX + 1) * REGION_PAD_RATIO).toInt())
        val padY = max(MIN_REGION_GRID_PAD, ((maxY - minY + 1) * REGION_PAD_RATIO).toInt())
        val x0 = (minX - padX).coerceAtLeast(0)
        val y0 = (minY - padY).coerceAtLeast(0)
        val x1 = (maxX + padX + 1).coerceAtMost(gridWidth)
        val y1 = (maxY + padY + 1).coerceAtMost(gridHeight)
        val regionWidth = ((x1 - x0).toDouble() / gridWidth).coerceAtLeast(MIN_REGION_SIZE)
        val regionHeight = ((y1 - y0).toDouble() / gridHeight).coerceAtLeast(MIN_REGION_SIZE)
        val x = (x0.toDouble() / gridWidth).coerceIn(0.0, 1.0 - regionWidth)
        val y = (y0.toDouble() / gridHeight).coerceIn(0.0, 1.0 - regionHeight)
        return ThermalMeasureRegion(
            x = x,
            y = y,
            width = regionWidth.coerceAtMost(1.0 - x),
            height = regionHeight.coerceAtMost(1.0 - y),
        )
    }

    private data class Component(
        val area: Int,
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val maxBrightness: Int,
    ) {
        val score: Double
            get() = maxBrightness - area * AREA_SCORE_PENALTY
    }

    companion object {
        private const val RGBA_BYTES = 4
        private const val MIN_HOT_BRIGHTNESS = 170
        private const val MIN_HOT_DELTA = 5
        private const val STDDEV_MULTIPLIER = 2.0
        private const val MIN_COMPONENT_AREA = 6
        private const val MIN_COMPONENT_SIDE = 2
        private const val MAX_COMPONENT_AREA_RATIO = 0.12
        private const val AREA_SCORE_PENALTY = 0.02
        private const val REGION_PAD_RATIO = 0.35
        private const val MIN_REGION_GRID_PAD = 2
        private const val MIN_REGION_SIZE = 0.06
        private const val DEFAULT_MAX_HOTSPOTS = 3

        private fun luminance(rgba: ByteArray, offset: Int): Int {
            val r = rgba[offset].toInt() and 0xff
            val g = rgba[offset + 1].toInt() and 0xff
            val b = rgba[offset + 2].toInt() and 0xff
            return (r * 299 + g * 587 + b * 114) / 1000
        }
    }
}
