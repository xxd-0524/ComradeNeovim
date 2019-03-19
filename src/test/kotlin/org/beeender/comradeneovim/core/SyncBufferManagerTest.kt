package org.beeender.comradeneovim.core

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import io.mockk.*

class SyncBufferManagerTest : LightCodeInsightFixtureTestCase() {
    private lateinit var nvimInstance: NvimInstance
    private lateinit var vf: VirtualFile
    private lateinit var bufferManger: SyncBufferManager

    override fun setUp() {
        super.setUp()
        nvimInstance = mockk(relaxed = true)
        mockkConstructor(Synchronizer::class)
        every { anyConstructed<Synchronizer>().initFromJetBrain() } just Runs

        vf = myFixture.copyFileToProject("empty.java")
        bufferManger = SyncBufferManager(nvimInstance)
    }

    override fun tearDown() {
        bufferManger.cleanUp(myFixture.project)
        super.tearDown()
    }

    override fun getTestDataPath(): String {
        return "tests/testData"
    }

    fun test_loadBuffer() {
        bufferManger.loadBuffer(1, vf.path)
        val buf = bufferManger.findBufferById(1)
        assertNotNull(buf)
        assertEquals(buf!!.id,  1)
    }

    fun test_comradeBufEnter() {
        val params = ComradeBufEnterParams(1, vf.path)
        val notification = ComradeBufEnterParams.toNotification(params)
        bufferManger.comradeBufEnter(notification)
        val buf = bufferManger.findBufferById(1)
        assertNotNull(buf)
        assertEquals(buf!!.id,  1)
    }
}