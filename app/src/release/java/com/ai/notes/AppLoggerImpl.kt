package com.ai.notes

class ReleaseAppLogger : AppLogger {
    override fun init() {
        // no-op: no logging in release builds
    }
}

fun provideAppLogger(): AppLogger = ReleaseAppLogger()
