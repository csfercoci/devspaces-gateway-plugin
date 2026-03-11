/*
 * Copyright (c) 2024-2025 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.Messages
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import com.redhat.devtools.gateway.openshift.DevWorkspace
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.Pods
import com.redhat.devtools.gateway.server.RemoteIDEServer
import com.redhat.devtools.gateway.server.RemoteIDEServerStatus
import com.redhat.devtools.gateway.util.ProgressCountdown
import com.redhat.devtools.gateway.util.isCancellationException
import io.kubernetes.client.openapi.ApiException
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

class DevSpacesConnection(
    private val devSpacesContext: DevSpacesContext,
    private val workspace: DevWorkspace = devSpacesContext.devWorkspace
) {
    companion object {
        private const val DEFAULT_REMOTE_IDE_PORT = 5990

        internal fun remoteIdePort(joinLink: String): Int {
            val parsed = URI(joinLink)
            return if (parsed.port > 0) parsed.port else DEFAULT_REMOTE_IDE_PORT
        }

        internal fun localJoinLink(joinLink: String, localPort: Int): String {
            val parsed = URI(joinLink)
            return URI(
                parsed.scheme,
                parsed.userInfo,
                parsed.host,
                localPort,
                parsed.path,
                parsed.query,
                parsed.fragment
            ).toString()
        }
    }

    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    fun connect(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onProgress: ((value: ProgressCountdown.ProgressEvent) -> Unit)? = null,
        checkCancelled: (() -> Unit)? = null
    ): ThinClientHandle = runBlocking {
        doConnect(onConnected, onDevWorkspaceStopped, onDisconnected, onProgress, checkCancelled)
    }

    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    private suspend fun doConnect(
        onConnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onDisconnected: () -> Unit,
        onProgress: ((value: ProgressCountdown.ProgressEvent) -> Unit)? = null,
        checkCancelled: (() -> Unit)? = null
    ): ThinClientHandle {
        devSpacesContext.addWorkspace(workspace)

        var remoteIdeServer: RemoteIDEServer? = null
        var forwarder: Closeable? = null
        var client: ThinClientHandle? = null

        return try {
            var remoteIdeServerStatus: RemoteIDEServerStatus = RemoteIDEServerStatus.empty()
            while (!remoteIdeServerStatus.isReady) {
                checkCancelled?.invoke()
                onProgress?.invoke(ProgressCountdown.ProgressEvent(
                    message = "Waiting for the Dev Workspace to get ready...",
                    countdownSeconds = DevWorkspaces.RUNNING_TIMEOUT))

                startAndWaitDevWorkspace(checkCancelled)

                checkCancelled?.invoke()
                onProgress?.invoke(ProgressCountdown.ProgressEvent(
                    message = "Waiting for the remote server to get ready...",
                    countdownSeconds = RemoteIDEServer.readyTimeout))

                remoteIdeServer = RemoteIDEServer(devSpacesContext, workspace)
                remoteIdeServerStatus = runCatching {
                    remoteIdeServer.apply { waitServerReady(checkCancelled) }.getStatus()
                }.getOrElse { e ->
                    if (e.isCancellationException()) throw e
                    RemoteIDEServerStatus.empty()
                }

                checkCancelled?.invoke()
                if (!remoteIdeServerStatus.isReady) {
                    when (askToRestartPod()) {
                        1 -> {
                            // User chose "Restart Pod": stop the Pod and try starting from scratch
                            stopAndWaitDevWorkspace(checkCancelled)
                            continue
                        }
                    }

                    // User chose "Cancel Connection"
                    throw CancellationException("User cancelled the operation")
                }
            }

            check(remoteIdeServer != null && remoteIdeServerStatus.isReady) { "Could not connect, remote IDE is not ready." }
            val joinLink = remoteIdeServerStatus.preferredJoinLink
                ?: throw IOException("Could not connect, remote IDE is not ready. No supported join link present.")

            checkCancelled?.invoke()
            onProgress?.invoke(ProgressCountdown.ProgressEvent(
                message = "Waiting for the IDE client to start up..."))

            val pods = Pods(devSpacesContext.client)
            val localPort = findFreePort()
            val remotePort = remoteIdePort(joinLink)
            forwarder = pods.forward(remoteIdeServer.pod, localPort, remotePort)
            pods.waitForForwardReady(localPort)

            val effectiveJoinLink = localJoinLink(joinLink, localPort)

            val lifetimeDef = Lifetime.Eternal.createNested()
            lifetimeDef.lifetime.onTermination { onClientClosed(client, onDisconnected, onDevWorkspaceStopped, remoteIdeServer, forwarder) }

            val finished = AtomicBoolean(false)

            checkCancelled?.invoke()
            client = LinkedClientManager
                .getInstance()
                .startNewClient(
                    Lifetime.Eternal,
                    URI(effectiveJoinLink),
                    "",
                    onConnected, // Triggers enableButtons() via view
                    false
                )

            client.onClientPresenceChanged.advise(client.lifetime) { finished.set(true) }
            client.clientClosed.advise(client.lifetime) {
                onClientClosed(client, onDisconnected, onDevWorkspaceStopped, remoteIdeServer, forwarder)
                finished.set(true)
            }
            client.clientFailedToOpenProject.advise(client.lifetime) {
                onClientClosed(client, onDisconnected, onDevWorkspaceStopped, remoteIdeServer, forwarder)
                finished.set(true)
            }

            val success = withTimeoutOrNull(60.seconds) {
                while (!finished.get()) {
                    checkCancelled?.invoke()
                    delay(200)
                }
                true
            } ?: false

            // Check if the thin client has opened
            check(success && client.clientPresent) {
                "Could not connect, remote IDE client is not ready."
            }

            onConnected()
            client
        } catch (e: Exception) {
            runCatching { client?.close() }
            onClientClosed(client, onDisconnected, onDevWorkspaceStopped, remoteIdeServer, forwarder)
            throw e
        }
    }

    private suspend fun askToRestartPod(): Int = suspendCancellableCoroutine { continuation ->
        ApplicationManager.getApplication().invokeLater {
            if (!continuation.isActive) {
                return@invokeLater
            }

            val result = Messages.showDialog(
                "The remote server is not responding properly.\n" +
                    "Would you like to try restarting the Pod or cancel the connection?",
                "Cannot Connect to Server",
                arrayOf("Cancel Connection", "Restart Pod and try again"),
                0,
                Messages.getWarningIcon()
            )
            continuation.resume(result)
        }
    }

    @Suppress("UnstableApiUsage")
    private fun onClientClosed(
        client: ThinClientHandle? = null,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        remoteIdeServer: RemoteIDEServer?,
        forwarder: Closeable?
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { client?.close() }
            try {
                if (true == remoteIdeServer?.waitServerTerminated()) {
                    DevWorkspaces(devSpacesContext.client)
                        .stop(
                            workspace.namespace,
                            workspace.name
                        )
                        .also { onDevWorkspaceStopped() }
                }
            } finally {
                runCatching {
                    forwarder?.close()
                }.onFailure { e ->
                    thisLogger().debug("Failed to close port forwarder", e)
                }
                devSpacesContext.removeWorkspace(workspace)
                runCatching { onDisconnected() }
            }
        }
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            socket.reuseAddress = true
            return socket.localPort
        }
    }

    @Throws(IOException::class, ApiException::class, CancellationException::class)
    private suspend fun startAndWaitDevWorkspace(checkCancelled: (() -> Unit)? = null) {
        // We really need a refreshed DevWorkspace here
        val devWorkspace = DevWorkspaces(devSpacesContext.client).get(
            workspace.namespace,
            workspace.name)

        if (!devWorkspace.started) {
            checkCancelled?.invoke()
            DevWorkspaces(devSpacesContext.client)
                .start(
                    workspace.namespace,
                    workspace.name
                )
        }

        if (!DevWorkspaces(devSpacesContext.client)
                .waitPhase(
                    workspace.namespace,
                    workspace.name,
                    DevWorkspaces.RUNNING,
                    DevWorkspaces.RUNNING_TIMEOUT,
                    checkCancelled
            )
        ) throw IOException(
            "DevWorkspace '${workspace.name}' is not running after ${DevWorkspaces.RUNNING_TIMEOUT} seconds"
        )
    }

    @Throws(IOException::class, ApiException::class, CancellationException::class)
    private suspend fun stopAndWaitDevWorkspace(checkCancelled: (() -> Unit)? = null) {
        // We really need a refreshed DevWorkspace here
        val devWorkspace = DevWorkspaces(devSpacesContext.client).get(
            workspace.namespace,
            workspace.name)

        if (devWorkspace.started) {
            checkCancelled?.invoke()
            DevWorkspaces(devSpacesContext.client)
                .stop(
                    workspace.namespace,
                    workspace.name
                )
        }

        if (!DevWorkspaces(devSpacesContext.client)
                .waitPhase(
                    workspace.namespace,
                    workspace.name,
                    DevWorkspaces.STOPPED,
                    DevWorkspaces.RUNNING_TIMEOUT,
                    checkCancelled
                )
        ) throw IOException(
            "DevWorkspace '${workspace.name}' has not stopped after ${DevWorkspaces.RUNNING_TIMEOUT} seconds"
        )
    }
}
