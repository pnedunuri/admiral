/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

package com.vmware.admiral.closures.drivers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

import com.vmware.admiral.closures.drivers.nashorn.EmbeddedNashornJSDriver;
import com.vmware.admiral.closures.services.closure.Closure;
import com.vmware.admiral.closures.services.closure.ClosureFactoryService;
import com.vmware.admiral.closures.services.closuredescription.ClosureDescription;
import com.vmware.admiral.closures.services.closuredescription.ClosureDescriptionFactoryService;
import com.vmware.admiral.closures.services.closuredescription.ResourceConstraints;
import com.vmware.xenon.common.BasicReusableHostTestCase;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.TaskState.TaskStage;
import com.vmware.xenon.common.UriUtils;

public class ClosureServiceTest extends BasicReusableHostTestCase {

    private static final long TEST_TASK_MAINTANENACE_TIMEOUT_MLS = 5000;

    @Before
    public void setUp() throws Exception {
        try {
            if (this.host.getServiceStage(ClosureFactoryService.FACTORY_LINK) != null) {
                return;
            }

            DriverRegistry driverRegistry = new DriverRegistryImpl();
            driverRegistry.register(DriverConstants.RUNTIME_NASHORN, new EmbeddedNashornJSDriver(this.host));

            // Start a closure factory services
            this.host.startServiceAndWait(ClosureDescriptionFactoryService.class,
                    ClosureDescriptionFactoryService.FACTORY_LINK);

            ClosureFactoryService closureFactoryService = new ClosureFactoryService(driverRegistry,
                    TEST_TASK_MAINTANENACE_TIMEOUT_MLS * 1000);
            this.host.startServiceAndWait(closureFactoryService, ClosureFactoryService.FACTORY_LINK, null);

        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @AfterClass
    public static void clean() {
        BasicReusableHostTestCase.tearDownOnce();
    }

    @Test
    public void addDefaultTaskTest() throws Throwable {
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";
        taskDefState.source = "var a = 1; print(\"Hello \" + a);";
        taskDefState.runtime = "nashorn";
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> assertNull(e)));
        this.host.send(post);
        this.host.testWait();

        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void executeJSNumberParametersTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";

        int expectedInVar = 3;
        int expectedOutVar = 3;
        double expectedResult = 4.0;

        taskDefState.source = "function test(x) {print('Hello number: ' + x); return x + 1;} var b = " +
                expectedOutVar
                + "; result = test(inputs.a);";
        taskDefState.runtime = "nashorn";
        taskDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 10;
        taskDefState.resources = constraints;
        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);

                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Closure closureRequest = new Closure();
        Map inputs = new HashMap<>();
        inputs.put("a", new JsonPrimitive(expectedInVar));
        closureRequest.inputs = inputs;
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(closureRequest)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        Thread.sleep(taskDefState.resources.timeoutSeconds * 1000 + TEST_TASK_MAINTANENACE_TIMEOUT_MLS);

        final Closure[] finalClosureResponse = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    finalClosureResponse[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, finalClosureResponse[0].descriptionLink);
                    assertEquals(TaskStage.FINISHED, finalClosureResponse[0].state);

                    assertEquals(expectedInVar, finalClosureResponse[0].inputs.get("a").getAsInt());
                    assertEquals(expectedResult, finalClosureResponse[0].outputs.get("result").getAsDouble(), 0);
                }));
        this.host.send(taskGet);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void executeJSArrayOfNumberParametersTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";

        Integer[] expectedInVar = { 1, 2, 3 };
        Integer expectedOutVar = 1;
        Integer[] expectedResult = { 2, 3, 4 };

        taskDefState.source = "function increment(x) {" + "print('Hello array of numbers: ' + x);"
                + "for(var i = 0; i < x.length; i++) {" + "x[i] = x[i] + 1;" + "}" + " return x;}" + " var b = ["
                + expectedOutVar + "]; result = increment(inputs.a);";
        taskDefState.runtime = "nashorn";
        taskDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 2;
        taskDefState.resources = constraints;
        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Closure closureRequest = new Closure();
        Map inputs = new HashMap<>();

        JsonArray jsArray = new JsonArray();
        for (int x : expectedInVar) {
            jsArray.add(new JsonPrimitive(x));
        }
        inputs.put("a", jsArray);
        closureRequest.inputs = inputs;
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(closureRequest)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        Thread.sleep(taskDefState.resources.timeoutSeconds * TEST_TASK_MAINTANENACE_TIMEOUT_MLS);

        final Closure[] finalClosureResponse = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    finalClosureResponse[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, finalClosureResponse[0].descriptionLink);
                    assertEquals(TaskStage.FINISHED, finalClosureResponse[0].state);

                    verifyJsonArrayInts(expectedInVar, finalClosureResponse[0].inputs.get("a")
                            .getAsJsonArray());
                    verifyJsonArrayInts(expectedResult, finalClosureResponse[0].outputs.get("result").getAsJsonArray());
                }));
        this.host.send(taskGet);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void executeJSArrayOfStringParametersTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";

        String[] expectedInVar = { "a", "b", "c" };
        String expectedOutVar = "test";
        String[] expectedResult = { "a_t", "b_t", "c_t" };

        taskDefState.source = "function appnd(x) {" + "print('Hello array of strings: ' + x);"
                + "for(var i = 0; i < x.length; i++) {" + "x[i] = x[i] + '_t';" + "}"
                + "print('Hello number: ' + x); return x;}" + " var b = ['" + expectedOutVar
                + "']; result = appnd(inputs.a);";
        taskDefState.runtime = "nashorn";
        taskDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 4;
        taskDefState.resources = constraints;

        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Closure closureRequest = new Closure();
        Map inputs = new HashMap<>();

        JsonArray jsArray = new JsonArray();
        for (String x : expectedInVar) {
            jsArray.add(new JsonPrimitive(x));
        }
        inputs.put("a", jsArray);
        closureRequest.inputs = inputs;
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(closureRequest)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        Thread.sleep(taskDefState.resources.timeoutSeconds * TEST_TASK_MAINTANENACE_TIMEOUT_MLS);

        final Closure[] finalClosureResponse = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    finalClosureResponse[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, finalClosureResponse[0].descriptionLink);
                    assertEquals(TaskStage.FINISHED, finalClosureResponse[0].state);

                    verifyJsonArrayStrings(expectedInVar, finalClosureResponse[0].inputs.get("a")
                            .getAsJsonArray());
                    verifyJsonArrayStrings(expectedResult,
                            finalClosureResponse[0].outputs.get("result").getAsJsonArray());
                }));
        this.host.send(taskGet);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void executeJSArrayOfBooleanParametersTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";

        Boolean[] expectedInVar = { true, true, true };
        Boolean expectedOutVar = true;
        Boolean[] expectedResult = { false, false, false };

        taskDefState.source = "function appl(x) {" + "print('Hello array of booleans: ' + x);"
                + "for(var i = 0; i < x.length; i++) {" + "x[i] = !x[i];" + "}" + "return x;}" + " var b = ["
                + expectedOutVar + "]; result = appl(inputs.a);";
        taskDefState.runtime = "nashorn";
        taskDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 1;
        taskDefState.resources = constraints;

        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();

        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Closure closureRequest = new Closure();
        Map inputs = new HashMap<>();

        JsonArray jsArray = new JsonArray();
        for (Boolean x : expectedInVar) {
            jsArray.add(new JsonPrimitive(x));
        }
        inputs.put("a", jsArray);
        closureRequest.inputs = inputs;
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(closureRequest)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        Thread.sleep(taskDefState.resources.timeoutSeconds * 1000 + TEST_TASK_MAINTANENACE_TIMEOUT_MLS);

        final Closure[] finalClosureResponse = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    finalClosureResponse[0] = o.getBody(Closure.class);

                    assertEquals(closureState.descriptionLink, finalClosureResponse[0].descriptionLink);
                    assertEquals(TaskStage.FINISHED, finalClosureResponse[0].state);
                    verifyJsonArrayBooleans(expectedInVar, finalClosureResponse[0].inputs.get("a")
                            .getAsJsonArray());
                    verifyJsonArrayBooleans(expectedResult,
                            finalClosureResponse[0].outputs.get("result").getAsJsonArray());
                }));
        this.host.send(taskGet);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    class TestObject {
        public String strTest;
        public int intTest;
        public Boolean boolTest;
    }

    class NestedTestObject {
        public String strTest;
        public int intTest;
        public Boolean boolTest;
        public NestedTestObject objTest;
    }

    @Test
    public void executeJSObjectParametersTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";

        TestObject expectedInVar = new TestObject();
        expectedInVar.strTest = "test";
        expectedInVar.intTest = 1;
        expectedInVar.boolTest = true;
        int expectedOutVar = 3;
        String expectedResult = expectedInVar.strTest + "_changed";

        taskDefState.source = "function test(x) {print('Hello object: ' + x.strTest);"
                + " x.strTest = x.strTest + '_changed';"
                + " x.intTest = x.intTest + 1; x.boolTest = !x.boolTest; return x;" + "}" + " var b = " + expectedOutVar
                + "; result = test(inputs.a);";
        taskDefState.runtime = "nashorn";
        taskDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 1;
        taskDefState.resources = constraints;

        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();

        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Closure closureRequest = new Closure();
        Map inputs = new HashMap<>();
        Gson gson = new Gson();
        inputs.put("a", gson.toJsonTree(expectedInVar));
        closureRequest.inputs = inputs;
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(closureRequest)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        Thread.sleep(taskDefState.resources.timeoutSeconds * 1000);

        final Closure[] finalClosureResponse = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    finalClosureResponse[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, finalClosureResponse[0].descriptionLink);
                    assertEquals(TaskStage.FINISHED, finalClosureResponse[0].state);
                    JsonObject inObj = finalClosureResponse[0].inputs.get("a").getAsJsonObject();

                    Gson json = new Gson();
                    TestObject deserialObj = json.fromJson(inObj, TestObject.class);

                    assertEquals(expectedInVar.strTest, deserialObj.strTest);
                    assertEquals(expectedInVar.intTest, deserialObj.intTest);
                    assertEquals(expectedInVar.boolTest, deserialObj.boolTest);

                    JsonObject jsonResultObj = finalClosureResponse[0].outputs.get("result").getAsJsonObject();
                    TestObject resultObj = json.fromJson(jsonResultObj, TestObject.class);

                    assertEquals(expectedResult, resultObj.strTest);
                    assertEquals(expectedInVar.intTest + 1, resultObj.intTest);
                    assertEquals(!expectedInVar.boolTest, resultObj.boolTest);
                }));
        this.host.send(taskGet);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void executeJSNestedObjectParametersTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";

        NestedTestObject expectedInVar = new NestedTestObject();
        expectedInVar.strTest = "test";
        expectedInVar.intTest = 1;
        expectedInVar.boolTest = true;
        expectedInVar.objTest = new NestedTestObject();
        expectedInVar.objTest.strTest = "child";
        expectedInVar.objTest.intTest = 1;
        expectedInVar.objTest.boolTest = true;

        int expectedOutVar = 3;
        String expectedResult = expectedInVar.objTest.strTest + "_changed";

        taskDefState.source = "function test(x) {print('Hello object: ' + x.objTest);"
                + " x.objTest.strTest = x.objTest.strTest + '_changed';"
                + " x.objTest.intTest = x.objTest.intTest + 1; x.objTest.boolTest = !x.objTest.boolTest; return x;"
                + "}" + " var b = " + expectedOutVar + "; result = test(inputs.a);";
        taskDefState.runtime = "nashorn";
        taskDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 2;
        taskDefState.resources = constraints;

        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();

        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Closure closureRequest = new Closure();
        Map inputs = new HashMap<>();
        Gson gson = new Gson();
        inputs.put("a", gson.toJsonTree(expectedInVar));
        closureRequest.inputs = inputs;
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(closureRequest)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        Thread.sleep(taskDefState.resources.timeoutSeconds * 1000);

        final Closure[] finalClosureResponse = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    finalClosureResponse[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, finalClosureResponse[0].descriptionLink);
                    assertEquals(TaskStage.FINISHED, finalClosureResponse[0].state);

                    JsonObject inObj = finalClosureResponse[0].inputs.get("a").getAsJsonObject().get
                            ("objTest")
                            .getAsJsonObject();

                    Gson json = new Gson();
                    NestedTestObject deserialObj = json.fromJson(inObj, NestedTestObject.class);
                    assertEquals(expectedInVar.objTest.strTest, deserialObj.strTest);
                    assertEquals(expectedInVar.objTest.intTest, deserialObj.intTest);
                    assertEquals(expectedInVar.objTest.boolTest, deserialObj.boolTest);

                    JsonObject jsonChild = finalClosureResponse[0].outputs.get("result").getAsJsonObject()
                            .get("objTest").getAsJsonObject();
                    NestedTestObject resultObj = json.fromJson(jsonChild, NestedTestObject.class);
                    assertEquals(expectedResult, resultObj.strTest);
                    assertEquals(expectedInVar.objTest.intTest + 1, resultObj.intTest);
                    assertEquals(!expectedInVar.objTest.boolTest, resultObj.boolTest);
                }));
        this.host.send(taskGet);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void executeJSArrayOfObjectParametersTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";

        TestObject expectedInVar = new TestObject();
        expectedInVar.strTest = "test";
        expectedInVar.intTest = 1;
        expectedInVar.boolTest = true;
        int expectedOutVar = 3;
        String expectedResult = expectedInVar.strTest + "_changed";

        taskDefState.source = "function test(x) { print('Hello object: ' + x[0].strTest);"
                + " x[0].strTest = x[0].strTest + '_changed';"
                + " x[0].intTest = x[0].intTest + 1; x[0].boolTest = !x[0].boolTest; return x;" + "}" + " var b = "
                + expectedOutVar + "; result = test(inputs.a);";
        taskDefState.runtime = "nashorn";
        taskDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 4;
        taskDefState.resources = constraints;

        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();

        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Closure closureRequest = new Closure();
        Map inputs = new HashMap<>();

        JsonArray jsArray = new JsonArray();
        jsArray.add(new Gson().toJsonTree(expectedInVar));
        inputs.put("a", jsArray);
        closureRequest.inputs = inputs;
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(closureRequest)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        Thread.sleep(taskDefState.resources.timeoutSeconds * 1000 + TEST_TASK_MAINTANENACE_TIMEOUT_MLS);

        final Closure[] finalClosureResponse = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    finalClosureResponse[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, finalClosureResponse[0].descriptionLink);
                    assertEquals(TaskStage.FINISHED, finalClosureResponse[0].state);
                    JsonObject inObj = finalClosureResponse[0].inputs.get("a").getAsJsonArray().get(0)
                            .getAsJsonObject();

                    Gson json = new Gson();
                    TestObject deserialObj = json.fromJson(inObj, TestObject.class);

                    assertEquals(expectedInVar.strTest, deserialObj.strTest);
                    assertEquals(expectedInVar.intTest, deserialObj.intTest);
                    assertEquals(expectedInVar.boolTest, deserialObj.boolTest);

                    JsonObject jsonResultObj = finalClosureResponse[0].outputs.get("result").getAsJsonArray().get(0)
                            .getAsJsonObject();
                    TestObject resultObj = json.fromJson(jsonResultObj, TestObject.class);

                    assertEquals(expectedResult, resultObj.strTest);
                    assertEquals(expectedInVar.intTest + 1, resultObj.intTest);
                    assertEquals(!expectedInVar.boolTest, resultObj.boolTest);
                }));
        this.host.send(taskGet);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void executeJSStringParametersTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";

        String expectedInVar = "a";
        String expectedOutVar = "b";
        String expectedResult = "ac";

        taskDefState.source = "function test(x) {print('Hello string: ' + x); return x.concat(\"c\");} var b = '"
                + expectedOutVar + "'; result = test(inputs.a);";
        taskDefState.runtime = "nashorn";
        taskDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 2;
        taskDefState.resources = constraints;

        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                    this.host.completeIteration();
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();

        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Closure closureRequest = new Closure();
        Map inputs = new HashMap<>();
        inputs.put("a", new JsonPrimitive(expectedInVar));
        closureRequest.inputs = inputs;
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(closureRequest)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        Thread.sleep(taskDefState.resources.timeoutSeconds * 1000 + TEST_TASK_MAINTANENACE_TIMEOUT_MLS);

        final Closure[] finalClosureResponse = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    finalClosureResponse[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, finalClosureResponse[0].descriptionLink);
                    assertEquals(TaskStage.FINISHED, finalClosureResponse[0].state);

                    assertEquals(expectedInVar,
                            finalClosureResponse[0].inputs.get("a").getAsString());
                    assertEquals(expectedResult, finalClosureResponse[0].outputs.get("result").getAsString());
                }));
        this.host.send(taskGet);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void executeJSBooleanParametersTest() throws Throwable {
        this.host.setTimeoutSeconds(1000);
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";

        boolean expectedInVar = true;
        int expectedOutVar = 1;
        boolean expectedResult = false;

        taskDefState.source = "function test(x) {print('Hello boolean: ' + x); return !x;} var b = " + expectedOutVar
                + "; result = test(inputs.a);";
        taskDefState.runtime = "nashorn";
        taskDefState.outputNames = new ArrayList<>(Collections.singletonList("result"));
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 1;
        taskDefState.resources = constraints;

        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                    this.host.completeIteration();
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();

        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Closure closureRequest = new Closure();
        Map inputs = new HashMap<>();
        inputs.put("a", new JsonPrimitive(expectedInVar));
        closureRequest.inputs = inputs;
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(closureRequest)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        Thread.sleep(taskDefState.resources.timeoutSeconds * 1000 + TEST_TASK_MAINTANENACE_TIMEOUT_MLS);

        final Closure[] finalClosureResponse = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    finalClosureResponse[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, finalClosureResponse[0].descriptionLink);
                    assertEquals(TaskStage.FINISHED, finalClosureResponse[0].state);

                    assertEquals(expectedInVar,
                            finalClosureResponse[0].inputs.get("a").getAsBoolean());
                    assertEquals(expectedResult, finalClosureResponse[0].outputs.get("result").getAsBoolean());
                }));
        this.host.send(taskGet);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void executeInvalidJSScriptTaskTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";
        taskDefState.source = "var a = 1; print(\"Hello \" + invalid);";
        taskDefState.runtime = "nashorn";
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 2;
        taskDefState.resources = constraints;

        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();
        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();

        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(new Closure())
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        Thread.sleep(taskDefState.resources.timeoutSeconds * 1000);

        final Closure[] endStateClosureResponses = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    endStateClosureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink,
                            endStateClosureResponses[0].descriptionLink);
                    assertEquals(TaskStage.FAILED, endStateClosureResponses[0].state);
                    assertTrue(endStateClosureResponses[0].errorMsg.length() > 0);
                }));
        this.host.send(taskGet);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void executeTimeoutedJSScriptTaskTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";
        taskDefState.source = "function sleep(delay) {var start = new Date().getTime();while (new Date().getTime() < start + delay) {}} sleep(60000);";
        taskDefState.runtime = "nashorn";
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 1;
        taskDefState.resources = constraints;

        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();

        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(new Closure())
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        Thread.sleep(
                (taskDefState.resources.timeoutSeconds * 1000) + TEST_TASK_MAINTANENACE_TIMEOUT_MLS);

        final Closure[] endStateClosureResponses = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    endStateClosureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink,
                            endStateClosureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CANCELLED, endStateClosureResponses[0].state);
                }));
        this.host.send(taskGet);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void completeFailTimeoutedJSScriptTaskTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";
        taskDefState.source = "function sleep(delay) {var start = new Date().getTime();while (new Date().getTime() < start + delay) {}} sleep(60000);";
        taskDefState.runtime = "nashorn";
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 1;
        taskDefState.resources = constraints;

        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();

        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(new Closure())
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        Thread.sleep(
                (taskDefState.resources.timeoutSeconds * 1000) + TEST_TASK_MAINTANENACE_TIMEOUT_MLS);

        final Closure[] endStateClosureResponses = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    endStateClosureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink,
                            endStateClosureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CANCELLED, endStateClosureResponses[0].state);
                }));
        this.host.send(taskGet);
        this.host.testWait();

        // Try to complete already cancelled Closure
        endStateClosureResponses[0].state = TaskStage.FINISHED;
        this.host.testStart(1);
        post = Operation
                .createPatch(factoryUri)
                .setBody(endStateClosureResponses[0])
                .setCompletion(BasicReusableHostTestCase.getSafeHandler(
                        (o, e) -> assertNotNull("Closure is not allowed to complete once it is cancelled", e)));
        this.host.send(post);
        this.host.testWait();

        // Try to fail already cancelled Closure
        endStateClosureResponses[0].state = TaskStage.FAILED;
        this.host.testStart(1);
        post = Operation
                .createPatch(factoryUri)
                .setBody(endStateClosureResponses[0])
                .setCompletion(BasicReusableHostTestCase.getSafeHandler(
                        (o, e) -> assertNotNull("Closure is not allowed to fail once it is cancelled", e)));
        this.host.send(post);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void completeOrFailOutdatedJSScriptTaskTest() throws Throwable {
        // Create Closure Definition
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureDescriptionFactoryService.class);
        this.host.testStart(1);
        ClosureDescription taskDefState = new ClosureDescription();
        taskDefState.name = "test";
        taskDefState.source = "function sleep(delay) {var start = new Date().getTime();while (new Date().getTime() < start + delay) {}} sleep(60000);";
        taskDefState.runtime = "nashorn";
        taskDefState.documentSelfLink = UUID.randomUUID().toString();
        ResourceConstraints constraints = new ResourceConstraints();
        constraints.timeoutSeconds = 1;
        taskDefState.resources = constraints;

        ClosureDescription[] responses = new ClosureDescription[1];
        URI taskDefChildURI = UriUtils.buildUri(this.host,
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink);
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(taskDefState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    assertNull(e);
                    responses[0] = o.getBody(ClosureDescription.class);
                    assertNotNull(responses[0]);
                }));
        this.host.send(post);
        this.host.testWait();

        // Create Closure
        URI factoryTaskUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure closureState = new Closure();

        closureState.descriptionLink =
                ClosureDescriptionFactoryService.FACTORY_LINK + "/" + taskDefState.documentSelfLink;
        closureState.documentSelfLink = UUID.randomUUID().toString();
        URI taskChildURI = UriUtils.buildUri(this.host,
                ClosureFactoryService.FACTORY_LINK + "/" + closureState.documentSelfLink);
        final Closure[] closureResponses = new Closure[1];
        Operation taskPost = Operation
                .createPost(factoryTaskUri)
                .setBody(closureState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink, closureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CREATED, closureResponses[0].state);
                }));
        this.host.send(taskPost);
        this.host.testWait();

        // Executing the created Closure
        this.host.testStart(1);
        Operation taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(new Closure())
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Wait for the completion timeout
        this.host.log("Waiting for: "
                + taskDefState.resources.timeoutSeconds * TEST_TASK_MAINTANENACE_TIMEOUT_MLS);
        Thread.sleep(
                (taskDefState.resources.timeoutSeconds * 1000) + TEST_TASK_MAINTANENACE_TIMEOUT_MLS);

        final Closure[] endStateClosureResponses = new Closure[1];
        this.host.testStart(1);
        Operation taskGet = Operation
                .createGet(taskChildURI)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    endStateClosureResponses[0] = o.getBody(Closure.class);
                    assertEquals(closureState.descriptionLink,
                            endStateClosureResponses[0].descriptionLink);
                    assertEquals(TaskStage.CANCELLED, endStateClosureResponses[0].state);
                }));
        this.host.send(taskGet);
        this.host.testWait();

        // Request bring new execution of the created Closure.
        this.host.testStart(1);
        taskExecPost = Operation
                .createPost(taskChildURI)
                .setBody(new Closure())
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> {
                    closureResponses[0] = o.getBody(Closure.class);
                    assertNotNull(closureResponses[0]);
                }));
        this.host.send(taskExecPost);
        this.host.testWait();

        // Try to complete outdated Closure
        endStateClosureResponses[0].state = TaskStage.FINISHED;
        this.host.testStart(1);
        post = Operation
                .createPatch(factoryUri)
                .setBody(endStateClosureResponses[0])
                .setCompletion(BasicReusableHostTestCase.getSafeHandler(
                        (o, e) -> assertNotNull("Closure is not allowed to complete once it is CANCELLED", e)));
        this.host.send(post);
        this.host.testWait();

        // Try to fail outdated cancelled Closure
        endStateClosureResponses[0].state = TaskStage.FAILED;
        this.host.testStart(1);
        post = Operation
                .createPatch(factoryUri)
                .setBody(endStateClosureResponses[0])
                .setCompletion(BasicReusableHostTestCase.getSafeHandler(
                        (o, e) -> assertNotNull("Closure is not allowed to fail once it is CANCELLED", e)));
        this.host.send(post);
        this.host.testWait();

        clean(taskDefChildURI);
        clean(taskChildURI);
    }

    @Test
    public void invalidNegativeTest() throws Throwable {
        URI factoryUri = UriUtils.buildFactoryUri(this.host, ClosureFactoryService.class);
        this.host.testStart(1);
        Closure initialState = new Closure();
        initialState.documentSelfLink = UUID.randomUUID().toString();
        Operation post = Operation
                .createPost(factoryUri)
                .setBody(initialState)
                .setCompletion(BasicReusableHostTestCase.getSafeHandler((o, e) -> assertNotNull(e)));
        this.host.send(post);
        this.host.testWait();
    }

    // HELPER METHODS

    private void verifyJsonArrayStrings(Object[] javaArray, JsonArray jsArray) {
        assertEquals(javaArray.length, jsArray.size());
        for (int i = 0; i < javaArray.length; i++) {
            assertEquals(javaArray[i], jsArray.get(i).getAsString());
        }
    }

    private void verifyJsonArrayInts(Object[] javaArray, JsonArray jsArray) {
        assertEquals(javaArray.length, jsArray.size());
        for (int i = 0; i < javaArray.length; i++) {
            assertEquals(javaArray[i], jsArray.get(i).getAsInt());
        }
    }

    private void verifyJsonArrayBooleans(Object[] javaArray, JsonArray jsArray) {
        assertEquals(javaArray.length, jsArray.size());
        for (int i = 0; i < javaArray.length; i++) {
            assertEquals(javaArray[i], jsArray.get(i).getAsBoolean());
        }
    }

    private void clean(URI childURI) throws Throwable {
        this.host.testStart(1);
        Operation delete = Operation
                .createDelete(childURI)
                .setCompletion(BasicReusableHostTestCase
                        .getSafeHandler((o, e) -> assertNull("Unable to clean document: " + childURI, e)));
        this.host.send(delete);
        this.host.testWait();
    }
}
