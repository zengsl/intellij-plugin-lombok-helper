package com.zzz.lombokhelper

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionHost
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.codeInsight.daemon.impl.JavaCodeVisionProviderBase
import com.intellij.codeInsight.hints.InlayHintsUtils
import com.intellij.codeInsight.hints.settings.language.isInlaySettingsEditor
import com.intellij.ide.actions.OpenInRightSplitAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.impl.source.PsiClassImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.task.ProjectTaskManager
import com.intellij.tracing.Tracer
import java.awt.event.MouseEvent
import java.io.File


class LombokCodeVisionProvider : JavaCodeVisionProviderBase() {


    override val defaultAnchor: CodeVisionAnchorKind
        get() = CodeVisionAnchorKind.Top
    override val id: String
        get() = "lombok origin content"
    override val name: String
        get() = "Lombok origin content"
    override val relativeOrderings: List<CodeVisionRelativeOrdering>
        get() = listOf(CodeVisionRelativeOrdering.CodeVisionRelativeOrderingFirst)

    override fun computeLenses(editor: Editor, psiFile: PsiFile): List<Pair<TextRange, CodeVisionEntry>> {
        // we want to let this provider work only in tests dedicated for code vision, otherwise they harm performance
        if (ApplicationManager.getApplication().isUnitTestMode && !CodeVisionHost.isCodeLensTest(editor)) return emptyList()


        val lenses = ArrayList<Pair<TextRange, CodeVisionEntry>>()
        val traverser = SyntaxTraverser.psiTraverser(psiFile)
        for (element in traverser) {
            val passType = psiFile.originalFile.name.endsWith(".class", true)
            if (element !is PsiMember || element !is PsiClassImpl || passType) continue
            if (!isFirstInLine(element)) continue

            val hint = "show class file"
            val handler = ClickHandler(element)
            val range = InlayHintsUtils.getTextRangeWithoutLeadingCommentsAndWhitespaces(element)
            lenses.add(range to ClickableTextCodeVisionEntry(hint, id, handler))
        }
        return lenses
    }

    private class ClickHandler(
            element: PsiElement,
    ) : (MouseEvent?, Editor) -> Unit {

        private val elementPointer = SmartPointerManager.createPointer(element)
         var lastEditorWindow: EditorWindow? = null
         var lastFile: VirtualFile? = null

        override fun invoke(event: MouseEvent?, editor: Editor) {
            if (isInlaySettingsEditor(editor)) return
            val element = elementPointer.element ?: return
            val project = editor.project ?: return
            val buildSpan = Tracer.start("open class file")
            val psiFile = element.containingFile
            val currentModule = ModuleUtil.findModuleForFile(psiFile)
            val compilerOutputUrl = CompilerModuleExtension.getInstance(currentModule)?.compilerOutputPointer?.presentableUrl
            val sourceRoot = currentModule?.rootManager?.sourceRoots?.get(0)
//            val split = psiFile.virtualFile.url.split("src")
            val split = psiFile.virtualFile.url.split(sourceRoot!!.url)
            val fileType = psiFile.virtualFile.fileType.name
            // 编译文件的路径
            val buildFileUrl = compilerOutputUrl + split[split.size - 1].replace(fileType, "class", true)
            println("buildFileUrl:$buildFileUrl")
            // compile currentFile
            val resultPromise = ProjectTaskManager.getInstance(project).compile(psiFile.virtualFile)
            // 死循环
//            val taskResult = ProjectTaskManagerImpl.waitForPromise(resultPromise)
            // 超时。目前不太了解原理
            /* val taskResult = resultPromise.blockingGet(15, TimeUnit.SECONDS)
             if (taskResult == null || taskResult.isAborted || taskResult.hasErrors()) {
                 NotificationGroupManager.getInstance()
                         .getNotificationGroup("ShowClassFile")
                         .createNotification("编译失败", NotificationType.WARNING)
                         .notify(project)
                 buildSpan.complete()
                 return
             }*/
            val file = LocalFileSystem.getInstance().findFileByIoFile(File(buildFileUrl))

            if (file == null) {
                // TODO 待完善：如果没有生成class就实时调用生成
                NotificationGroupManager.getInstance()
                        .getNotificationGroup("ShowClassFile")
                        .createNotification("当前文件未编译生成class文件", NotificationType.WARNING)
                        .notify(project)
                buildSpan.complete()
                return
            }
            if (lastEditorWindow != null && lastFile != null) {
                lastEditorWindow!!.closeFile(lastFile!!)
            }
            lastFile = file
            val editorWindow = OpenInRightSplitAction.openInRightSplit(project, file, null)
            lastEditorWindow = editorWindow
            if (editorWindow != null) {
                val fileEditorManager = FileEditorManagerEx.getInstanceEx(project)
                fileEditorManager.openFileWithProviders(file, true, editorWindow)
                buildSpan.complete()
            }
        }
    }

    private fun isFirstInLine(element: PsiElement): Boolean {
        var prevLeaf = PsiTreeUtil.prevLeaf(element, true) ?: return true
        while (prevLeaf is PsiWhiteSpace) {
            if (prevLeaf.textContains('\n') || prevLeaf.textRange.startOffset == 0) {
                return true
            }
            prevLeaf = PsiTreeUtil.prevLeaf(prevLeaf, true)!!
        }
        return false
    }
}