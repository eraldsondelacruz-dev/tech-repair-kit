package com.tatskie.bootloaderunlocker.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootManagerTest {

    @Test
    fun classifiesKernelSuFirst() {
        assertEquals(RootType.KERNELSU, RootManager.classify(ksu = true, apatch = true, magisk = true, su = true))
    }

    @Test
    fun classifiesApatchBeforeMagisk() {
        assertEquals(RootType.APATCH, RootManager.classify(ksu = false, apatch = true, magisk = true, su = true))
    }

    @Test
    fun classifiesMagisk() {
        assertEquals(RootType.MAGISK, RootManager.classify(ksu = false, apatch = false, magisk = true, su = true))
    }

    @Test
    fun classifiesGenericSu() {
        assertEquals(RootType.OTHER_SU, RootManager.classify(ksu = false, apatch = false, magisk = false, su = true))
    }

    @Test
    fun classifiesNone() {
        assertEquals(RootType.NONE, RootManager.classify(ksu = false, apatch = false, magisk = false, su = false))
    }

    @Test
    fun detectsRootedFromIdOutput() {
        assertTrue(RootManager.looksRooted("uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0"))
        assertTrue(RootManager.looksRooted("uid=0(root) gid=0(root)"))
    }

    @Test
    fun detectsUnrootedFromIdOutput() {
        assertFalse(RootManager.looksRooted("uid=10249(u0_a249) gid=10249(u0_a249)"))
        assertFalse(RootManager.looksRooted(null))
        assertFalse(RootManager.looksRooted(""))
    }

    @Test
    fun cleansVersionFromMultilineOutput() {
        assertEquals("KernelSU 0.9.5", RootManager.cleanVersion("\nKernelSU 0.9.5\nbuild 12345\n"))
        assertEquals("27000", RootManager.cleanVersion("27000"))
    }

    @Test
    fun cleanVersionReturnsNullForBlank() {
        assertNull(RootManager.cleanVersion(null))
        assertNull(RootManager.cleanVersion(""))
        assertNull(RootManager.cleanVersion("   \n  \n"))
    }

    @Test
    fun cleanVersionIsLengthCapped() {
        val long = "v" + "x".repeat(200)
        val cleaned = RootManager.cleanVersion(long)
        assertEquals(64, cleaned?.length)
    }

    @Test
    fun hintsKernelSuManagerFromPackages() {
        assertEquals(RootType.KERNELSU, RootManager.hintFromPackages(setOf("me.weishu.kernelsu")))
    }

    @Test
    fun hintsMagiskManagerFromPackages() {
        assertEquals(RootType.MAGISK, RootManager.hintFromPackages(setOf("com.topjohnwu.magisk")))
    }

    @Test
    fun hintIsNullWhenNoManagerInstalled() {
        assertNull(RootManager.hintFromPackages(setOf("com.android.chrome", "com.foo.bar")))
    }
}
