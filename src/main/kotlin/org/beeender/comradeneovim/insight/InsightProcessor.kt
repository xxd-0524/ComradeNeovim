package org.beeender.comradeneovim.insight

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.beeender.comradeneovim.ComradeNeovimPlugin
import org.beeender.comradeneovim.ComradeScope
import org.beeender.comradeneovim.buffer.SyncBuffer
import org.beeender.comradeneovim.buffer.SyncBufferManager
import org.beeender.comradeneovim.buffer.SyncBufferManagerListener
import org.beeender.comradeneovim.core.*
import org.beeender.comradeneovim.invokeOnMainAndWait
import org.beeender.neovim.annotation.RequestHandler
import java.util.*

private const val PROCESS_INTERVAL = 500L

object InsightProcessor : SyncBufferManagerListener, DaemonCodeAnalyzer.DaemonListener, ProjectManagerListener {
    private val log = Logger.getInstance(InsightProcessor::class.java)
    private val appBus =
            ApplicationManager.getApplication().messageBus.connect(ComradeNeovimPlugin.instance)
    private var isStarted: Boolean = false
    private val jobsMap = IdentityHashMap<SyncBuffer, Deferred<Unit>>()
    private val projectBusMap = IdentityHashMap<Project, MessageBusConnection>()
    private val insightMap = IdentityHashMap<SyncBuffer, Map<Int, InsightItem>>()

    fun start() {
        if (!isStarted) {
            appBus.subscribe(SyncBufferManager.TOPIC, this)
            appBus.subscribe(ProjectManager.TOPIC, this)
            isStarted = true
        }
    }

    /**
     * Process the insight information immediately.
     */
    private fun process(buffer: SyncBuffer) {
        val nvimInstance = buffer.nvimInstance
        ApplicationManager.getApplication().invokeLater {
            if (buffer.isReleased()) return@invokeLater

            val list = mutableListOf<HighlightInfo>()
            DaemonCodeAnalyzerEx.processHighlights(buffer.document,
                    buffer.project,
                    HighlightSeverity.GENERIC_SERVER_ERROR_OR_WARNING,
                    0,
                    buffer.document.getLineEndOffset(buffer.document.lineCount - 1)) {
                info ->
                list.add(info)
                true
            }

            val itemMap = list.asSequence()
                    .map {
                        val item = InsightItem(buffer, it)
                        item.id to item
                    }.toMap()

            val insights = createInsights(itemMap)
            insightMap[buffer] = itemMap

            ComradeScope.launch {
                nvimInstance.client.api.callFunction(FUN_SET_INSIGHT, listOf(buffer.id, insights))
            }
        }
    }

    private fun createInsights(itemMap: Map<Int, InsightItem>) : Map<Int, List<Map<String, Any>>>
    {
        val ret = mutableMapOf<Int, List<Map<String, Any>>>()
        itemMap.values.forEach {item ->
            val insight = item.toMap()
            val startLine = insight["s_line"] as Int
            if (!ret.containsKey(startLine)) {
                ret[startLine] = mutableListOf()
            }
            val insightList = ret[startLine] as MutableList
            insightList.add(insight)
        }
        ret.values.forEach {
            (it as MutableList).sortByDescending { insightMap -> insightMap["severity"] as Int }
        }
        return ret
    }

    private fun createJobAsync(syncBuffer: SyncBuffer) : Deferred<Unit> {
        return ComradeScope.async {
            delay(PROCESS_INTERVAL)
            process(syncBuffer)
        }
    }

    @RequestHandler(MSG_COMRADE_QUICK_FIX)
    fun comradeQuickFix(params: ComradeQuickFixParams) : Int {
        invokeOnMainAndWait( {
            val buf = insightMap.keys.firstOrNull {
                it.id == params.bufId
            } ?: return@invokeOnMainAndWait

            val insight = insightMap[buf]?.get(params.insightId) ?: return@invokeOnMainAndWait
            if (insight.actionList.size <= params.fixIndex) return@invokeOnMainAndWait
            val fix = insight.actionList[params.fixIndex]

            WriteCommandAction.runWriteCommandAction(buf.project) {
                fix.action.invoke(buf.project, buf.editor, buf.psiFile)
            }
        }, {
            log.info("comradeQuickFix failed.", it)
        })
        return 1
    }

    override fun bufferCreated(syncBuffer: SyncBuffer) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        val project = syncBuffer.project
        if (!projectBusMap.contains(project)) {
            val bus = project.messageBus.connect()
            bus.subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, this)
            projectBusMap[project] = bus
        }

        jobsMap[syncBuffer]?.cancel()
        jobsMap[syncBuffer] = createJobAsync(syncBuffer)
    }

    override fun bufferReleased(syncBuffer: SyncBuffer) {
        ApplicationManager.getApplication().assertIsDispatchThread()
        jobsMap.remove(syncBuffer)?.cancel()
        insightMap.remove(syncBuffer)
        val allBuffers = SyncBufferManager.listAllBuffers()
        val toRemove = projectBusMap.filterKeys { project ->
            val buf = allBuffers.firstOrNull { it.project === project }
            buf == null
        }

        toRemove.forEach {
            projectBusMap.remove(it.key)?.disconnect()
        }
    }

    override fun daemonFinished(fileEditors: MutableCollection<FileEditor>) {
        fileEditors.forEach { editor ->
            val syncBuf = jobsMap.keys.firstOrNull { it.psiFile.virtualFile === editor.file }
            if (syncBuf != null) {
                jobsMap[syncBuf]?.cancel()
                jobsMap[syncBuf] = createJobAsync(syncBuf)
            }
        }
    }

    override fun projectClosed(project: Project) {
        projectBusMap.remove(project)?.disconnect()
    }
}