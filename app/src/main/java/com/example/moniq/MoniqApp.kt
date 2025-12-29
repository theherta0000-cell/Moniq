package com.example.moniq

import android.app.Application
import com.example.moniq.util.ServerManager

class MoniqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize ServerManager with persistent storage
        ServerManager.initialize(this)
        
        // attempt to restore session from secure storage on process start
        try {
            SessionStore.load(this)
        } catch (_: Throwable) {}
    }
}