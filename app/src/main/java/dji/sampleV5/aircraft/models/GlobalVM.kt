package dji.sampleV5.aircraft.models

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelLazy
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore

// 全域使用的 ViewModelStore
val globalViewModelStore = ViewModelStore()

/**
 * 在 Activity 中建立一個全域 ViewModel，儲存於全域的 ViewModelStore。
 */
@MainThread
inline fun <reified VM : ViewModel> ComponentActivity.globalViewModels(
    noinline factoryProducer: (() -> ViewModelProvider.Factory)? = null
): Lazy<VM> {
    val factoryPromise = factoryProducer ?: {
        defaultViewModelProviderFactory
    }
    return ViewModelLazy(VM::class, { globalViewModelStore }, factoryPromise)
}

/**
 * 在 Fragment 中建立一個全域 ViewModel，儲存於全域的 ViewModelStore。
 */
@MainThread
inline fun <reified VM : ViewModel> Fragment.globalViewModels(
    noinline factoryProducer: (() -> ViewModelProvider.Factory)? = null
): Lazy<VM> {
    val factoryPromise = factoryProducer ?: {
        defaultViewModelProviderFactory
    }
    return ViewModelLazy(VM::class, { globalViewModelStore }, factoryPromise)
}

/**
 * 在 Application 中建立一個全域 ViewModel，儲存於全域的 ViewModelStore。
 */
@MainThread
inline fun <reified VM : ViewModel> Application.globalViewModels(): Lazy<VM> {
    val factory = ViewModelProvider.AndroidViewModelFactory.getInstance(this)
    return ViewModelLazy(VM::class, { globalViewModelStore }, { factory })
}

