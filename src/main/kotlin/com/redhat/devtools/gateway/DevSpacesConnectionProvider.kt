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
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.ui.dsl.builder.Align.Companion.CENTER
import com.intellij.ui.dsl.builder.panel
import com.jetbrains.gateway.api.ConnectionRequestor
import com.jetbrains.gateway.api.GatewayConnectionHandle
import com.jetbrains.gateway.api.GatewayConnectionProvider
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.redhat.devtools.gateway.kubeconfig.KubeConfigUtils
import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.OpenShiftClientFactory
import com.redhat.devtools.gateway.openshift.isNotFound
import com.redhat.devtools.gateway.openshift.isUnauthorized
import com.redhat.devtools.gateway.util.ProgressCountdown
import com.redhat.devtools.gateway.util.isCancellationException
import com.redhat.devtools.gateway.util.messageWithoutPrefix
import com.redhat.devtools.gateway.view.ui.Dialogs
import io.kubernetes.client.openapi.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.util.concurrent.CancellationException
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.coroutines.resume

private const val DW_NAMESPACE = "dwNamespace"
private const val DW_NAME = "dwName"
private const val LINK = "link"

/**
 * Handles links as:
 *      jetbrains-gateway://connect#type=devspaces
 *      https://code-with-me.jetbrains.com/remoteDev#type=devspaces
 */
class DevSpacesConnectionProvider : GatewayConnectionProvider {

    companion object {
        internal fun directGatewayLink(parameters: Map<String, String>): URI? {
            val rawLink = parameters[LINK]
                ?.takeIf { it.isNotBlank() }
                ?: return null

            return runCatching { URI(rawLink) }
                .getOrNull()
                ?.takeIf { uri ->
                    !uri.scheme.isNullOrBlank() &&
                        (!uri.host.isNullOrBlank() || !uri.authority.isNullOrBlank())
                }
        }
    }

    private var clientFactory: OpenShiftClientFactory? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("UnstableApiUsage")
    override suspend fun connect(
        parameters: Map<String, String>,
        requestor: ConnectionRequestor
    ): GatewayConnectionHandle? {
        logIncomingRequest(parameters)

        return suspendCancellableCoroutine { cont ->
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    val indicator = ProgressCountdown(ProgressManager.getInstance().progressIndicator)
                    try {
                        indicator.isIndeterminate = true
                        indicator.text = "Connecting to DevSpace..."

                        val handle = doConnect(parameters, indicator)
                        val thinClient = handle.clientHandle
                            ?: throw RuntimeException("Failed to obtain ThinClientHandle")

                        indicator.text = "Waiting for remote IDE to start..."

                        val ready = CompletableDeferred<GatewayConnectionHandle?>()

                        thinClient.onClientPresenceChanged.advise(thinClient.lifetime,
                            onClientPresenceChanged(ready, indicator, handle)
                        )
                        thinClient.clientFailedToOpenProject.advise(thinClient.lifetime,
                            onClientFailedToOpenProject(ready, indicator)
                        )
                        thinClient.clientClosed.advise(thinClient.lifetime,
                            onClientClosed(ready, indicator)
                        )
                        if (thinClient.clientPresent && ready.isActive) {
                            ready.complete(handle)
                        }
                        ready.invokeOnCompletion { error ->
                            if (error == null) {
                                cont.resume(ready.getCompleted())
                            } else {
                                cont.resumeWith(Result.failure(error))
                            }
                        }
                    } catch (e: ApiException) {
                        thisLogger().warn(connectionFailureMessage(parameters), e)
                        indicator.text = "Connection failed"
                        runDelayed(2000, { if (indicator.isRunning) indicator.stop() })
                        if (!(handleUnauthorizedError(e) || handleNotFoundError(e))) {
                            Dialogs.error(
                                e.messageWithoutPrefix() ?: "Could not connect to workspace.",
                                "Connection Error"
                            )
                        }

                        if (cont.isActive) cont.resume(null)
                    } catch (e: Exception) {
                        thisLogger().warn(connectionFailureMessage(parameters), e)
                        if (e.isCancellationException() || indicator.isCanceled) {
                            indicator.text2 = "Error: ${e.message}"
                            runDelayed(2000) { if (indicator.isRunning) indicator.stop() }
                        } else {
                            runDelayed(2000) { if (indicator.isRunning) indicator.stop() }
                            Dialogs.error(
                                e.message ?: "Could not connect to workspace.",
                                "Connection Error"
                            )
                        }
                        cont.resume(null)
                    } finally {
                        indicator.dispose()
                    }
                },
                "Connecting to Remote IDE...",
                true,
                null
            )
        }
    }

    private fun logIncomingRequest(parameters: Map<String, String>) {
        val namespace = parameters[DW_NAMESPACE].orEmpty()
        val workspace = parameters[DW_NAME].orEmpty()
        val link = parameters[LINK].orEmpty()

        thisLogger().info(
            "Received Dev Spaces connection request: namespace='$namespace', workspace='$workspace', link='$link'"
        )
    }

    private fun connectionFailureMessage(parameters: Map<String, String>): String {
        val namespace = parameters[DW_NAMESPACE].orEmpty()
        val workspace = parameters[DW_NAME].orEmpty()
        return "Dev Spaces connection failed: namespace='$namespace', workspace='$workspace'"
    }

    private fun onClientPresenceChanged(
        ready: CompletableDeferred<GatewayConnectionHandle?>,
        indicator: ProgressIndicator,
        handle: GatewayConnectionHandle
    ): (Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater {
            if (!ready.isCompleted) {
                indicator.text = "Remote IDE has started successfully"
                indicator.text2 = "Opening project window…"
                runDelayed(3000) {
                    if (indicator.isRunning) indicator.stop()
                    if (ready.isActive) ready.complete(handle)
                }
            }
        }
    }

    private fun onClientFailedToOpenProject(
        ready: CompletableDeferred<GatewayConnectionHandle?>,
        indicator: ProgressIndicator
    ): (Int) -> Unit = { errorCode ->
        ApplicationManager.getApplication().invokeLater {
            if (!ready.isCompleted) {
                indicator.text = "Failed to open remote project (code: $errorCode)"
                runDelayed(2000) {
                    if (indicator.isRunning) indicator.stop()
                    if (ready.isActive) ready.complete(null)
                }
            }
        }
    }

    private fun onClientClosed(
        ready: CompletableDeferred<GatewayConnectionHandle?>,
        indicator: ProgressIndicator
    ): (Unit) -> Unit = {
        ApplicationManager.getApplication().invokeLater {
            if (!ready.isCompleted) {
                indicator.text = "Remote IDE closed unexpectedly."
                runDelayed(2000) {
                    if (indicator.isRunning) indicator.stop()
                    if (ready.isActive) ready.complete(null)
                }
            }
        }
    }

    @Suppress("UnstableApiUsage")
    @Throws(IllegalArgumentException::class)
    private fun doConnect(
        parameters: Map<String, String>,
        indicator: ProgressCountdown
    ): GatewayConnectionHandle {
        thisLogger().debug("Launched Dev Spaces connection provider", parameters)

        directGatewayLink(parameters)?.let { directLink ->
            val workspaceName = parameters[DW_NAME].takeUnless { it.isNullOrBlank() } ?: "Remote IDE"
            indicator.update(message = "Opening remote IDE from external link…")
            thisLogger().info("Using direct Dev Spaces gateway link '$directLink' for workspace '$workspaceName'")

            val thinClient = LinkedClientManager
                .getInstance()
                .startNewClient(
                    com.jetbrains.rd.util.lifetime.Lifetime.Eternal,
                    directLink,
                    "",
                    {},
                    false
                )

            return DevSpacesConnectionHandle(
                thinClient.lifetime,
                thinClient,
                { createComponent(workspaceName) },
                workspaceName
            )
        }

        indicator.update(message = "Preparing connection environment…")

        val dwNamespace = parameters[DW_NAMESPACE]
        if (dwNamespace.isNullOrBlank()) {
            thisLogger().error("Query parameter \"$DW_NAMESPACE\" is missing")
            throw IllegalArgumentException("Query parameter \"$DW_NAMESPACE\" is missing")
        }

        val dwName = parameters[DW_NAME]
        if (dwName.isNullOrBlank()) {
            thisLogger().error("Query parameter \"$DW_NAME\" is missing")
            throw IllegalArgumentException("Query parameter \"$DW_NAME\" is missing")
        }

        val ctx = DevSpacesContext()

        indicator.update(message = "Initializing Kubernetes connection…")
        val factory = OpenShiftClientFactory(KubeConfigUtils)
        this.clientFactory = factory
        ctx.client = factory.create()

        indicator.update(message = "Fetching DevWorkspace “$dwName” from namespace “$dwNamespace”…")
        ctx.devWorkspace = DevWorkspaces(ctx.client).get(dwNamespace, dwName)

        indicator.update(message = "Establishing remote IDE connection…")
        val thinClient = DevSpacesConnection(ctx)
            .connect({}, {}, {},
                onProgress = { value ->
                    indicator.update(value.title, value.message, value.countdownSeconds)
                },
                checkCancelled = {
                    if (indicator.isCanceled) throw CancellationException("User cancelled the operation")
                }
            )

        indicator.update(message = "Connection established successfully.")
        return DevSpacesConnectionHandle(
            thinClient.lifetime,
            thinClient,
            { createComponent(dwName) },
            dwName)
    }

    override fun isApplicable(parameters: Map<String, String>): Boolean {
        return parameters["type"] == "devspaces"
    }

    private fun createComponent(dwName: String): JComponent {
        return panel {
            indent {
                row {
                    resizableRow()
                    panel {
                        row {
                            icon(DevSpacesIcons.LOGO).align(CENTER)
                        }
                        row {
                            label(dwName).bold().align(CENTER)
                        }
                    }.align(CENTER)
                }
            }
        }
    }

    private fun handleUnauthorizedError(err: ApiException): Boolean {
        if (!err.isUnauthorized()) return false

        val tokenNote = if (clientFactory?.isTokenAuth() == true)
            "\n\nYou are using token-based authentication.\nUpdate your token in the kubeconfig file."
        else ""

        Dialogs.error(
            "Your session has expired.\nPlease log in again to continue.$tokenNote",
            "Authentication Required"
        )
        return true
    }

    private fun handleNotFoundError(err: ApiException): Boolean {
        if (!err.isNotFound()) return false

        val message = """
            Workspace or DevWorkspace support not found.
            You're likely connected to a cluster that doesn't have the DevWorkspace Operator installed, or the specified workspace doesn't exist.
        
            Please verify your Kubernetes context, namespace, and that the DevWorkspace Operator is installed and running.
        """.trimIndent()

        Dialogs.error(message, "Resource Not Found")
        return true
    }

    private fun runDelayed(delay: Int, runnable: () -> Unit) {
        Timer(delay) {
            runnable.invoke()
        }.start()
    }

}
