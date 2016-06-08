package com.github.shiraji.breakpointsmanager.view

import com.github.shiraji.breakpointsmanager.ext.convertToEntity
import com.github.shiraji.breakpointsmanager.model.BreakpointsEntity
import com.github.shiraji.breakpointsmanager.model.BreakpointsManagerConfig
import com.github.shiraji.breakpointsmanager.model.BreakpointsManagerConfigForWorkspace
import com.github.shiraji.breakpointsmanager.model.BreakpointsNodeEntity
import com.intellij.ide.util.treeView.NodeRenderer
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.ui.*
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.impl.XDebuggerManagerImpl
import com.intellij.xdebugger.impl.XSourcePositionImpl
import com.intellij.xdebugger.impl.breakpoints.XBreakpointUtil
import java.awt.Component
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.ToolTipManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.MutableTreeNode

class BreakpointsExplorer(val project: Project) : SimpleToolWindowPanel(false, true), DataProvider, Disposable {

    val myTree: Tree
    val myModel: DefaultTreeModel

    init {
        myModel = DefaultTreeModel(DefaultMutableTreeNode("Breakpoint Manager"))
        insertNodeFromConfig(BreakpointsManagerConfig.getInstance(project))
        insertNodeFromConfig(BreakpointsManagerConfigForWorkspace.getInstance(project))

        myTree = Tree(myModel).apply {
            isRootVisible = false
            showsRootHandles = true
            dragEnabled = false
            cellRenderer = NodeRenderer()

            addMouseListener(object : PopupHandler() {
                override fun invokePopup(component: Component, x: Int, y: Int) {
                    val actionGroup = DefaultActionGroup()
                    actionGroup.add(ApplyAction())
                    actionGroup.add(ShareAction())
                    actionGroup.add(RemoveAction(IconUtil.getEmptyIcon(false)))
                    ActionManager.getInstance().createActionPopupMenu("BreakpointsExplorerPopup", actionGroup).component.show(component, x, y)
                }
            })
        }

        TreeUtil.installActions(myTree)
        TreeSpeedSearch(myTree)
        setContent(ScrollPaneFactory.createScrollPane(myTree))
        ToolTipManager.sharedInstance().registerComponent(myTree)

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent?): Boolean {
                event ?: return false
                val path = myTree.getClosestPathForLocation(event.x, event.y)
                val selectedNode = path.lastPathComponent
                if (selectedNode !is CheckedTreeNode) return true
                val breakpointsEntity = selectedNode.userObject as BreakpointsNodeEntity
                val virtualFile = VirtualFileManager.getInstance().findFileByUrl(breakpointsEntity.entity.fileUrl)!!
                XSourcePositionImpl.create(virtualFile, breakpointsEntity.entity.line)!!.createNavigatable(project).navigate(true)
                return true
            }
        }.installOn(myTree)

        setToolbar(createToolbarPanel())
    }

    private fun insertNodeFromConfig(config: BreakpointsManagerConfig) {
        config.state?.entities!!.forEach {
            val node = DefaultMutableTreeNode(it.key)
            myModel.insertNodeInto(node, myModel.root as MutableTreeNode?, myModel.getChildCount(myModel.root))
            it.value.sortedBy { it.fileUrl.substringAfterLast(File.separator) }
                    .sortedBy { it.line }
                    .forEachIndexed { index, breakpointsEntity ->
                        val checkedTreeNode = CheckedTreeNode(BreakpointsNodeEntity(breakpointsEntity)).apply {
                            isChecked = breakpointsEntity.isEnabled
                        }
                        myModel.insertNodeInto(checkedTreeNode, node, index)
                    }
        }
    }

    private fun createToolbarPanel(): JPanel {
        val group = DefaultActionGroup()
        group.add(AddAction())
        group.add(RemoveAction(IconUtil.getRemoveIcon()))
        val actionToolBar = ActionManager.getInstance().createActionToolbar(ActionPlaces.ANT_EXPLORER_TOOLBAR, group, false)
        return JBUI.Panels.simplePanel(actionToolBar.component)
    }

    inner class AddAction() : AnAction("Save all breakpoints", "Save all breakpoints with unique name. Later, the developers can restore this breakpoints set", IconUtil.getAddIcon()) {
        override fun actionPerformed(e: AnActionEvent) {
            val dialog = BreakpointsSetNameDialog(project, getAreadlyExistsNames())
            if (dialog.showAndGet()) {
                val name = dialog.nameTextField.text
                val list = mutableListOf<BreakpointsEntity>()
                (XDebuggerManager.getInstance(project) as XDebuggerManagerImpl).breakpointManager.allBreakpoints.forEachIndexed { i, it ->
                    if (it !is XLineBreakpoint<*>) return@forEachIndexed

                    @Suppress("UNCHECKED_CAST")
                    val type = (it as XLineBreakpoint<XBreakpointProperties<Any>>).type
                    BreakpointsEntity(
                            fileUrl = it.fileUrl,
                            typeId = type.id,
                            line = it.line,
                            condition = it.conditionExpression?.convertToEntity(),
                            isTemporary = it.isTemporary,
                            isEnabled = it.isEnabled,
                            isLogMessage = it.isLogMessage,
                            logExpression = it.logExpressionObject?.convertToEntity()
                    ).let { list.add(it) }
                }

                val entities = BreakpointsManagerConfigForWorkspace.getInstance(project).state?.entities ?: return
                entities.put(name, list)
                val node = DefaultMutableTreeNode(name)
                myModel.apply {
                    insertNodeInto(node, root as MutableTreeNode?, myModel.getChildCount(myModel.root))
                    entities[name]?.sortedBy { it.fileUrl.substringAfterLast(File.separator) }?.
                            sortedBy { it.line }?.
                            forEachIndexed { index, breakpointsEntity -> insertNodeInto(CheckedTreeNode(BreakpointsNodeEntity(breakpointsEntity)), node, index) }
                    reload()
                }
            }
        }

        private fun getAreadlyExistsNames(): Array<String> {
            val configForW = BreakpointsManagerConfigForWorkspace.getInstance(project)
            val config = BreakpointsManagerConfig.getInstance(project)

            val names = config.state?.entities?.keys
            val namesForW = configForW.state?.entities?.keys

            val alreadyExistsNames = mutableListOf<String>()
            if (names != null) alreadyExistsNames.addAll(names)
            if (namesForW != null) alreadyExistsNames.addAll(namesForW)
            return alreadyExistsNames.toTypedArray()
        }
    }

    inner class RemoveAction(icon: Icon) : AnAction("Remove the selected breakpoint[s] from settings", "Remove the selected breakpoint[s] from settings. This action does not remove breakpoints that are currently on the editor", icon) {
        override fun actionPerformed(e: AnActionEvent) {
            val config = BreakpointsManagerConfig.getInstance(project)
            val configForW = BreakpointsManagerConfigForWorkspace.getInstance(project)
            myTree.selectionPaths?.forEach {
                val selectedNode = it.lastPathComponent as DefaultMutableTreeNode
                val isShared = config.state?.entities?.contains(selectedNode.userObject) ?: false
                if (isShared) {
                    config.state?.entities?.remove(selectedNode.userObject)
                } else {
                    configForW.state?.entities?.remove(selectedNode.userObject)
                }

                myModel.apply {
                    removeNodeFromParent(selectedNode)
                    reload(selectedNode.parent)
                }
            }
        }

        override fun update(e: AnActionEvent?) {
            e ?: return
            super.update(e)
            e.presentation.isEnabled = myTree.selectionPath != null
        }
    }

    inner class ApplyAction() : AnAction("Apply the selected breakpoint[s]", "Apply the selected breakpoint[s]", IconUtil.getEmptyIcon(false)) {
        override fun actionPerformed(e: AnActionEvent?) {
            ApplicationManager.getApplication().runWriteAction {
                myTree.selectionPaths.forEach {
                    val selectedPath = it ?: return@runWriteAction
                    val selectedNode = selectedPath.lastPathComponent as DefaultMutableTreeNode
                    val userObject = selectedNode.userObject
                    if (userObject is BreakpointsNodeEntity) {
                        addBreakpoint(userObject)
                    } else if (userObject is String) {
                        val children = selectedNode.children()
                        while (children.hasMoreElements()) {
                            addBreakpoint((children.nextElement() as DefaultMutableTreeNode).userObject as BreakpointsNodeEntity)
                        }
                    }
                }
            }
        }

        fun addBreakpoint(breakpointsNodeEntity: BreakpointsNodeEntity) {
            val breakpointsEntity = breakpointsNodeEntity.entity
            val manager = XDebuggerManager.getInstance(project) as XDebuggerManagerImpl
            val find = manager.breakpointManager.allBreakpoints.find {
                it is XLineBreakpoint<*> && it.fileUrl == breakpointsEntity.fileUrl && it.line == breakpointsEntity.line
            }
            if (find != null) manager.breakpointManager.removeBreakpoint(find)
            @Suppress("UNCHECKED_CAST")
            val type = XBreakpointUtil.findType(breakpointsEntity.typeId) as XLineBreakpointType<XBreakpointProperties<Any>>?
            manager.breakpointManager.addLineBreakpoint(
                    type,
                    breakpointsEntity.fileUrl,
                    breakpointsEntity.line,
                    type?.createBreakpointProperties(VirtualFileManager.getInstance().findFileByUrl(breakpointsEntity.fileUrl)!!, breakpointsEntity.line)).let {
                it.conditionExpression = breakpointsEntity.condition?.toXExpression()
                it.isTemporary = breakpointsEntity.isTemporary
                it.isEnabled = breakpointsEntity.isEnabled
                it.isLogMessage = breakpointsEntity.isLogMessage
                it.logExpressionObject = breakpointsEntity.logExpression?.toXExpression()
            }
        }
    }

    inner class ShareAction() : AnAction("Share selected breakpoint[s] to project", "Share selected breakpoint[s] to project", IconUtil.getEmptyIcon(false)) {
        override fun actionPerformed(e: AnActionEvent?) {
            val config = BreakpointsManagerConfig.getInstance(project)
            val configForW = BreakpointsManagerConfigForWorkspace.getInstance(project)
            val selectedPath = myTree.selectionPath ?: return
            val selectedNode = selectedPath.lastPathComponent as DefaultMutableTreeNode
            val nodeName = selectedNode.userObject as? String ?: return

            val hasNode = configForW.state?.entities?.contains(nodeName) ?: false
            if (hasNode) {
                val entities = configForW.state!!.entities
                val node = entities[nodeName]
                config.state?.entities?.put(nodeName, node!!)
                entities.remove(nodeName)
            }
        }

        override fun update(e: AnActionEvent?) {
            super.update(e)
            e ?: return

            val selectedPath = myTree.selectionPath

            if (selectedPath == null) {
                e.presentation.isEnabledAndVisible = false
            } else {
                e.presentation.isVisible = true
                e.presentation.isEnabled = selectedPath.lastPathComponent !is CheckedTreeNode
                        && BreakpointsManagerConfigForWorkspace.getInstance(project).state?.entities?.containsKey((selectedPath.lastPathComponent as DefaultMutableTreeNode).userObject) ?: false
            }
        }
    }

    override fun dispose() {
        myTree.let {
            ToolTipManager.sharedInstance().unregisterComponent(it);
            registeredKeyStrokes.forEach { unregisterKeyboardAction(it) }
        }
    }
}