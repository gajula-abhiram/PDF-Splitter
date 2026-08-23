package com.abhiram.photoq

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PhotoQApp : Application() {
    lateinit var store: PhotoStore
    lateinit var processor: PhotoProcessor
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        store = PhotoStore(this)
        processor = PhotoProcessor(this, store, scope)
        processor.resumePending()
    }
}
