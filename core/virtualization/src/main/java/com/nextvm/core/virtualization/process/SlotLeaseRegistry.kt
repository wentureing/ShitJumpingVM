package com.nextvm.core.virtualization.process

/**
 * 一条会话租约：实例 ↔ slot ↔ stub（+ 首次绑定的系统 taskId）。
 *
 * 镜像文档：`docs/代码镜像/core/virtualization/process/SlotLeaseRegistry.md`
 */
data class SlotLease(
    val slot: Int,
    val instanceId: String,
    val stubClassName: String,
    val acquiredAtMs: Long,
    val taskId: Int = 0,
)

/**
 * 租约表：容器主进程内存态，是"哪个实例占着哪个 slot / 哪个 stub"的唯一事实源。
 *
 * 线程安全（方法级 synchronized）；不依赖 Android，可在纯 JVM 用例里驱动。
 */
class SlotLeaseRegistry {

    private val leasesByInstance = LinkedHashMap<String, SlotLease>()
    private val leasesBySlot = LinkedHashMap<Int, SlotLease>()

    /**
     * 登记一次会话。同实例重复调用返回既有租约（幂等，不覆盖 slot/stub）。
     *
     * 同一 slot 若已被别的实例占用，**不覆盖** slot 索引 —— 冲突交给 reconcile 判定。
     */
    @Synchronized
    fun acquire(
        slot: Int,
        instanceId: String,
        stubClassName: String,
        nowMs: Long = System.currentTimeMillis(),
    ): SlotLease {
        leasesByInstance[instanceId]?.let { return it }
        val lease = SlotLease(slot, instanceId, stubClassName, nowMs)
        leasesByInstance[instanceId] = lease
        if (leasesBySlot[slot] == null) leasesBySlot[slot] = lease
        return lease
    }

    @Synchronized
    fun byInstance(instanceId: String): SlotLease? = leasesByInstance[instanceId]

    @Synchronized
    fun bySlot(slot: Int): SlotLease? = leasesBySlot[slot]

    /** 全部租约，按 slot 升序（UI 展示顺序稳定）。 */
    @Synchronized
    fun all(): List<SlotLease> =
        leasesByInstance.values.sortedWith(compareBy<SlotLease>({ it.slot }, { it.acquiredAtMs }))

    /** 首次拿到系统 taskId 时回填（幂等；只影响展示与"切前台"）。 */
    @Synchronized
    fun noteTaskId(instanceId: String, taskId: Int): SlotLease? {
        val old = leasesByInstance[instanceId] ?: return null
        if (old.taskId == taskId) return old
        val updated = old.copy(taskId = taskId)
        leasesByInstance[instanceId] = updated
        if (leasesBySlot[old.slot]?.instanceId == instanceId) leasesBySlot[old.slot] = updated
        return updated
    }

    /** 释放一个实例的租约（幂等）；返回被释放的租约，供调用方决定是否归还 stub。 */
    @Synchronized
    fun release(instanceId: String): SlotLease? {
        val lease = leasesByInstance.remove(instanceId) ?: return null
        if (leasesBySlot[lease.slot]?.instanceId == instanceId) leasesBySlot.remove(lease.slot)
        return lease
    }

    /** 清空（容器侧会话全丢时用；不触发任何归还）。 */
    @Synchronized
    fun clear() {
        leasesByInstance.clear()
        leasesBySlot.clear()
    }
}
