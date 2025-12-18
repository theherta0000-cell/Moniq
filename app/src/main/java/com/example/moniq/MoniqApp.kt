package com.example.moniq

import android.app.Application

class MoniqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // attempt to restore session from secure storage on process start
        try {
            SessionStore.load(this)
        } catch (_: Throwable) {}
    }
}
