/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.redhat.devtools.gateway

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DevSpacesConnectionTest {

    @Test
    fun `remoteIdePort uses port from join link`() {
        val joinLink = "tcp://workspace.devspaces.example:63342/join"

        assertThat(DevSpacesConnection.remoteIdePort(joinLink)).isEqualTo(63342)
    }

    @Test
    fun `remoteIdePort falls back to default when join link has no explicit port`() {
        val joinLink = "tcp://workspace.devspaces.example/join"

        assertThat(DevSpacesConnection.remoteIdePort(joinLink)).isEqualTo(5990)
    }

    @Test
    fun `localJoinLink rewrites only host port and preserves path query and fragment`() {
        val joinLink = "tcp://workspace.devspaces.example:63342/project/join?foo=bar#baz"

        assertThat(DevSpacesConnection.localJoinLink(joinLink, 51234))
            .isEqualTo("tcp://workspace.devspaces.example:51234/project/join?foo=bar#baz")
    }
}