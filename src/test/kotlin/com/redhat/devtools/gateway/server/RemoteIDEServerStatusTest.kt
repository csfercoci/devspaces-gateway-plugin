/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.redhat.devtools.gateway.server

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RemoteIDEServerStatusTest {

    @Test
    fun `preferredJoinLink uses top level join link when present`() {
        val status = RemoteIDEServerStatus(
            joinLink = "tcp://workspace.devspaces.example:5990/join",
            httpLink = "",
            gatewayLink = "",
            appVersion = "",
            runtimeVersion = "",
            projects = arrayOf(
                ProjectInfo("project", "/projects/project", "tcp://project.devspaces.example:5991/join", "", "")
            )
        )

        assertThat(status.preferredJoinLink).isEqualTo("tcp://workspace.devspaces.example:5990/join")
    }

    @Test
    fun `preferredJoinLink falls back to the first project join link for older server payloads`() {
        val status = RemoteIDEServerStatus(
            joinLink = null,
            httpLink = "",
            gatewayLink = "",
            appVersion = "",
            runtimeVersion = "",
            projects = arrayOf(
                ProjectInfo("project", "/projects/project", "tcp://project.devspaces.example:5991/join", "", "")
            )
        )

        assertThat(status.preferredJoinLink).isEqualTo("tcp://project.devspaces.example:5991/join")
        assertThat(status.isReady).isTrue
    }

    @Test
    fun `isReady returns true for top level join link even when projects are absent`() {
        val status = RemoteIDEServerStatus(
            joinLink = "tcp://workspace.devspaces.example:5990/join",
            httpLink = "",
            gatewayLink = "",
            appVersion = "",
            runtimeVersion = "",
            projects = null
        )

        assertThat(status.isReady).isTrue
    }

    @Test
    fun `isFullyRunning returns true when join link and projects are present`() {
        val status = RemoteIDEServerStatus(
            joinLink = "tcp://workspace.devspaces.example:5990/join",
            httpLink = "",
            gatewayLink = "",
            appVersion = "",
            runtimeVersion = "",
            projects = arrayOf(
                ProjectInfo("project", "/projects/project", "tcp://project.devspaces.example:5991/join", "", "")
            )
        )

        assertThat(status.isFullyRunning).isTrue
    }

    @Test
    fun `isFullyRunning returns false when join link is present but projects are absent`() {
        val status = RemoteIDEServerStatus(
            joinLink = "tcp://workspace.devspaces.example:5990/join",
            httpLink = "",
            gatewayLink = "",
            appVersion = "",
            runtimeVersion = "",
            projects = null
        )

        assertThat(status.isReady).isTrue
        assertThat(status.isFullyRunning).isFalse
    }

    @Test
    fun `isFullyRunning returns false when empty`() {
        val status = RemoteIDEServerStatus.empty()

        assertThat(status.isReady).isFalse
        assertThat(status.isFullyRunning).isFalse
    }
}