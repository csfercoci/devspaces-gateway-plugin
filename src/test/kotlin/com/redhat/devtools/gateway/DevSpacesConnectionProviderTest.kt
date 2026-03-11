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

class DevSpacesConnectionProviderTest {

    @Test
    fun `directGatewayLink returns uri for valid external link`() {
        val uri = DevSpacesConnectionProvider.directGatewayLink(
            mapOf("link" to "tcp://127.0.0.1:5990#jt=abc")
        )

        assertThat(uri).isNotNull
        assertThat(uri.toString()).isEqualTo("tcp://127.0.0.1:5990#jt=abc")
    }

    @Test
    fun `directGatewayLink ignores blank or malformed values`() {
        assertThat(DevSpacesConnectionProvider.directGatewayLink(mapOf("link" to ""))).isNull()
        assertThat(DevSpacesConnectionProvider.directGatewayLink(mapOf("link" to "not a uri"))).isNull()
        assertThat(DevSpacesConnectionProvider.directGatewayLink(emptyMap())).isNull()
    }
}