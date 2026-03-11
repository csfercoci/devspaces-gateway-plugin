/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.redhat.devtools.gateway

import com.redhat.devtools.gateway.openshift.DevWorkspace
import com.redhat.devtools.gateway.openshift.DevWorkspaceObjectMeta
import com.redhat.devtools.gateway.openshift.DevWorkspaceSpec
import com.redhat.devtools.gateway.openshift.DevWorkspaceStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DevSpacesContextTest {

    @Test
    fun `addWorkspace tracks two active workspaces at the same time`() {
        val context = DevSpacesContext()
        val first = workspace(name = "workspace-a", namespace = "team-a")
        val second = workspace(name = "workspace-b", namespace = "team-a")

        context.addWorkspace(first)
        context.addWorkspace(second)

        assertThat(context.activeWorkspaces)
            .containsExactlyInAnyOrder(first, second)
    }

    @Test
    fun `removeWorkspace only removes the targeted active workspace`() {
        val context = DevSpacesContext()
        val first = workspace(name = "workspace-a", namespace = "team-a")
        val second = workspace(name = "workspace-b", namespace = "team-a")

        context.addWorkspace(first)
        context.addWorkspace(second)
        context.removeWorkspace(first)

        assertThat(context.activeWorkspaces)
            .containsExactly(second)
    }

    @Test
    fun `addWorkspace does not duplicate the same workspace after a status refresh`() {
        val context = DevSpacesContext()
        val running = workspace(name = "workspace-a", namespace = "team-a", started = true, phase = "Running")
        val stopped = workspace(name = "workspace-a", namespace = "team-a", started = false, phase = "Stopped")

        context.addWorkspace(running)
        context.addWorkspace(stopped)

        assertThat(context.activeWorkspaces).hasSize(1)
        assertThat(context.activeWorkspaces).contains(running)
    }

    @Test
    fun `removing a stopped refreshed workspace leaves the other active session intact`() {
        val context = DevSpacesContext()
        val firstRunning = workspace(name = "workspace-a", namespace = "team-a", started = true, phase = "Running")
        val firstStopped = workspace(name = "workspace-a", namespace = "team-a", started = false, phase = "Stopped")
        val secondRunning = workspace(name = "workspace-b", namespace = "team-a", started = true, phase = "Running")

        context.addWorkspace(firstRunning)
        context.addWorkspace(secondRunning)
        context.removeWorkspace(firstStopped)

        assertThat(context.activeWorkspaces)
            .containsExactly(secondRunning)
    }

    private fun workspace(
        name: String,
        namespace: String,
        started: Boolean = true,
        phase: String = "Running",
        cheEditor: String? = null
    ): DevWorkspace {
        return DevWorkspace(
            DevWorkspaceObjectMeta(name, namespace, "$namespace-$name-uid", cheEditor),
            DevWorkspaceSpec(started),
            DevWorkspaceStatus(phase)
        )
    }
}