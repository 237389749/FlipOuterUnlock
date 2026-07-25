package com.example.flipunlock.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Add lock/unlock + app-info menu on recents task long-press.
 *
 * When the user long-presses a task in the fliphome recents view (vertical layout),
 * a PopupWindow appears with two buttons: lock/unlock toggle and "app info".
 * Other tasks are faded out while the menu is shown. Tapping outside dismisses.
 *
 * Ported from MixFlipMod's FlipHomeHook recents menu.
 */
object RecentsMenuHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    private const val TASK_STACK_VIEW_CLASS = "com.miui.fliphome.recents.views.TaskStackView"
    private const val RECENTS_CONTAINER_CLASS = "com.miui.fliphome.recents.views.RecentsContainer"

    private val recentsTaskMenus = WeakHashMap<View, RecentsTaskMenuHandle>()

    private data class RecentsTaskMenuHandle(
        val popup: PopupWindow,
        val dismiss: () -> Unit,
    )

    private data class RecentsMenuState(
        val taskStackView: Any?,
        val taskViews: List<TaskViewState>,
    )

    private data class TaskViewState(
        val view: View,
        val visibility: Int,
        val alpha: Float,
        val translationZ: Float,
        val importantForAccessibility: Int,
    )

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.uiRecentsMenu) {
            log("RecentsMenuHook: DISABLED by persist.flipunlock.ui.recentsmenu")
            return
        }
        log("RecentsMenuHook: loading for ${param.packageName}")
        safeHook("RecentsMenu") {
            hookTaskViewLongPress(param)
            hookTaskViewDetach(param)
            hookTaskStackVisibility(param)
            hookRecentsBackPress(param)
        }
    }

    // ── TaskView long-press entry point ──────────────────────────────────

    private fun hookTaskViewLongPress(param: PackageReadyParam) {
        val taskViewClass = param.classLoader.loadClass(
            "com.miui.fliphome.recents.views.TaskView")
        val taskClass = param.classLoader.loadClass(
            "com.android.systemui.shared.recents.model.Task")
        val utilsClass = runCatching {
            param.classLoader.loadClass("com.miui.fliphome.RecentsAndFSGestureUtils")
        }.getOrNull()
        val lockOrUnlockApp: Method? = runCatching {
            utilsClass?.method(
                "lockOrUnlockApp", taskClass, Boolean::class.javaPrimitiveType!!, Runnable::class.java)
        }.getOrNull()

        hook(taskViewClass.method("onFinishInflate"), after { chain, result ->
            val taskView = chain.thisObject as? View ?: return@after result
            taskView.isLongClickable = true
            taskView.setOnLongClickListener {
                val task = runCatching { taskView.callMethod("getTask") }
                    .onFailure { log("RecentsMenu: getTask failed", it) }
                    .getOrNull() ?: return@setOnLongClickListener false
                runCatching {
                    val packageName = taskPackageName(task) ?: return@runCatching
                    showRecentsTaskMenu(taskView, task, packageName, lockOrUnlockApp)
                }.onFailure { log("RecentsMenu: long press handler failed", it) }
                true
            }
            result
        })
    }

    // ── Dismiss on detach / visibility change / back press ───────────────

    private fun hookTaskViewDetach(param: PackageReadyParam) {
        val taskViewClass = param.classLoader.loadClass(
            "com.miui.fliphome.recents.views.TaskView")
        hook(taskViewClass.method("onDetachedFromWindow"), after { chain, result ->
            (chain.thisObject as? View)?.let { dismissRecentsTaskMenu(it) }
            result
        })
    }

    private fun hookTaskStackVisibility(param: PackageReadyParam) {
        val taskStackViewClass = param.classLoader.loadClass(TASK_STACK_VIEW_CLASS)
        hook(taskStackViewClass.method("setVisibility", Int::class.javaPrimitiveType!!),
            after { chain, result ->
                if (chain.args.firstOrNull() != View.VISIBLE) dismissAllRecentsTaskMenus()
                result
            })
    }

    private fun hookRecentsBackPress(param: PackageReadyParam) {
        val recentsContainerClass = param.classLoader.loadClass(RECENTS_CONTAINER_CLASS)
        hook(recentsContainerClass.method("onBackPressed")) { chain ->
            if (hasRecentsTaskMenu()) dismissAllRecentsTaskMenus()
            else chain.proceed()
        }
    }

    // ── Menu show / dismiss ──────────────────────────────────────────────

    private fun showRecentsTaskMenu(
        taskView: View, task: Any, packageName: String, lockOrUnlockApp: Method?,
    ) {
        val context = taskView.context
        dismissRecentsTaskMenu(taskView)
        val menuState = enterRecentsMenuMode(taskView)
        val cleanupAutoDismiss = installRecentsTaskMenuAutoDismiss(taskView)

        lateinit var popup: PopupWindow
        var isDismissing = false
        val dismissWithAnimation = {
            if (!isDismissing) {
                isDismissing = true
                animateMenuContainerDismiss(popup.contentView) { popup.dismiss() }
            }
        }

        val contentView = createRecentsTaskMenuView(
            context, task, packageName, taskView, lockOrUnlockApp, dismissWithAnimation)

        popup = PopupWindow(context).apply {
            this.contentView = contentView
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
            isFocusable = false
            isOutsideTouchable = true
            elevation = 12.dp(context).toFloat()
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            setOnDismissListener {
                cleanupAutoDismiss()
                restoreRecentsMenuMode(menuState)
                if (recentsTaskMenus[taskView]?.popup === popup) {
                    recentsTaskMenus.remove(taskView)
                }
            }
        }
        recentsTaskMenus[taskView] = RecentsTaskMenuHandle(popup, dismissWithAnimation)
        popup.showAtLocation(taskView.rootView, Gravity.TOP or Gravity.START, 0, 0)
        taskView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun dismissRecentsTaskMenu(taskView: View) {
        recentsTaskMenus[taskView]?.dismiss()
    }

    private fun dismissAllRecentsTaskMenus() {
        recentsTaskMenus.keys.toList().forEach { dismissRecentsTaskMenu(it) }
    }

    private fun hasRecentsTaskMenu(): Boolean {
        return recentsTaskMenus.values.any { it.popup.isShowing }
    }

    // ── Auto-dismiss on view detach ──────────────────────────────────────

    private fun installRecentsTaskMenuAutoDismiss(taskView: View): () -> Unit {
        val rootView = taskView.rootView
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                dismissRecentsTaskMenu(taskView)
            }
        }
        val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (!taskView.isAttachedToWindow || !taskView.isShown || !rootView.isShown) {
                dismissRecentsTaskMenu(taskView)
            }
        }
        taskView.addOnAttachStateChangeListener(attachListener)
        rootView.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        return {
            taskView.removeOnAttachStateChangeListener(attachListener)
            rootView.viewTreeObserver.takeIf { it.isAlive }
                ?.removeOnGlobalLayoutListener(globalLayoutListener)
        }
    }

    // ── Menu view creation ───────────────────────────────────────────────

    private fun createRecentsTaskMenuView(
        context: Context, task: Any, packageName: String, taskView: View,
        lockOrUnlockApp: Method?, dismissWithAnimation: () -> Unit,
    ): View {
        val isLocked = taskIsLocked(task)
        val itemSize = 56.dp(context)
        val minMargin = 8.dp(context)
        val anchor = createMenuAnchor(context, taskView, itemSize, minMargin)
        val positions = calculateMenuPositions(anchor, itemSize, minMargin)

        return FrameLayout(context).apply {
            isClickable = true
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismissWithAnimation() }

            // Lock/unlock button
            addView(createMenuItem(
                context = context,
                iconRes = if (isLocked) android.R.drawable.ic_menu_revert
                          else android.R.drawable.ic_lock_idle_lock,
                contentDescription = if (isLocked) "解锁" else "锁定",
                itemSize = itemSize,
                pivotX = positions[0].pivotX,
                pivotY = positions[0].pivotY,
                onClick = {
                    if (lockOrUnlockApp != null) {
                        toggleTaskLock(taskView, task, lockOrUnlockApp, !isLocked)
                    }
                    dismissWithAnimation()
                },
            ), FrameLayout.LayoutParams(itemSize, itemSize).apply {
                leftMargin = positions[0].x; topMargin = positions[0].y
            })

            // App info button
            addView(createMenuItem(
                context = context,
                iconRes = android.R.drawable.ic_menu_info_details,
                contentDescription = "应用信息",
                itemSize = itemSize,
                pivotX = positions[1].pivotX,
                pivotY = positions[1].pivotY,
                onClick = {
                    openApplicationInfo(context, packageName)
                    dismissRecentsToHome(taskView)
                    dismissWithAnimation()
                },
            ), FrameLayout.LayoutParams(itemSize, itemSize).apply {
                leftMargin = positions[1].x; topMargin = positions[1].y
            })
        }
    }

    // ── Positioning ──────────────────────────────────────────────────────

    private data class MenuItemPosition(val x: Int, val y: Int, val pivotX: Int, val pivotY: Int)

    private data class MenuAnchor(
        val taskLeft: Int, val taskRight: Int, val taskTop: Int, val taskBottom: Int,
        val taskCenterY: Int, val screenWidth: Int, val screenHeight: Int,
        val itemSize: Int, val showAtRight: Boolean,
    )

    private fun createMenuAnchor(context: Context, taskView: View, itemSize: Int, minMargin: Int): MenuAnchor {
        val rootView = taskView.rootView
        val rootGroup = rootView as? ViewGroup
        val taskBounds = Rect(0, 0, taskView.width, taskView.height)
        rootGroup?.offsetDescendantRectToMyCoords(taskView, taskBounds)
        val screenWidth = rootView.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val screenHeight = rootView.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val taskCenterX = taskBounds.left + taskView.width / 2
        return MenuAnchor(
            taskLeft = taskBounds.left, taskRight = taskBounds.right,
            taskTop = taskBounds.top, taskBottom = taskBounds.bottom,
            taskCenterY = taskBounds.top + taskView.height / 2,
            screenWidth = screenWidth, screenHeight = screenHeight,
            itemSize = itemSize,
            showAtRight = taskCenterX <= screenWidth / 2,
        )
    }

    private fun calculateMenuPositions(anchor: MenuAnchor, itemSize: Int, minMargin: Int): List<MenuItemPosition> {
        val centerBaseY = (anchor.taskCenterY + anchor.itemSize * 0.14f).toInt()
        val centerFirstY = (centerBaseY - anchor.itemSize * 1.2f).toInt()
        val rawPositions = when {
            centerFirstY < minMargin -> {
                val y1 = (anchor.taskBottom - anchor.itemSize * 0.3f).toInt()
                val y2 = (y1 + anchor.itemSize * 0.8f).toInt()
                if (anchor.showAtRight) {
                    listOf(
                        posNearRight(anchor, y1, anchor.taskBottom - y1 - anchor.itemSize * 2),
                        posNearRight(anchor, y2, anchor.taskBottom - y2 - anchor.itemSize * 2))
                } else {
                    listOf(
                        posNearLeft(anchor, y1, anchor.taskBottom - y1 - anchor.itemSize * 2),
                        posNearLeft(anchor, y2, anchor.taskBottom - y2 - anchor.itemSize * 2))
                }
            }
            centerBaseY + anchor.itemSize > anchor.screenHeight - minMargin -> {
                val y2 = (anchor.taskTop - anchor.itemSize * 0.7f).toInt()
                val y1 = (y2 - anchor.itemSize * 0.8f).toInt()
                if (anchor.showAtRight) {
                    listOf(
                        posNearRight(anchor, y1, anchor.taskTop - y1 + anchor.itemSize * 2),
                        posNearRight(anchor, y2, anchor.taskTop - y2 + anchor.itemSize * 2))
                } else {
                    listOf(
                        posNearLeft(anchor, y1, anchor.taskTop - y1 + anchor.itemSize * 2),
                        posNearLeft(anchor, y2, anchor.taskTop - y2 + anchor.itemSize * 2))
                }
            }
            else -> {
                val x = if (anchor.showAtRight)
                    (anchor.taskRight + anchor.itemSize * 0.8f).toInt()
                else
                    (anchor.taskLeft - anchor.itemSize * 1.8f).toInt()
                listOf(
                    posCentered(anchor, x, centerFirstY),
                    posCentered(anchor, x, centerBaseY))
            }
        }
        val maxX = (anchor.screenWidth - anchor.itemSize - minMargin).coerceAtLeast(minMargin)
        val maxY = (anchor.screenHeight - anchor.itemSize - minMargin).coerceAtLeast(minMargin)
        return rawPositions.map { pos ->
            pos.copy(x = pos.x.coerceIn(minMargin, maxX), y = pos.y.coerceIn(minMargin, maxY))
        }
    }

    private fun posNearRight(a: MenuAnchor, y: Int, pivotY: Int) = MenuItemPosition(
        x = (a.taskRight + a.itemSize * 0.5f).toInt(), y = y,
        pivotX = a.taskRight - a.itemSize * 2 - (a.taskRight + a.itemSize * 0.5f).toInt(),
        pivotY = pivotY)
    private fun posNearLeft(a: MenuAnchor, y: Int, pivotY: Int) = MenuItemPosition(
        x = (a.taskLeft - a.itemSize * 1.5f).toInt(), y = y,
        pivotX = a.taskLeft + a.itemSize * 2 - (a.taskLeft - a.itemSize * 1.5f).toInt(),
        pivotY = pivotY)
    private fun posCentered(a: MenuAnchor, x: Int, y: Int) = MenuItemPosition(
        x = x, y = y,
        pivotX = if (a.showAtRight) a.taskRight - a.itemSize - x else a.taskLeft + a.itemSize - x,
        pivotY = a.taskCenterY - y)

    // ── Menu enter / restore mode (fade other tasks) ─────────────────────

    private fun enterRecentsMenuMode(taskView: View): RecentsMenuState {
        val taskStackView = taskView.findTaskStackView()
        val taskViews = taskStackView?.taskViews().orEmpty().map { view ->
            TaskViewState(view, view.visibility, view.alpha, view.translationZ, view.importantForAccessibility)
        }
        runCatching { taskStackView?.callMethod("setIsShowingMenu", true) }
        taskViews.forEach { state ->
            state.view.animate().cancel()
            if (state.view === taskView) {
                state.view.translationZ = 10f
            } else {
                state.view.alpha = 0f
                state.view.visibility = View.INVISIBLE
                state.view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
        return RecentsMenuState(taskStackView, taskViews)
    }

    private fun restoreRecentsMenuMode(state: RecentsMenuState) {
        runCatching { state.taskStackView?.callMethod("setIsShowingMenu", false) }
        state.taskViews.forEach { vs ->
            vs.view.animate().cancel()
            vs.view.visibility = vs.visibility
            vs.view.alpha = vs.alpha
            vs.view.translationZ = vs.translationZ
            vs.view.importantForAccessibility = vs.importantForAccessibility
        }
    }

    // ── Animations ───────────────────────────────────────────────────────

    private fun animateMenuContainerDismiss(contentView: View?, endAction: () -> Unit) {
        val container = contentView as? ViewGroup ?: return endAction()
        val menuItems = (0 until container.childCount).map { container.getChildAt(it) }
            .filter { it is ImageView }
        if (menuItems.isEmpty()) { endAction(); return }
        var remaining = menuItems.size
        menuItems.forEach { item ->
            item.animate().cancel()
            item.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f)
                .setDuration(125L).withEndAction {
                    remaining -= 1
                    if (remaining == 0) endAction()
                }.start()
        }
    }

    private fun createMenuItem(
        context: Context, iconRes: Int, contentDescription: String,
        itemSize: Int, pivotX: Int, pivotY: Int, onClick: () -> Unit,
    ): View {
        return ImageView(context).apply {
            isClickable = true; isFocusable = true
            this.contentDescription = contentDescription
            setPadding(14.dp(context), 14.dp(context), 14.dp(context), 14.dp(context))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = itemSize / 2f
                setColor(Color.argb(235, 245, 245, 245))
            }
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { onClick() }
            this.pivotX = pivotX.toFloat(); this.pivotY = pivotY.toFloat()
            alpha = 0f; scaleX = 0.6f; scaleY = 0.6f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start()
        }
    }

    // ── App info ─────────────────────────────────────────────────────────

    private fun openApplicationInfo(context: Context, packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    // ── Task lock/unlock ─────────────────────────────────────────────────

    private fun toggleTaskLock(taskView: View, task: Any, lockOrUnlockApp: Method, toLock: Boolean) {
        runCatching {
            lockOrUnlockApp.invoke(null, task, toLock, Runnable {
                runCatching {
                    taskView.callMethod("getHeaderView")
                        ?.callMethod("showOrHideLockImageView", toLock)
                    taskView.performHapticFeedback(1)
                }.onFailure { log("RecentsMenu: lock callback failed", it) }
            })
        }.onFailure { log("RecentsMenu: toggleTaskLock failed", it) }
    }

    // ── Utility extensions ───────────────────────────────────────────────

    private fun dismissRecentsToHome(taskView: View) {
        runCatching {
            taskView.findRecentsContainer()?.callMethod("dismissRecentsToHome", true)
        }
    }

    private fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun taskPackageName(task: Any): String? {
        return listOf("key", "cti1Key", "cti2Key").firstNotNullOfOrNull { fieldName ->
            val key = runCatching { task.getField(fieldName) }.getOrNull()
            (runCatching { key?.callMethod("getComponent") }.getOrNull() as? ComponentName)?.packageName
        }
    }

    private fun taskIsLocked(task: Any): Boolean = task.getField("isLocked") as? Boolean ?: false

    private fun View.findTaskStackView(): Any? =
        findParentByClassName(TASK_STACK_VIEW_CLASS)

    private fun View.findRecentsContainer(): Any? =
        findParentByClassName(RECENTS_CONTAINER_CLASS)

    private fun View.findParentByClassName(className: String): Any? {
        var currentParent = this.parent
        while (currentParent != null) {
            if (currentParent.javaClass.name == className) return currentParent
            currentParent = currentParent.parent
        }
        return null
    }

    private fun Any.taskViews(): List<View> {
        return (runCatching { callMethod("getTaskViews") }.getOrNull() as? Iterable<*>)
            ?.filterIsInstance<View>().orEmpty()
    }
}
