package com.bydmate.app.ui.settings

import kotlinx.coroutines.Job

/**
 * Serializes the diagnostic log recorder lifecycle.
 *
 * A token belongs to exactly one start attempt. Callbacks from an older attempt cannot
 * activate, stop, or publish state for a newer recorder. The STOPPING phase deliberately
 * rejects a new start until the previous process and pipe thread have been terminated.
 */
internal class LogRecordingLifecycle<T> {

    @JvmInline
    value class Token internal constructor(internal val value: Long)

    data class Resources<T>(
        val token: Token,
        val startJob: Job?,
        val session: T?,
        val autoStopJob: Job?,
    )

    private enum class Phase {
        IDLE,
        STARTING,
        ACTIVE,
        STOPPING,
        CLEARED,
    }

    private val lock = Any()
    private var phase = Phase.IDLE
    private var nextToken = 0L
    private var token: Token? = null
    private var startJob: Job? = null
    private var session: T? = null
    private var autoStopJob: Job? = null

    fun beginStart(): Token? = synchronized(lock) {
        if (phase != Phase.IDLE) return@synchronized null

        Token(++nextToken).also { newToken ->
            phase = Phase.STARTING
            token = newToken
            startJob = null
            session = null
            autoStopJob = null
        }
    }

    fun attachStartJob(expectedToken: Token, job: Job): Boolean = synchronized(lock) {
        if (phase != Phase.STARTING || token != expectedToken) return@synchronized false
        startJob = job
        true
    }

    fun isStarting(expectedToken: Token): Boolean = synchronized(lock) {
        phase == Phase.STARTING && token == expectedToken
    }

    fun activate(expectedToken: Token, activeSession: T): Boolean = synchronized(lock) {
        if (phase != Phase.STARTING || token != expectedToken) return@synchronized false
        session = activeSession
        phase = Phase.ACTIVE
        true
    }

    fun attachAutoStopJob(expectedToken: Token, job: Job): Boolean = synchronized(lock) {
        if (phase != Phase.ACTIVE || token != expectedToken) return@synchronized false
        autoStopJob = job
        true
    }

    fun completeStart(expectedToken: Token) = synchronized(lock) {
        if ((phase == Phase.STARTING || phase == Phase.ACTIVE) && token == expectedToken) {
            startJob = null
        }
    }

    fun runIfActive(expectedToken: Token, action: () -> Unit): Boolean = synchronized(lock) {
        if (phase != Phase.ACTIVE || token != expectedToken) return@synchronized false
        action()
        true
    }

    /**
     * Moves the current session to STOPPING and returns the resources owned by it.
     * [expectedToken] makes delayed auto-stop and pipe callbacks harmless.
     */
    fun requestStop(expectedToken: Token? = null): Resources<T>? = synchronized(lock) {
        if (phase != Phase.STARTING && phase != Phase.ACTIVE) return@synchronized null
        val currentToken = token ?: return@synchronized null
        if (expectedToken != null && currentToken != expectedToken) return@synchronized null

        phase = Phase.STOPPING
        Resources(currentToken, startJob, session, autoStopJob)
    }

    /**
     * Completes only the matching stop. The callback runs while lifecycle transitions are
     * serialized, so a newer start cannot race ahead of the final UI update.
     */
    fun finishStop(expectedToken: Token, action: () -> Unit = {}): Boolean = synchronized(lock) {
        if (phase != Phase.STOPPING || token != expectedToken) return@synchronized false
        clearResources()
        phase = Phase.IDLE
        action()
        true
    }

    /** Permanently closes the lifecycle and returns anything that still needs termination. */
    fun clear(): Resources<T>? = synchronized(lock) {
        if (phase == Phase.CLEARED) return@synchronized null
        val currentToken = token
        val resources = currentToken?.let { Resources(it, startJob, session, autoStopJob) }
        clearResources()
        phase = Phase.CLEARED
        resources
    }

    private fun clearResources() {
        token = null
        startJob = null
        session = null
        autoStopJob = null
    }
}
