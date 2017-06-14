/*
 * Copyright (c) 2017 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.compute.cluster;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;

import com.vmware.admiral.common.ManagementUriParts;
import com.vmware.admiral.common.util.QueryUtil;
import com.vmware.admiral.compute.ContainerHostService;
import com.vmware.admiral.compute.ContainerHostService.ContainerHostSpec;
import com.vmware.admiral.compute.ContainerHostService.ContainerHostType;
import com.vmware.admiral.compute.ContainerHostUtil;
import com.vmware.admiral.compute.PlacementZoneUtil;
import com.vmware.admiral.compute.cluster.ClusterService.ClusterDto;
import com.vmware.admiral.compute.cluster.ClusterService.ClusterStatus;
import com.vmware.admiral.compute.cluster.ClusterService.ClusterType;
import com.vmware.admiral.compute.container.ComputeBaseTest;
import com.vmware.admiral.compute.container.GroupResourcePlacementService;
import com.vmware.admiral.compute.container.GroupResourcePlacementService.GroupResourcePlacementState;
import com.vmware.admiral.service.test.MockDockerHostAdapterService;
import com.vmware.photon.controller.model.query.QueryUtils.QueryByPages;
import com.vmware.photon.controller.model.query.QueryUtils.QueryTemplate;
import com.vmware.photon.controller.model.resources.ComputeService;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.photon.controller.model.resources.ResourcePoolService;
import com.vmware.photon.controller.model.resources.ResourcePoolService.ResourcePoolState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.services.common.QueryTask;

public class ClusterServiceTest extends ComputeBaseTest {
    private static final String COMPUTE_ADDRESS = "test.host.address";
    private static final String ADAPTER_DOCKER_TYPE_ID = "API";

    private MockDockerHostAdapterService dockerAdapterService;

    @Before
    public void setUp() throws Throwable {
        waitForServiceAvailability(ClusterService.SELF_LINK);
        waitForServiceAvailability(ContainerHostService.SELF_LINK);
        waitForServiceAvailability(ComputeService.FACTORY_LINK);
        waitForServiceAvailability(ResourcePoolService.FACTORY_LINK);
        waitForServiceAvailability(GroupResourcePlacementService.FACTORY_LINK);

        dockerAdapterService = new MockDockerHostAdapterService();
        host.startService(Operation.createPost(UriUtils.buildUri(host,
                MockDockerHostAdapterService.class)), dockerAdapterService);
        waitForServiceAvailability(MockDockerHostAdapterService.SELF_LINK);

    }

    @Test
    public void testCreateDockerCluster() throws Throwable {
        final String projectLink = buildProjectLink("test-docker-project");
        final String placementZoneName = PlacementZoneUtil
                .buildPlacementZoneDefaultName(ContainerHostType.DOCKER, COMPUTE_ADDRESS);

        ContainerHostSpec hostSpec = createContainerHostSpec(Collections.singletonList(projectLink),
                ContainerHostType.DOCKER);
        verifyCluster(createCluster(hostSpec), ClusterType.DOCKER, placementZoneName, projectLink);
    }

    @Test
    public void testCreateVchCluster() throws Throwable {
        final String projectLink = buildProjectLink("test-vch-project");
        final String placementZoneName = PlacementZoneUtil
                .buildPlacementZoneDefaultName(ContainerHostType.VCH, COMPUTE_ADDRESS);

        ContainerHostSpec hostSpec = createContainerHostSpec(Collections.singletonList(projectLink),
                ContainerHostType.VCH);
        verifyCluster(createCluster(hostSpec), ClusterType.VCH, placementZoneName, projectLink);
    }

    private void verifyCluster(ClusterDto clusterDto, ClusterType clusterType, String expectedName,
            String projectLink) throws Throwable {
        // verify cluster creation
        assertNotNull(clusterDto);
        assertNotNull(clusterDto.documentSelfLink);
        assertEquals(clusterType, clusterDto.type);
        assertNotNull(clusterDto.nodeLinks);
        assertEquals(1, clusterDto.nodeLinks.size());
        assertEquals(ClusterStatus.ON, clusterDto.status);
        assertEquals(expectedName, clusterDto.name);

        // verify placement zone
        final String placementZoneLink = UriUtils.buildUriPath(ResourcePoolService.FACTORY_LINK,
                Service.getId(clusterDto.documentSelfLink));
        ResourcePoolState placementZone = getDocument(ResourcePoolState.class, placementZoneLink);
        assertNotNull(placementZone);
        assertNotNull(placementZone.tenantLinks);
        assertTrue(placementZone.tenantLinks.contains(projectLink));
        assertEquals(expectedName, placementZone.name);

        // verify placement
        List<GroupResourcePlacementState> placements = getPlacementsForZone(placementZoneLink);
        assertNotNull(placements);
        assertEquals(1, placements.size());
        GroupResourcePlacementState placement = placements.iterator().next();
        assertNotNull(placement);
        assertEquals(placementZoneLink, placement.resourcePoolLink);
        assertNotNull(placement.tenantLinks);
        assertTrue(placement.tenantLinks.contains(projectLink));

        // verify compute state
        ComputeState hostState = getDocument(ComputeState.class,
                clusterDto.nodeLinks.iterator().next());
        assertNotNull(hostState);
        assertNotNull(hostState.tenantLinks);
        assertTrue(hostState.tenantLinks.contains(projectLink));
        if (clusterType == ClusterType.DOCKER) {
            assertEquals(ContainerHostType.DOCKER,
                    ContainerHostUtil.getDeclaredContainerHostType(hostState));
        } else {
            assertEquals(ContainerHostType.VCH,
                    ContainerHostUtil.getDeclaredContainerHostType(hostState));
        }
        assertEquals(ComputeService.PowerState.ON, hostState.powerState);
    }

    private List<GroupResourcePlacementState> getPlacementsForZone(String placementZoneLink) {
        QueryTask queryTask = QueryUtil.buildPropertyQuery(GroupResourcePlacementState.class,
                GroupResourcePlacementState.FIELD_NAME_RESOURCE_POOL_LINK, placementZoneLink);

        QueryByPages<GroupResourcePlacementState> pages = new QueryByPages<>(host,
                queryTask.querySpec.query, GroupResourcePlacementState.class, null);

        return QueryTemplate.waitToComplete(pages.collectDocuments(Collectors.toList()));
    }

    private String buildProjectLink(String projectId) {
        return UriUtils.buildUriPath(ManagementUriParts.PROJECTS, projectId);
    }

    private ClusterDto createCluster(ContainerHostSpec hostSpec) {
        ArrayList<ClusterDto> result = new ArrayList<>(1);

        Operation create = Operation.createPost(host, ClusterService.SELF_LINK)
                .setReferer(host.getUri())
                .setBody(hostSpec)
                .setCompletion((o, ex) -> {
                    if (ex != null) {
                        host.log(Level.SEVERE, "Failed to create cluster: %s", Utils.toString(ex));
                        host.failIteration(ex);
                    } else {
                        try {
                            result.add(o.getBody(ClusterDto.class));
                            host.completeIteration();
                        } catch (Throwable er) {
                            host.log(Level.SEVERE,
                                    "Failed to retrieve created cluster DTO from response: %s",
                                    Utils.toString(er));
                            host.failIteration(er);
                        }
                    }
                });

        host.testStart(1);
        host.send(create);
        host.testWait();

        assertEquals(1, result.size());
        ClusterDto dto = result.iterator().next();
        assertNotNull(dto);
        return dto;
    }

    private ContainerHostSpec createContainerHostSpec(List<String> tenantLinks,
            ContainerHostType hostType) throws Throwable {
        ContainerHostSpec ch = new ContainerHostSpec();
        ch.hostState = createComputeState(hostType, ComputeService.PowerState.ON, tenantLinks);
        return ch;
    }

    private ComputeState createComputeState(ContainerHostType hostType,
            ComputeService.PowerState hostState, List<String> tenantLinks) throws Throwable {
        ComputeState cs = new ComputeState();
        cs.id = UUID.randomUUID().toString();
        cs.address = COMPUTE_ADDRESS;
        cs.powerState = hostState;
        cs.customProperties = new HashMap<>();
        cs.customProperties.put(ContainerHostService.HOST_DOCKER_ADAPTER_TYPE_PROP_NAME,
                ADAPTER_DOCKER_TYPE_ID);
        cs.customProperties.put(ContainerHostService.CONTAINER_HOST_TYPE_PROP_NAME,
                hostType.toString());
        cs.customProperties.put(MockDockerHostAdapterService.CONTAINER_HOST_TYPE_PROP_NAME,
                hostType.toString());
        cs.tenantLinks = new ArrayList<>(tenantLinks);
        return cs;
    }
}