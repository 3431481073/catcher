package com.example.clip.service;

import android.view.View;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.lifecycle.ViewTreeViewModelStoreOwner;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.savedstate.SavedStateRegistryOwner;
import androidx.savedstate.ViewTreeSavedStateRegistryOwner;

/**
 * 给 overlay 的 ComposeView 手动注入三个 ViewTree owner。
 * 用 Java 写是因为这三个类在 Kotlin 侧因 KMP -android 变体元数据可见性问题 import 不到
 * （类其实在 classpath 上、字节码是 public static），javac 只看字节码、不读 Kotlin 元数据，能正常调用。
 */
public final class OverlayViewTreeHelper {

    private OverlayViewTreeHelper() {}

    public static void setOwners(
            View view,
            LifecycleOwner lifecycleOwner,
            ViewModelStoreOwner viewModelStoreOwner,
            SavedStateRegistryOwner savedStateRegistryOwner
    ) {
        ViewTreeLifecycleOwner.set(view, lifecycleOwner);
        ViewTreeViewModelStoreOwner.set(view, viewModelStoreOwner);
        ViewTreeSavedStateRegistryOwner.set(view, savedStateRegistryOwner);
    }
}
