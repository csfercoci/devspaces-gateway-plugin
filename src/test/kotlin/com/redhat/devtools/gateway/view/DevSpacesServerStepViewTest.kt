/*
 * Copyright (c) 2026 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package com.redhat.devtools.gateway.view

import com.redhat.devtools.gateway.openshift.Cluster
import com.redhat.devtools.gateway.view.steps.DevSpacesServerStepView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DevSpacesServerStepViewTest {

    @Test
    fun `resolveClusterSelection prefers the explicitly requested cluster`() {
        val first = Cluster("cluster-a", "https://cluster-a.example", "token-a")
        val second = Cluster("cluster-b", "https://cluster-b.example", "token-b")

        val selection = DevSpacesServerStepView.resolveClusterSelection(
            name = "cluster-b",
            clusters = listOf(first, second),
            savedCluster = first,
            savedServer = first.url,
            savedToken = "saved-token"
        )

        assertThat(selection.selectedCluster).isEqualTo(second)
        assertThat(selection.token).isEqualTo("token-b")
    }

    @Test
    fun `resolveClusterSelection falls back to saved server and prefers saved token`() {
        val first = Cluster("cluster-a", "https://cluster-a.example", "token-a")
        val second = Cluster("cluster-b", "https://cluster-b.example", "token-b")

        val selection = DevSpacesServerStepView.resolveClusterSelection(
            name = null,
            clusters = listOf(first, second),
            savedCluster = null,
            savedServer = second.url,
            savedToken = "saved-token"
        )

        assertThat(selection.selectedCluster).isEqualTo(second)
        assertThat(selection.token).isEqualTo("saved-token")
    }

    @Test
    fun `resolveClusterSelection prefers saved server over kubeconfig fallback cluster`() {
        val first = Cluster("cluster-a", "https://cluster-a.example", "token-a")
        val second = Cluster("cluster-b", "https://cluster-b.example", "token-b")

        val selection = DevSpacesServerStepView.resolveClusterSelection(
            name = null,
            clusters = listOf(first, second),
            savedCluster = null,
            savedServer = second.url,
            savedToken = "saved-token",
            fallbackName = first.name
        )

        assertThat(selection.selectedCluster).isEqualTo(second)
        assertThat(selection.token).isEqualTo("saved-token")
    }

    @Test
    fun `resolveClusterSelection creates an ad hoc cluster for saved custom server and keeps saved token`() {
        val first = Cluster("cluster-a", "https://cluster-a.example", "token-a")
        val customServer = "https://custom.devspaces.example"

        val selection = DevSpacesServerStepView.resolveClusterSelection(
            name = null,
            clusters = listOf(first),
            savedCluster = null,
            savedServer = customServer,
            savedToken = "saved-token"
        )

        assertThat(selection.selectedCluster?.url).isEqualTo(customServer)
        assertThat(selection.token).isEqualTo("saved-token")
    }

    @Test
    fun `resolveClusterSelection prefers saved custom server over kubeconfig fallback cluster`() {
        val first = Cluster("cluster-a", "https://cluster-a.example", "token-a")
        val customServer = "https://custom.devspaces.example"

        val selection = DevSpacesServerStepView.resolveClusterSelection(
            name = null,
            clusters = listOf(first),
            savedCluster = null,
            savedServer = customServer,
            savedToken = "saved-token",
            fallbackName = first.name
        )

        assertThat(selection.selectedCluster?.url).isEqualTo(customServer)
        assertThat(selection.token).isEqualTo("saved-token")
    }

    @Test
    fun `resolveClusterSelection falls back to first cluster when there is no saved match`() {
        val first = Cluster("cluster-a", "https://cluster-a.example", "token-a")
        val second = Cluster("cluster-b", "https://cluster-b.example", "token-b")

        val selection = DevSpacesServerStepView.resolveClusterSelection(
            name = null,
            clusters = listOf(first, second),
            savedCluster = null,
            savedServer = null,
            savedToken = null
        )

        assertThat(selection.selectedCluster).isEqualTo(first)
        assertThat(selection.token).isEqualTo("token-a")
    }

    @Test
    fun `resolveClusterSelection prefers saved token over kubeconfig token for matching server`() {
        val cluster = Cluster("cluster-a", "https://cluster-a.example", "kubeconfig-token")

        val selection = DevSpacesServerStepView.resolveClusterSelection(
            name = null,
            clusters = listOf(cluster),
            savedCluster = null,
            savedServer = cluster.url,
            savedToken = "manual-token"
        )

        assertThat(selection.selectedCluster).isEqualTo(cluster)
        assertThat(selection.token).isEqualTo("manual-token")
    }

    @Test
    fun `resolveClusterSelection uses cluster token when no saved token exists`() {
        val cluster = Cluster("cluster-a", "https://cluster-a.example", "kubeconfig-token")

        val selection = DevSpacesServerStepView.resolveClusterSelection(
            name = null,
            clusters = listOf(cluster),
            savedCluster = null,
            savedServer = cluster.url,
            savedToken = null
        )

        assertThat(selection.selectedCluster).isEqualTo(cluster)
        assertThat(selection.token).isEqualTo("kubeconfig-token")
    }

    @Test
    fun `resolveClusterSelection uses cluster token when saved server differs`() {
        val cluster = Cluster("cluster-a", "https://cluster-a.example", "kubeconfig-token")

        val selection = DevSpacesServerStepView.resolveClusterSelection(
            name = "cluster-a",
            clusters = listOf(cluster),
            savedCluster = null,
            savedServer = "https://other-server.example",
            savedToken = "manual-token"
        )

        assertThat(selection.selectedCluster).isEqualTo(cluster)
        assertThat(selection.token).isEqualTo("kubeconfig-token")
    }
}