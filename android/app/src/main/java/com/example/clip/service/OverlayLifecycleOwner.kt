package com.example.clip.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * overlay 窗口的 Compose 生命周期宿主。
 * ComposeView 挂到 WindowManager 时不在 Activity 的 view tree 里，
 * 必须手动提供 LifecycleOwner / ViewModelStoreOwner / SavedStateRegistryOwner，
 * 否则 setContent 会抛 "ViewTreeLifecycleOwner not found"。
 */
class OverlayLifecycleOwner : SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    /** 必须先于任何生命周期事件调用（此时 lifecycle 必须还是 INITIALIZED）。 */
    fun performAttach() {
        // 顺序关键：performRestore 内部会先 performAttach 再 performRestore（javap 确认）。
        // 必须先 restore，否则 Recreator 在 ON_CREATE 时 consumeRestoredStateForKey 抛异常。
        controller.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun performResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun performDestroy() {
        // lifecycle 推到 DESTROYED 时，Recreator 等 observer 会自动移除，无需 performDetach
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}
