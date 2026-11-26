package com.nextvm.core.virtualization.process

import com.nextvm.core.model.VirtualConstants

/**
 * 把「租约 + 系统进程 + 系统任务 + 实例记录」算成 slot 状态与清理计划。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/process/SlotReconciler.md`
 */
object SlotReconciler {

    /** stub 类名前缀（与 manifest 里 `com.nextvm.app.stub.PN$Xxx` 一一对应）。 */
    private val STUB_SLOT_PREFIX = VirtualConstants.STUB_PREFIX + ".P"

    /** `com.nextvm.app.stub.P3$Standard01` → 3；非 stub 类名 → null。 */
    fun parseSlotFromStubClass(className: String?): Int? {
        if (className == null) return null
        if (!className.startsWith(STUB_SLOT_PREFIX)) return null
        val rest = className.substring(STUB_SLOT_PREFIX.length)
        val digits = rest.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        if (rest.getOrNull(digits.length) != '$') return null
        return digits.toIntOrNull()
    }

    /** `com.nextvm.app:p3` → 3；主进程或别的包 → null。 */
    fun parseSlotFromProcessName(hostPackage: String, processName: String?): Int? {
        if (processName == null) return null
        val prefix = "$hostPackage:p"
        if (!processName.startsWith(prefix)) return null
        return processName.substring(prefix.length).toIntOrNull()
    }

    /**
     * 纯判定：不读系统、不写状态。
     *
     * 每个 slot 的输出规则：无租约+有进程 → ORPHAN；有租约+无进程 → STALE（并进入归还清单）；
     * 同 slot 多租约 → 最近的判 RUNNING，其余标 CONFLICT；任务归属与租约不符 → CONFLICT。
     */
    fun reconcile(
        leases: List<SlotLease>,
        procs: List<ProcSnapshot>,
        tasks: List<TaskSnapshot>,
        records: List<RecordSnapshot> = emptyList(),
        maxSlots: Int = VirtualConstants.MAX_PROCESS_SLOTS,
    ): ReconcilePlan {
        val pkgOf = records.associate { it.instanceId to it.packageName }

        val procBySlot = LinkedHashMap<Int, ProcSnapshot>()
        for (p in procs) procBySlot.putIfAbsent(p.slot, p)

        val taskBySlot = LinkedHashMap<Int, TaskSnapshot>()
        for (t in tasks) {
            val slot = t.slot ?: continue
            if (t.numActivities <= 0) continue
            taskBySlot.putIfAbsent(slot, t)
        }

        val leasesBySlot = leases.groupBy { it.slot }

        // 每个 slot 的残留 task 数（同一 slot 多点几次就会多几个 task；ORPHAN 展示要用）
        val taskCountBySlot = HashMap<Int, Int>()
        for (t in tasks) {
            val slot = t.slot ?: continue
            taskCountBySlot[slot] = (taskCountBySlot[slot] ?: 0) + 1
        }

        val statuses = ArrayList<SlotStatus>()
        val releaseLeases = ArrayList<SlotLease>()
        val releaseSlots = LinkedHashSet<Int>()
        val notRunning = LinkedHashSet<String>()
        val orphans = ArrayList<SlotStatus>()

        for (slot in 0 until maxSlots) {
            val slotLeases = (leasesBySlot[slot] ?: emptyList()).sortedBy { it.acquiredAtMs }
            val proc = procBySlot[slot]
            val task = taskBySlot[slot]

            if (slotLeases.isEmpty()) {
                if (proc != null) {
                    val orphan = SlotStatus(
                        slot = slot,
                        state = SlotState.ORPHAN,
                        pid = proc.pid,
                        taskId = task?.taskId ?: 0,
                        topComponent = task?.topComponent,
                        note = "orphan: process without lease",
                        taskCount = taskCountBySlot[slot] ?: 0,
                    )
                    statuses += orphan
                    orphans += orphan
                }
                continue
            }

            val current = slotLeases.last()
            for (older in slotLeases.dropLast(1)) {
                statuses += SlotStatus(
                    slot = slot,
                    state = SlotState.CONFLICT,
                    instanceId = older.instanceId,
                    packageName = pkgOf[older.instanceId],
                    pid = proc?.pid ?: 0,
                    note = "conflict: preempted by " + current.instanceId,
                    taskCount = taskCountBySlot[slot] ?: 0,
                )
            }

            val taskOwner = task?.instanceId
            val taskMismatch = taskOwner != null && taskOwner != current.instanceId

            when {
                proc == null -> {
                    statuses += SlotStatus(
                        slot = slot,
                        state = SlotState.STALE,
                        instanceId = current.instanceId,
                        packageName = pkgOf[current.instanceId],
                        note = "stale: no process",
                        taskCount = taskCountBySlot[slot] ?: 0,
                    )
                    releaseLeases += current
                    releaseSlots += slot
                    notRunning += current.instanceId
                }

                taskMismatch -> {
                    statuses += SlotStatus(
                        slot = slot,
                        state = SlotState.CONFLICT,
                        instanceId = current.instanceId,
                        packageName = pkgOf[current.instanceId],
                        pid = proc.pid,
                        taskId = task?.taskId ?: 0,
                        topComponent = task?.topComponent,
                        note = "conflict: task belongs to " + taskOwner,
                        taskCount = taskCountBySlot[slot] ?: 0,
                    )
                }

                else -> {
                    statuses += SlotStatus(
                        slot = slot,
                        state = SlotState.RUNNING,
                        instanceId = current.instanceId,
                        packageName = pkgOf[current.instanceId],
                        pid = proc.pid,
                        taskId = task?.taskId ?: current.taskId,
                        topComponent = task?.topComponent,
                        note = if (task == null) "no-task" else "",
                        taskCount = taskCountBySlot[slot] ?: 0,
                    )
                }
            }
        }

        return ReconcilePlan(
            statuses = statuses.sortedBy { it.slot },
            releaseLeases = releaseLeases,
            releaseStubsForSlots = releaseSlots.sorted(),
            markNotRunning = notRunning.toList(),
            orphans = orphans,
        )
    }
}
