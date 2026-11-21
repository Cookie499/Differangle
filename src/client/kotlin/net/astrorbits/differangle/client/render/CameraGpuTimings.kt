package net.astrorbits.differangle.client.render

import com.mojang.blaze3d.systems.GpuQueryPool
import com.mojang.blaze3d.systems.RenderSystem
import org.slf4j.LoggerFactory

/**
 * 整帧 GPU 计时：每帧写入 begin/end 两个 timestamp，2 组 × 2 槽轮换，
 * 避免覆盖上一帧尚未收回的结果。所有入口静默降级为 no-op，绝不阻塞或打断渲染线程。
 *
 * 与 TimerQuery 的差异：只保留最近一次成功收数（不维护滑动平均），
 * 返回毫秒而非纳秒；begin/end 不抛状态异常；设备/池不可用时直接禁用。
 */
class CameraGpuTimings : AutoCloseable {
    private companion object {
        const val ROTATIONS = 2
        const val SLOTS_PER_ROTATION = 2
    }

    private val logger = LoggerFactory.getLogger("Differangle")
    private var pool: GpuQueryPool? = try {
        RenderSystem.getDevice().createTimestampQueryPool(ROTATIONS * SLOTS_PER_ROTATION)
    } catch (failure: Exception) {
        logger.warn("GPU timestamp query pool unavailable; GPU frame timings disabled", failure)
        null
    }
    private val periodNanos: Double = try {
        RenderSystem.getDevice().deviceInfo.timestampPeriod.toDouble()
    } catch (failure: Exception) {
        logger.warn("GPU timestamp period unavailable; GPU frame timings disabled", failure)
        0.0
    }
    private var rotation = 0
    private var started = false
    private var awaiting = false

    fun begin() {
        val queryPool = pool ?: return
        if (started || awaiting) return
        rotation = (rotation + 1) % ROTATIONS
        try {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(queryPool, rotation * SLOTS_PER_ROTATION)
            started = true
        } catch (failure: Exception) {
            logger.warn("GPU timestamp begin failed", failure)
            started = false
        }
    }

    fun end() {
        val queryPool = pool ?: return
        if (!started) return
        try {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(queryPool, rotation * SLOTS_PER_ROTATION + 1)
            started = false
            awaiting = true
        } catch (failure: Exception) {
            logger.warn("GPU timestamp end failed", failure)
            started = false
            awaiting = false
        }
    }

    /** 非阻塞读取上一帧 GPU 总耗时（毫秒）；未就绪返回 null，成功则释放当前 rotation 供下一帧复用。 */
    fun collectIfReady(): Double? {
        val queryPool = pool ?: return null
        if (!awaiting) return null
        return try {
            val timestamps = queryPool.getValues(rotation * SLOTS_PER_ROTATION, SLOTS_PER_ROTATION)
            val begin = timestamps[0]
            val end = timestamps[1]
            if (begin.isPresent && end.isPresent) {
                awaiting = false
                (end.asLong - begin.asLong) * periodNanos / 1_000_000.0
            } else {
                null
            }
        } catch (failure: Exception) {
            logger.warn("GPU timestamp collection failed", failure)
            awaiting = false
            null
        }
    }

    override fun close() {
        started = false
        awaiting = false
        val queryPool = pool ?: return
        pool = null
        try {
            queryPool.close()
        } catch (failure: Exception) {
            logger.warn("GPU query pool close failed", failure)
        }
    }
}
