package com.nextvm.core.virtualization.process

/**
 * "占用了几个 process slot"的唯一算法（设置页计数 / 列表页标题共用）。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/process/SlotUsage.md`
 */
object SlotUsage {

    /**
     * 有进程占着的 slot 数。
     *
     * `RUNNING` 与 `ORPHAN` 都算占用（后者是"进程在、租约丢了"，用户看到的就是在跑）；
     * 同一 slot 只算一次（`CONFLICT` / 多条记录不重复计）。
     */
    fun countInUse(statuses: List<SlotStatus>): Int = statuses
        .filter { it.state == SlotState.RUNNING || it.state == SlotState.ORPHAN }
        .distinctBy { it.slot }
        .size
}
