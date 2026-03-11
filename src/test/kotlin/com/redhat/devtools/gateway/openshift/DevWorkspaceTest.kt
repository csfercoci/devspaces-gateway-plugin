/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.redhat.devtools.gateway.openshift

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DevWorkspaceTest {

    @Test
    fun `equals and hashCode stay stable across status refreshes for same workspace identity`() {
        val running = workspace(started = true, phase = "Running")
        val stopped = workspace(started = false, phase = "Stopped")

        assertThat(running).isEqualTo(stopped)
    }

    @Test
    fun `set membership still works after workspace refresh`() {
        val active = hashSetOf(workspace(started = true, phase = "Running"))
        val refreshed = workspace(started = false, phase = "Stopped")

        assertThat(active).contains(refreshed)
    }

    private fun workspace(started: Boolean, phase: String): DevWorkspace {
        return DevWorkspace(
            DevWorkspaceObjectMeta("workspace-a", "namespace-a", "uid-a", null),
            DevWorkspaceSpec(started),
            DevWorkspaceStatus(phase)
        )
    }
}