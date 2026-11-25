package com.nextvm.core.virtualization.lifecycle.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * InitTrace 的纯逻辑铆钉（T1–T9）。
 *
 * ART SLOW v3 记录格式（见 VirtualApplicationManager 顶部说明与镜像文档）：
 * 文本头 + `*methods`（`hexId\tclass\tmethod`）+ `*end`，随后 "SLOW" + 14 字节记录
 * `<u16 tid, u32 mid, u32 t1, u32 t2>`；`mid` 低 bit 是 exit 标志，方法 id = `mid & ~3`。
 *
 * 说明：docs/代码镜像/core/virtualization/lifecycle/diag/InitTrace.md
 */
class InitTraceTest {

    /** 拼一个最小可解析的 trace：头 + *methods + *end + SLOW + 记录。 */
    private fun buildTrace(
        methods: List<Triple<Int, String, String>>,
        records: List<Pair<Int, Int>>,   // mid, tid
        recSize: Int = 14,
    ): ByteArray {
        val header = StringBuilder()
        header.append("ART SLOW v3\n")
        header.append("*methods\n")
        for ((id, cls, fn) in methods) {
            header.append("0x").append(Integer.toHexString(id)).append('\t')
                .append(cls).append('\t').append(fn).append('\n')
        }
        header.append("*end\n")
        val blob = java.io.ByteArrayOutputStream()
        blob.write(header.toString().toByteArray(Charsets.UTF_8))
        blob.write("SLOW".toByteArray(Charsets.UTF_8))
        // "SLOW" 之后是 28 字节块头，记录从 blobOff + 32 起（4 魔数 + 28 块头）。
        // recSize 位于块头偏移 12（生产读 blobOff + 16 = 4 + 12）。
        // 写错块头长度或偏移会让记录起点错位、recSize 读成 0（后者曾导致死循环）。
        val head = ByteArray(28)
        head[12] = (recSize and 0xFF).toByte()
        head[13] = ((recSize shr 8) and 0xFF).toByte()
        head[14] = ((recSize shr 16) and 0xFF).toByte()
        head[15] = ((recSize shr 24) and 0xFF).toByte()
        blob.write(head)
        for ((mid, tid) in records) {
            val r = ByteArray(recSize)
            r[0] = (tid and 0xFF).toByte()
            r[1] = ((tid shr 8) and 0xFF).toByte()
            r[2] = (mid and 0xFF).toByte()
            r[3] = ((mid shr 8) and 0xFF).toByte()
            r[4] = ((mid shr 16) and 0xFF).toByte()
            r[5] = ((mid shr 24) and 0xFF).toByte()
            r[10] = 0x39
            r[11] = 0x05 // t2 = 0x539
            blob.write(r)
        }
        return blob.toByteArray()
    }

    @Test
    fun `T1 textEnd 找到星号 end`() {
        val data = "abc*end\nxyz".toByteArray()
        assertEquals(3, InitTrace.textEnd(data))
    }

    @Test
    fun `T2 textEnd 找不到返回 -1`() {
        assertEquals(-1, InitTrace.textEnd("no marker here".toByteArray()))
        assertEquals(-1, InitTrace.textEnd(ByteArray(0)))
    }

    @Test
    fun `T3 parseMethods 解析 id 为十六进制`() {
        val text = "*methods\n0x10\tcom.A\trun\n0x2a\tcom.B\tgo\n*end\n"
        val m = InitTrace.parseMethods(text)
        assertEquals(mapOf(0x10 to "com.A.run", 0x2a to "com.B.go"), m)
    }

    @Test
    fun `T4 parseMethods 忽略畸形行与 * 段`() {
        val text = "*other\n0x10\tcom.A\trun\n*methods\nbadline\n0xzz\tcom.C\tx\n0x30\tcom.D\ty\n"
        val m = InitTrace.parseMethods(text)
        assertEquals(mapOf(0x30 to "com.D.y"), m)
    }

    @Test
    fun `T5 isSlow 与 u32`() {
        val data = "SLOW".toByteArray() + ByteArray(4)
        assertTrue(InitTrace.isSlow(data, 0))
        assertFalse(InitTrace.isSlow("XXXX".toByteArray(), 0))

        val n = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        assertEquals(0x04030201, InitTrace.u32(n, 0))
    }

    @Test
    fun `T6 matchesAny 命中任一前缀`() {
        val keys = arrayOf("TelephonyManager", "com.A")
        assertTrue(InitTrace.matchesAny("android.telephony.TelephonyManager.get", keys))
        assertTrue(InitTrace.matchesAny("com.A.foo", keys))
        assertFalse(InitTrace.matchesAny("com.B.bar", keys))
    }

    @Test
    fun `T7 collectRecords 只保留 ENTER 且命中 key 的记录`() {
        // mid 0x10 -> base 0x10（ENTER, mid&1==0）；0x11 -> 同 base 但 exit 位
        val data = buildTrace(
            methods = listOf(
                Triple(0x10, "android.telephony.TelephonyManager", "getDeviceId"),
                Triple(0x20, "com.other", "noise"),
            ),
            records = listOf(0x10 to 7, 0x11 to 7, 0x20 to 8),
        )
        val textEnd = InitTrace.textEnd(data)
        val methods = InitTrace.parseMethods(String(data, 0, textEnd, Charsets.UTF_8))
        val blobOff = textEnd + 5
        val recSize = InitTrace.u32(data, blobOff + 16)
        val r = InitTrace.collectRecords(
            data, blobOff + 32, recSize, methods,
            arrayOf("TelephonyManager"), false
        )
        assertEquals(3, r.total)
        assertEquals(1, r.lines.size)
        assertTrue(r.lines[0].contains("TelephonyManager.getDeviceId"))
        assertTrue(r.lines[0].startsWith("t2=1337 tid=7 "))
    }

    @Test
    fun `T8 sampled=true 时保留 exit 记录`() {
        val data = buildTrace(
            methods = listOf(Triple(0x10, "com.A", "go")),
            records = listOf(0x10 to 1, 0x11 to 1),
        )
        val textEnd = InitTrace.textEnd(data)
        val methods = InitTrace.parseMethods(String(data, 0, textEnd, Charsets.UTF_8))
        val blobOff = textEnd + 5
        val recSize = InitTrace.u32(data, blobOff + 16)
        val r = InitTrace.collectRecords(
            data, blobOff + 32, recSize, methods, arrayOf("com.A"), true
        )
        assertEquals(2, r.lines.size)
    }

    @Test
    fun `T10 recSize 为 0 时立即返回不死循环`() {
        // 回归：T8 曾因构造把 recSize 写到块头 [16]（生产读的是 [12]）而读到 0，
        // collectRecords 里 off += 0 -> 死循环 -> CI 卡住 8 分钟。
        val data = buildTrace(listOf(Triple(0x10, "com.A", "go")), listOf(0x10 to 1))
        val te = InitTrace.textEnd(data)
        val methods = InitTrace.parseMethods(String(data, 0, te, Charsets.UTF_8))
        val r = InitTrace.collectRecords(data, te + 5 + 32, 0, methods, arrayOf("com.A"), false)
        assertEquals(0, r.total)
        assertEquals(0, r.lines.size)
    }

    @Test
    fun `T9 无 SLOW 或 recSize 过小被上层拒绝`() {
        val noSlow = "ART SLOW v3\n*methods\n*end\n".toByteArray()
        val textEnd = InitTrace.textEnd(noSlow)
        val blobOff = textEnd + 5
        assertFalse(InitTrace.isSlow(noSlow, blobOff))

        val small = buildTrace(emptyList(), emptyList(), recSize = 14)
        val te = InitTrace.textEnd(small)
        assertEquals(14, InitTrace.u32(small, te + 5 + 16))
    }
}
