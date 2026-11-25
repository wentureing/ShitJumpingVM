package com.nextvm.core.virtualization.lifecycle.diag

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GuestMemoryDump 的可测部分（纯 JVM）。
 *
 * `dumpGuestStacks` 依赖 Android Looper、`dumpMemoryDex` 依赖 /proc 与 Application，
 * 都不能在纯 JVM 跑；此处只铆钉 `le32`（dex header 校验的地基）。
 *
 * 说明：docs/代码镜像/core/virtualization/lifecycle/diag/GuestMemoryDump.md
 */
class GuestMemoryDumpTest {

    @Test
    fun `G1 小端读取 4 字节`() {
        // 0x12345678 小端字节序
        val b = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        assertEquals(0x12345678, GuestMemoryDump.le32(b, 0))
    }

    @Test
    fun `G2 高位为 1 时不因符号位出错`() {
        // 0xFFFFFFFF -> Int -1（Byte.toInt() 会符号扩展，必须 and 0xFF）
        val b = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals(-1, GuestMemoryDump.le32(b, 0))
    }

    @Test
    fun `G3 尊重偏移且不读相邻字节`() {
        val b = byteArrayOf(0x00, 0x00, 0x78, 0x56, 0x34, 0x12, 0x7F)
        assertEquals(0x12345678, GuestMemoryDump.le32(b, 2))
        assertEquals(0x00000000, GuestMemoryDump.le32(b, 0))
    }

    @Test
    fun `G4 dex header 常见常量`() {
        // header_size = 0x70（112）
        val headerSize = byteArrayOf(0x70, 0x00, 0x00, 0x00)
        assertEquals(0x70, GuestMemoryDump.le32(headerSize, 0))
        // endian_tag = 0x12345678
        val endian = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        assertEquals(0x12345678, GuestMemoryDump.le32(endian, 0))
    }

    @Test
    fun `G5 小值不被高位污染`() {
        val b = byteArrayOf(0x01, 0x00, 0x00, 0x00)
        assertEquals(1, GuestMemoryDump.le32(b, 0))
    }
}
