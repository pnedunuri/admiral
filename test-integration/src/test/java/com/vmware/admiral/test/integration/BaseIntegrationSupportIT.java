/*
 * Copyright (c) 2016-2018 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.test.integration;

import static org.junit.Assert.assertNotNull;

import static com.vmware.admiral.test.integration.TestPropertiesUtil.getSystemOrTestProp;

import java.net.HttpURLConnection;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.Timeout;

import com.vmware.admiral.common.util.AssertUtil;
import com.vmware.admiral.compute.ContainerHostService;
import com.vmware.admiral.compute.ContainerHostService.ContainerHostSpec;
import com.vmware.admiral.compute.ResourceType;
import com.vmware.admiral.compute.container.CompositeComponentRegistry;
import com.vmware.admiral.compute.container.ContainerDescriptionService;
import com.vmware.admiral.compute.container.ContainerDescriptionService.ContainerDescription;
import com.vmware.admiral.compute.container.ContainerFactoryService;
import com.vmware.admiral.compute.container.ContainerService.ContainerState;
import com.vmware.admiral.compute.container.HostContainerListDataCollection;
import com.vmware.admiral.compute.container.HostContainerListDataCollection.ContainerListCallback;
import com.vmware.admiral.compute.container.network.ContainerNetworkDescriptionService;
import com.vmware.admiral.compute.container.network.ContainerNetworkDescriptionService.ContainerNetworkDescription;
import com.vmware.admiral.compute.container.network.ContainerNetworkService;
import com.vmware.admiral.compute.container.network.ContainerNetworkService.ContainerNetworkState;
import com.vmware.admiral.compute.container.volume.ContainerVolumeDescriptionFactoryService;
import com.vmware.admiral.compute.container.volume.ContainerVolumeDescriptionService.ContainerVolumeDescription;
import com.vmware.admiral.compute.container.volume.ContainerVolumeFactoryService;
import com.vmware.admiral.compute.container.volume.ContainerVolumeService.ContainerVolumeState;
import com.vmware.admiral.request.ContainerHostRemovalTaskFactoryService;
import com.vmware.admiral.request.ContainerHostRemovalTaskService.ContainerHostRemovalTaskState;
import com.vmware.admiral.request.RequestStatusFactoryService;
import com.vmware.admiral.request.RequestStatusService.RequestStatus;
import com.vmware.admiral.service.common.DefaultSubStage;
import com.vmware.admiral.service.common.TaskServiceDocument;
import com.vmware.admiral.test.integration.SimpleHttpsClient.HttpMethod;
import com.vmware.admiral.test.integration.SimpleHttpsClient.HttpResponse;
import com.vmware.photon.controller.model.resources.ComputeService;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeState;
import com.vmware.photon.controller.model.resources.ComputeService.ComputeStateWithDescription;
import com.vmware.photon.controller.model.resources.ComputeService.PowerState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.TaskState;
import com.vmware.xenon.common.TestLogger;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;

public abstract class BaseIntegrationSupportIT {
    private static final String TEST_DCP_URL_PROP_NAME = "test.dcp.url";
    private static final String TEST_DCP_HOST_PROP_NAME = "test.dcp.host";
    private static final String TEST_DCP_PORT_PROP_NAME = "test.dcp.port";

    protected static final int STATE_CHANGE_WAIT_POLLING_RETRY_COUNT = Integer.getInteger(
            "test.state.change.wait.retry.count", 300);
    protected static final int TASK_CHANGE_WAIT_POLLING_RETRY_COUNT = Integer.getInteger(
            "test.task.change.wait.retry.count", 600);
    protected static final int STATE_CHANGE_WAIT_POLLING_PERIOD_MILLIS = Integer.getInteger(
            "test.state.change.wait.period.millis", 1000);
    protected static final Queue<ServiceDocument> documentsForDeletionAfterClass =
            new LinkedBlockingQueue<>();
    protected static final Queue<ServiceDocument> documentsForDeletion =
            new LinkedBlockingQueue<>();

    protected static final TestLogger logger = new TestLogger(BaseIntegrationSupportIT.class);
    private static String baseURI;

    @Rule
    public Timeout globalTimeout = Timeout.seconds(TimeUnit.MINUTES.toSeconds(5));

    @BeforeClass
    public static void baseBeforeClass() {
        // Allow "Host" header to be passed
        // http://stackoverflow.com/questions/7648872/can-i-override-the-host-header-where-using-javas-httpurlconnection-class
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");

        // register a well-know Components
        CompositeComponentRegistry.registerComponent(ResourceType.CONTAINER_TYPE.getName(),
                ContainerDescriptionService.FACTORY_LINK,
                ContainerDescription.class, ContainerFactoryService.SELF_LINK,
                ContainerState.class);
        CompositeComponentRegistry.registerComponent(ResourceType.NETWORK_TYPE.getName(),
                ContainerNetworkDescriptionService.FACTORY_LINK, ContainerNetworkDescription.class,
                ContainerNetworkService.FACTORY_LINK, ContainerNetworkState.class);
        CompositeComponentRegistry.registerComponent(ResourceType.VOLUME_TYPE.getName(),
                ContainerVolumeDescriptionFactoryService.SELF_LINK,
                ContainerVolumeDescription.class,
                ContainerVolumeFactoryService.SELF_LINK, ContainerVolumeState.class);
    }

    @AfterClass
    public static void baseAfterClass() throws Exception {
        deleteDocuments(documentsForDeletionAfterClass);
    }

    @After
    public void baseTearDown() throws Exception {
        deleteDocuments(documentsForDeletion);
    }

    protected static void cleanUpAfterClass(ServiceDocument document) {
        documentsForDeletionAfterClass.add(document);
    }

    protected static void cleanUpAfter(ServiceDocument document) {
        documentsForDeletion.add(document);
    }

    public static String getBaseUrl() {
        if (baseURI != null) {
            return baseURI;
        }
        // if a dynamic port is used, build the URL from the host and port parts
        String port = getSystemOrTestProp(TEST_DCP_PORT_PROP_NAME);

        if (port != null) {
            String host = getSystemOrTestProp(TEST_DCP_HOST_PROP_NAME, "127.0.0.1");
            return String.format("http://%s:%s", host, port);
        }

        return getSystemOrTestProp(TEST_DCP_URL_PROP_NAME);
    }

    protected static String buildServiceUri(String... paths) {
        StringBuilder url = new StringBuilder();
        for (String path : paths) {
            url.append(UriUtils.normalizeUriPath(path));
        }
        return url.toString();
    }

    protected static void delete(ServiceDocument document) throws Exception {
        delete(document.documentSelfLink);
    }

    protected static void delete(String documentSelfLink) throws Exception {
        sendRequest(HttpMethod.DELETE, documentSelfLink, Utils.toJson(new ServiceDocument()));
    }

    protected static <T> T getDocument(String selfLink,
            Class<? extends T> type) throws Exception {
        String body = sendRequest(HttpMethod.GET, selfLink, null);
        if (body == null || body.isEmpty()) {
            return null;
        }
        return Utils.fromJson(body, type);
    }

    public static enum TestDocumentLifeCycle {
        FOR_DELETE,
        FOR_DELETE_AFTER_CLASS,
        NO_DELETE
    }

    protected static <T extends ServiceDocument> T postDocument(String fabricLink, T document)
            throws Exception {
        return postDocument(fabricLink, document, TestDocumentLifeCycle.FOR_DELETE);
    }

    protected static <T extends ServiceDocument> T postDocument(String fabricLink, T document,
            Map<String, String> headers) throws Exception {
        return postDocument(fabricLink, document, TestDocumentLifeCycle.FOR_DELETE, headers);
    }

    protected static <T extends ServiceDocument> T postDocument(String fabricLink, T document,
            TestDocumentLifeCycle documentLifeCycle) throws Exception {
        return postDocument(fabricLink, document, documentLifeCycle, Collections.emptyMap());
    }

    @SuppressWarnings("unchecked")
    protected static <T extends ServiceDocument> T postDocument(String fabricLink, T document,
            TestDocumentLifeCycle documentLifeCycle, Map<String, String> headers) throws Exception {
        if (document.documentSelfLink != null && !document.documentSelfLink.isEmpty()) {
            String servicePathUrl = buildServiceUri(fabricLink,
                    extractId(document.documentSelfLink));
            String body = sendRequest(HttpMethod.GET, servicePathUrl, null);
            if (body != null && !body.isEmpty()) {
                delete(servicePathUrl);
            }
        }
        String body = sendRequest(HttpMethod.POST, fabricLink, Utils.toJson(document), headers);
        if (body == null || body.isEmpty()) {
            return null;
        }
        T doc = (T) Utils.fromJson(body, document.getClass());
        switch (documentLifeCycle) {
        case FOR_DELETE:
            cleanUpAfter(doc);
            break;
        case FOR_DELETE_AFTER_CLASS:
            cleanUpAfterClass(doc);
            break;
        default:
            break;
        }
        return doc;
    }

    protected static ComputeState addHost(ComputeState computeState) throws Exception {
        String documentSelfLink = null;
        if (computeState.id != null) {
            documentSelfLink = buildServiceUri(ComputeService.FACTORY_LINK,
                    computeState.id);
            String body = sendRequest(HttpMethod.GET, documentSelfLink, null);
            if (body != null && !body.isEmpty()) {
                delete(documentSelfLink);
            }
        }

        ContainerHostSpec hostSpec = new ContainerHostSpec();
        hostSpec.hostState = computeState;
        hostSpec.acceptCertificate = true;

        ContainerListCallback body = new ContainerListCallback();
        body.containerHostLink = documentSelfLink;
        body.unlockDataCollectionForHost = true;
        // TODO remove when the issue with the locked data collection is fixed
        SimpleHttpsClient.execute(HttpMethod.PATCH,
                getBaseUrl() + HostContainerListDataCollection
                        .DEFAULT_HOST_CONTAINER_LIST_DATA_COLLECTION_LINK,
                Utils.toJson(body));
        HttpResponse httpResponse = SimpleHttpsClient.execute(HttpMethod.PUT,
                getBaseUrl() + buildServiceUri(ContainerHostService.SELF_LINK),
                Utils.toJson(hostSpec));

        if (HttpURLConnection.HTTP_NO_CONTENT != httpResponse.statusCode) {
            throw new IllegalArgumentException("Add host failed with status code: "
                    + httpResponse.statusCode);
        }

        List<String> headers = httpResponse.headers.get(Operation.LOCATION_HEADER);
        String computeStateLink = headers.get(0);

        waitForStateChange(
                computeStateLink,
                t -> {
                    ComputeStateWithDescription host = Utils.fromJson(t,
                            ComputeStateWithDescription.class);
                    return PowerState.ON == host.powerState;
                });

        return getDocument(computeStateLink, ComputeState.class);
    }

    protected void removeHost(ComputeState computeState) throws Exception {
        logger.info("---------- Request remove host. --------");
        if (computeState != null && computeState.id != null) {
            String documentSelfLink = buildServiceUri(ComputeService.FACTORY_LINK,
                    computeState.id);
            String body = sendRequest(HttpMethod.GET, documentSelfLink, null);
            if (body != null && !body.isEmpty()) {
                // host is found, remove it
                ContainerHostRemovalTaskState state = new ContainerHostRemovalTaskState();
                state.resourceLinks = Collections.singleton(computeState.documentSelfLink);
                state = postDocument(ContainerHostRemovalTaskFactoryService.SELF_LINK, state);

                assertNotNull("task is null", state);
                String taskSelfLink = state.documentSelfLink;
                assertNotNull("task self link is missing", taskSelfLink);
                waitForTaskToComplete(taskSelfLink);
            } else {
                logger.info("Docker host not found. Skipping removal");
            }
        }
    }

    protected static String sendRequest(HttpMethod method, String link, String body)
            throws Exception {
        return sendRequest(method, link, body, Collections.emptyMap());
    }

    protected static String sendRequest(HttpMethod method, String link, String body,
            Map<String, String> headers) throws Exception {
        HttpResponse httpResponse = SimpleHttpsClient.execute(method,
                getBaseUrl() + buildServiceUri(link), body, headers, null);
        if (httpResponse.responseBody == null) {
            Utils.logWarning("Body for method %s and link: %s is null. Status code: %s", method,
                    link, httpResponse.statusCode);
        }
        return httpResponse.responseBody;
    }

    protected void waitForTaskToComplete(final String documentSelfLink) throws Exception {
        String propName = TaskServiceDocument.FIELD_NAME_TASK_SUB_STAGE;
        String successPropValue = DefaultSubStage.COMPLETED.name();
        String errorPropValue = DefaultSubStage.ERROR.name();

        String body = null;
        boolean error = false;
        for (int i = 0; i < TASK_CHANGE_WAIT_POLLING_RETRY_COUNT; i++) {
            Thread.sleep(STATE_CHANGE_WAIT_POLLING_PERIOD_MILLIS);
            body = sendRequest(HttpMethod.GET, documentSelfLink, null);
            String value = body != null ? getJsonValue(body, propName) : null;
            if (successPropValue.equals(value)) {
                return;
            }

            RequestStatus requestStatus = getDocument(
                    UriUtils.buildUriPath(RequestStatusFactoryService.SELF_LINK,
                            extractId(documentSelfLink)),
                    RequestStatus.class);
            if (requestStatus != null) {
                logger.info("~~~~~~~~~ Request %s status progress: %s."
                                + " Progress by component: %s   ~~~~~~~",
                        documentSelfLink, requestStatus.progress,
                        requestStatus.requestProgressByComponent);
            }

            if (errorPropValue.equals(value)) {
                error = true;
                break;
            }
        }

        String failure = "";
        if (body != null) {
            // get the failure message to help in debugging test failures
            TaskState taskInfo = Utils.getJsonMapValue(body,
                    TaskServiceDocument.FIELD_NAME_TASK_INFO, TaskState.class);
            if (taskInfo != null && taskInfo.failure != null) {
                failure = "failure: " + taskInfo.failure.message;
            }
        }

        if (error) {
            throw new IllegalStateException(String.format(
                    "Failed with Error waiting for %s to transition to %s. Failure: %s",
                    documentSelfLink, successPropValue, failure));
        } else {
            throw new RuntimeException(String.format(
                    "Failed waiting for %s to transition to %s %s", documentSelfLink,
                    successPropValue, failure));
        }
    }

    protected static void waitForStateChange(final String documentSelfLink,
            Predicate<String> predicate) throws Exception {

        String body = null;
        for (int i = 0; i < STATE_CHANGE_WAIT_POLLING_RETRY_COUNT; i++) {
            Thread.sleep(STATE_CHANGE_WAIT_POLLING_PERIOD_MILLIS);
            body = sendRequest(HttpMethod.GET, documentSelfLink, null);
            if (predicate.test(body)) {
                return;
            }
        }

        throw new RuntimeException("Failed waiting for state change");
    }

    protected static void waitFor(Predicate<Void> predicate) throws Exception {
        for (int i = 0; i < STATE_CHANGE_WAIT_POLLING_RETRY_COUNT; i++) {
            Thread.sleep(STATE_CHANGE_WAIT_POLLING_PERIOD_MILLIS);
            if (predicate.test(null)) {
                return;
            }
        }

        throw new RuntimeException("Failed waiting");
    }

    protected static void waitForStatusCode(URI uri, int expectedStatusCode) throws Exception {
        waitForStatusCode(uri, expectedStatusCode, STATE_CHANGE_WAIT_POLLING_RETRY_COUNT);
    }

    protected static void waitForStatusCode(URI uri, int expectedStatusCode, int count)
            throws Exception {
        waitForStatusCode(uri, Collections.emptyMap(), expectedStatusCode, count);
    }

    protected static void waitForStatusCode(URI uri, Map<String, String> headers,
            int expectedStatusCode, int count)
            throws Exception {

        long toFinish = System.currentTimeMillis()
                + STATE_CHANGE_WAIT_POLLING_PERIOD_MILLIS * count;

        while (System.currentTimeMillis() <= toFinish) {
            try {
                HttpResponse httpResponse = SimpleHttpsClient.execute(HttpMethod.GET,
                        uri.toString(), null, headers, getUnsecuredSSLSocketFactory());
                if (expectedStatusCode == httpResponse.statusCode) {
                    return;
                } else {
                    logger.info("Expected status code %s but got %s. Will retry. Response body: %s",
                            expectedStatusCode, httpResponse.statusCode, httpResponse.responseBody);
                }
            } catch (Exception x) {
                logger.info(
                        "An exception was thrown while waiting for status code [%s] for uri [%s]. Will retry. Exception message: %s",
                        expectedStatusCode, uri.toString(), x.getMessage());
                // failed - keep waiting
            }
            Thread.sleep(STATE_CHANGE_WAIT_POLLING_PERIOD_MILLIS);
        }

        throw new RuntimeException(String.format(
                "Failed waiting for status code %s at %s", expectedStatusCode, uri));
    }

    protected static String getJsonValue(String body, String fieldName) {
        JsonObject jo = new JsonParser().parse(body).getAsJsonObject();
        JsonElement je = jo.get(fieldName);
        if (je == null) {
            return null;
        }

        return je.getAsString();
    }

    protected static String extractId(String link) {
        AssertUtil.assertNotNull(link, "link");
        if (link.endsWith(UriUtils.URI_PATH_CHAR)) {
            link = link.substring(0, link.length() - 1);
        }
        return link.substring(link.lastIndexOf(UriUtils.URI_PATH_CHAR) + 1);
    }

    /**
     * Get a property designated by path from a root object
     * <p/>
     * This implementation assumes the object is a nested map of maps until the leaf object is found
     *
     * @param object
     * @param propertyPathElements
     * @return
     */
    @SuppressWarnings("unchecked")
    protected <T> T getNestedPropertyByPath(Object object, String... propertyPathElements) {
        List<String> pathParts = new LinkedList<>(Arrays.asList(propertyPathElements));

        while ((object instanceof Map) && (!pathParts.isEmpty())) {
            String pathPart = pathParts.remove(0);

            Map<String, Object> map = (Map<String, Object>) object;
            object = map.get(pathPart);
        }

        return (T) object;
    }

    protected String getResourceContaining(Collection<String> links, String pattern) {
        for (String resourceLink : links) {
            if (resourceLink.contains(pattern)) {
                return resourceLink;
            }
        }

        return null;
    }

    protected static SSLSocketFactory getUnsecuredSSLSocketFactory()
            throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] { UnsecuredX509TrustManager.getInstance() }, null);
        return context.getSocketFactory();
    }

    private static class UnsecuredX509TrustManager implements X509TrustManager {

        private static UnsecuredX509TrustManager instance;

        private UnsecuredX509TrustManager() {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return null;
        }

        public static UnsecuredX509TrustManager getInstance() {
            if (instance == null) {
                instance = new UnsecuredX509TrustManager();
            }

            return instance;
        }
    }

    private static void deleteDocuments(Queue<ServiceDocument> list) {
        ServiceDocument docToDelete = null;
        LinkedList<ServiceDocument> deleteAgain = new LinkedList<>();
        while (!list.isEmpty()) {
            try {
                docToDelete = list.poll();
                logger.info("Deleting document: %s", docToDelete.documentSelfLink);
                delete(docToDelete);
            } catch (Exception e) {
                e.printStackTrace();
                deleteAgain.add(docToDelete);
            }
        }
        // retry deleting states that failed the first time, so naively try to clean resources with
        // dependencies, e.g. credentials cannot be deleted if they are used by compute state
        // sleep for 6 seconds before retrying, as the removal of expired states in xenon is
        // 5 times the default maintenance interval (1s)
        if (!deleteAgain.isEmpty()) {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(6));
            } catch (InterruptedException ignore) {
            }
            while (!deleteAgain.isEmpty()) {
                try {
                    docToDelete = deleteAgain.poll();
                    logger.info("Retry deleting document: %s", docToDelete.documentSelfLink);
                    delete(docToDelete);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void setBaseURI(String baseURI) {
        BaseIntegrationSupportIT.baseURI = baseURI;
    }
}
