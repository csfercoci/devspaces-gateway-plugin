/*
 * Copyright (c) 2025 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.server

import com.google.gson.Gson
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.openshift.DevWorkspace
import com.redhat.devtools.gateway.openshift.DevWorkspaceObjectMeta
import com.redhat.devtools.gateway.openshift.DevWorkspaceSpec
import com.redhat.devtools.gateway.openshift.DevWorkspaceStatus
import com.redhat.devtools.gateway.openshift.Pods
import io.kubernetes.client.openapi.models.V1Container
import io.kubernetes.client.openapi.models.V1ObjectMeta
import io.kubernetes.client.openapi.models.V1Pod
import io.kubernetes.client.openapi.models.V1PodSpec
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class RemoteIDEServerTest {

    private lateinit var devSpacesContext: DevSpacesContext
    private lateinit var remoteIDEServer: RemoteIDEServer
    private lateinit var workspace: DevWorkspace

    @BeforeEach
    fun beforeEach() {
        devSpacesContext = mockk(relaxed = true)
        workspace = DevWorkspace(
            DevWorkspaceObjectMeta("test-workspace", "test-namespace", "uid-1", null),
            DevWorkspaceSpec(true),
            DevWorkspaceStatus("Running")
        )

        mockkConstructor(Pods::class)
        val mockPod = V1Pod().apply {
            metadata = V1ObjectMeta().apply {
                name = "test-pod"
            }
            spec = V1PodSpec().apply {
                containers = listOf(
                    V1Container().apply {
                        name = "test-container"
                        ports = listOf(
                            mockk(relaxed = true) {
                                every { name } returns "idea-server"
                            }
                        )
                    }
                )
            }
        }
        coEvery {
            anyConstructed<Pods>().findFirst(any(), any())
        } returns mockPod

        remoteIDEServer = RemoteIDEServer(devSpacesContext, workspace)
    }

    @AfterEach
    fun afterEach() {
        unmockkAll()
    }

    @Test
    fun `#waitServerReady should throw if server status has no supported join link and no projects`() {
        // given
        val withoutProjects = remoteIDEServerStatus(null, null)
        coEvery {
            anyConstructed<Pods>().exec(any(), any(), any(), any(), any())
        } returns statusOutput(withoutProjects)

        // when, then
        assertThrows<IOException> {
            runBlocking {
                remoteIDEServer.waitServerReady(timeout = 1)
            }
        }
    }

    @Test
    fun `#waitServerReady should return true if project join link is present`() {
        // given
        val withoutProjects = remoteIDEServerStatus(
            null,
            arrayOf(projectInfo("death star"))
        )
        coEvery {
            anyConstructed<Pods>().exec(any(), any(), any(), any(), any())
        } returns statusOutput(withoutProjects)

        // when
        val result = runBlocking {
            remoteIDEServer.waitServerReady(timeout = 1)
        }

        // then
        assertThat(result).isTrue
    }

    @Test
    fun `#waitServerTerminated should return true if server status has a join link but no projects`() {
        // given - server has a join link but no projects, so it's no longer fully running
        val withoutProjects = remoteIDEServerStatus(
            "https://starwars.galaxy?peridea",
            null
        )
        coEvery {
            anyConstructed<Pods>().exec(any(), any(), any(), any(), any())
        } returns statusOutput(withoutProjects)

        // when
        val result = runBlocking {
            remoteIDEServer.waitServerTerminated(1)
        }

        // then
        assertThat(result).isTrue
    }

    @Test
    fun `#waitServerTerminated should return false on timeout`() {
        // given
        coEvery {
            anyConstructed<Pods>().exec(any(), any(), any(), any(), any())
        } returns statusOutput(remoteIDEServerStatus(
            // running server has join link and projects
            "https://starwars.galaxy?peridea",
            arrayOf(
                projectInfo("death star")
            )
        ))

        // when
        val result = runBlocking {
            remoteIDEServer.waitServerTerminated(1)
        }

        // then
        assertThat(result).isFalse
    }

    @Test
    fun `#waitServerTerminated should return false on exception`() {
        // given
        coEvery {
            anyConstructed<Pods>().exec(any(), any(), any(), any(), any())
        } throws IOException("error")

        // when
        val result = runBlocking {
            remoteIDEServer.waitServerTerminated(1)
        }

        // then
        assertThat(result).isFalse
    }

    private fun remoteIDEServerStatus(joinLink: String? = null, projects: Array<ProjectInfo>?): RemoteIDEServerStatus {
        return RemoteIDEServerStatus(
            joinLink,
            "",
            "",
            "",
            "",
            projects
        )
    }

    private fun statusOutput(status: RemoteIDEServerStatus): String {
        return Gson().toJson(status)
    }

    private fun projectInfo(name: String): ProjectInfo {
        return ProjectInfo(
            name,
            name,
            name,
            name,
            name
        )

    }

}
