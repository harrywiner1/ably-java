package io.ably.lib.test.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.test.AndroidTestCase;

import junit.extensions.TestSetup;
import junit.framework.TestSuite;

import junit.framework.Test;

import org.junit.After;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import io.ably.lib.debug.DebugOptions;
import io.ably.lib.rest.AblyRest;
import io.ably.lib.rest.Auth;
import io.ably.lib.rest.Channel;
import io.ably.lib.rest.DeviceDetails;
import io.ably.lib.rest.LocalDevice;
import io.ably.lib.rest.Push;
import io.ably.lib.rest.Push.ActivationStateMachine.AfterRegistrationUpdateFailed;
import io.ably.lib.rest.Push.ActivationStateMachine.CalledActivate;
import io.ably.lib.rest.Push.ActivationStateMachine.CalledDeactivate;
import io.ably.lib.rest.Push.ActivationStateMachine.Deregistered;
import io.ably.lib.rest.Push.ActivationStateMachine.DeregistrationFailed;
import io.ably.lib.rest.Push.ActivationStateMachine.Event;
import io.ably.lib.rest.Push.ActivationStateMachine.GettingUpdateTokenFailed;
import io.ably.lib.rest.Push.ActivationStateMachine.GotPushDeviceDetails;
import io.ably.lib.rest.Push.ActivationStateMachine.GotUpdateToken;
import io.ably.lib.rest.Push.ActivationStateMachine.NotActivated;
import io.ably.lib.rest.Push.ActivationStateMachine.RegistrationUpdated;
import io.ably.lib.rest.Push.ActivationStateMachine.State;
import io.ably.lib.rest.Push.ActivationStateMachine.UpdatingRegistrationFailed;
import io.ably.lib.rest.Push.ActivationStateMachine.WaitingForDeregistration;
import io.ably.lib.rest.Push.ActivationStateMachine.WaitingForNewPushDeviceDetails;
import io.ably.lib.rest.Push.ActivationStateMachine.WaitingForPushDeviceDetails;
import io.ably.lib.rest.Push.ActivationStateMachine.WaitingForRegistrationUpdate;
import io.ably.lib.rest.Push.ActivationStateMachine.WaitingForUpdateToken;
import io.ably.lib.rest.PushBase;
import io.ably.lib.test.common.Helpers;
import io.ably.lib.test.common.Helpers.AsyncWaiter;
import io.ably.lib.test.common.Helpers.CompletionWaiter;
import io.ably.lib.test.common.Setup;
import io.ably.lib.test.util.TestCases;
import io.ably.lib.types.AblyException;
import io.ably.lib.types.ClientOptions;
import io.ably.lib.types.ErrorInfo;
import io.ably.lib.types.Function;
import io.ably.lib.types.Param;
import io.ably.lib.types.RegistrationToken;
import io.ably.lib.util.IntentUtils;
import io.ably.lib.util.JsonUtils;
import io.ably.lib.util.Log;
import io.ably.lib.util.Serialisation;

import static io.ably.lib.test.common.Helpers.assertArrayUnorderedEquals;
import static io.ably.lib.test.common.Helpers.assertInstanceOf;
import static io.ably.lib.test.common.Helpers.assertSize;

public class AndroidPushTest extends AndroidTestCase {
    private static AblyRest rest;
    private static Helpers.RawHttpTracker httpTracker;
    private static TestActivationStateMachine machine;

    public static Test suite() {
        TestSuite suite = new TestSuite();
        suite.addTest(new TestSetup(new TestSuite(AndroidPushTest.class)) {
            protected void setUp() throws Exception {
                setUpBeforeClass();
            }
            protected void tearDown() throws Exception {
                tearDownAfterClass();
            }
        });
        return suite;
    }

    public void setUp() throws Exception {
        httpTracker = new Helpers.RawHttpTracker();
        DebugOptions options = createOptions(testVars.keys[0].keyStr);
        options.httpListener = httpTracker;
        options.useTokenAuth = true;
        rest = new AblyRest(options);
        rest.auth.authorize(null, null);
        Push.ActivationStateMachine.INSTANCE = null;
        rest.push.getMachine = new Function.Binary<Context, AblyRest, Push.ActivationStateMachine>() {
            @Override
            public Push.ActivationStateMachine call(Context context, AblyRest rest) {
                return new TestActivationStateMachine(context, rest);
            }
        };
        machine = (TestActivationStateMachine) rest.push.getStateMachine(getContext());
        assertTrue(machine.reset());
    }

    // RSH2a
    public void test_push_activate() throws InterruptedException {
        BlockingQueue<Event> events = machine.getEventReceiver(1);
        assertInstanceOf(NotActivated.class, machine.current);
        rest.push.activate(getContext());
        Event event = events.take();
        assertInstanceOf(CalledActivate.class, event);
    }

    // RSH2b
    public void test_push_deactivate() throws InterruptedException {
        BlockingQueue<Event> events = machine.getEventReceiver(1);
        assertInstanceOf(NotActivated.class, machine.current);
        rest.push.deactivate(getContext());
        Event event = events.take();
        assertInstanceOf(CalledDeactivate.class, event);
    }

    // RSH2c
    public void test_push_onNewRegistrationToken() throws InterruptedException {
        BlockingQueue<Event> events = machine.getEventReceiver(1);
        rest.push.onNewRegistrationToken(getContext(), RegistrationToken.Type.GCM, "foo");
        Event event = events.take();
        assertInstanceOf(GotPushDeviceDetails.class, event);
    }

    // RSH3a1
    public void test_NotActivated_on_CalledDeactivate() {
        State state = new NotActivated(machine);

        final Helpers.AsyncWaiter<Intent> waiter = broadcastWaiter("PUSH_DEACTIVATE");

        State to = state.transition(new CalledDeactivate());

        // RSH3a1a
        waiter.waitFor();
        assertNull(waiter.error);

        // RSH3a1b
        assertInstanceOf(NotActivated.class, to);
    }

    // RSH3a2a
    public void test_NotActivated_on_CalledActivate_with_updateToken() throws InterruptedException {
        LocalDevice device = rest.device(getContext());
        device.setUpdateToken(getContext(), "foo");

        assertNotNull(device.id);
        assertEquals("foo", device.updateToken);

        State state = new NotActivated(machine);
        State to = state.transition(new CalledActivate());

        assertSize(1, machine.pendingEvents);
        assertInstanceOf(CalledActivate.class, machine.pendingEvents.getLast());
        
        assertInstanceOf(WaitingForNewPushDeviceDetails.class, to);
    }

    // RSH3a3a
    public void test_NotActivated_on_GotPushDeviceDetails() throws InterruptedException {
        State state = new NotActivated(machine);

        State to = state.transition(new GotPushDeviceDetails());

        assertSize(0, machine.pendingEvents);
        assertInstanceOf(NotActivated.class, to);
    }

    // RSH3a2b
    public void test_NotActivated_on_CalledActivate_with_registrationToken() throws InterruptedException {
        rest.push.onNewRegistrationToken(getContext(), RegistrationToken.Type.GCM, "testToken");

        State state = new NotActivated(machine);
        State to = state.transition(new CalledActivate());

        assertSize(1, machine.pendingEvents);
        assertInstanceOf(GotPushDeviceDetails.class, machine.pendingEvents.getLast());

        assertInstanceOf(WaitingForPushDeviceDetails.class, to);
    }

    // RSH3a2c
    public void test_NotActivated_on_CalledActivate_without_registrationToken() throws InterruptedException {
        State state = new NotActivated(machine);
        State to = state.transition(new CalledActivate());

        assertSize(0, machine.pendingEvents);

        assertInstanceOf(WaitingForPushDeviceDetails.class, to);
    }

    // RSH3b1
    public void test_WaitingForPushDeviceDetails_on_CalledActivate() {
        State state = new WaitingForPushDeviceDetails(machine);
        State to = state.transition(new CalledActivate());

        assertSize(0, machine.pendingEvents);

        // RSH3b1a
        assertInstanceOf(WaitingForPushDeviceDetails.class, to);
    }

    // RSH3b2
    public void test_WaitingForPushDeviceDetails_on_CalledDeactivate() {
        State state = new WaitingForPushDeviceDetails(machine);

        final Helpers.AsyncWaiter<Intent> waiter = broadcastWaiter("PUSH_DEACTIVATE");

        State to = state.transition(new CalledDeactivate());

        // RSH3b2a
        waiter.waitFor();
        assertNull(waiter.error);

        assertSize(0, machine.pendingEvents);

        // RSH3b2b
        assertInstanceOf(NotActivated.class, to);
    }

    // RSH3b3
    public void test_WaitingForPushDeviceDetails_on_GotPushDeviceDetails() throws Exception {
        class TestCase extends TestCases.Base {
            private final ErrorInfo registerError;
            private final boolean useCustomRegisterer;
            private final String updateToken;
            private final Class<? extends Event> expectedEvent;
            private final Class<? extends State> expectedState;

            public TestCase(String name, boolean useCustomRegisterer, ErrorInfo error, String updateToken, Class<? extends Event> expectedEvent, Class<? extends State> expectedState) {
                super(name, null);
                this.useCustomRegisterer = useCustomRegisterer;
                this.registerError = error;
                this.updateToken = updateToken;
                this.expectedEvent = expectedEvent;
                this.expectedState = expectedState;
            }

            @Override
            public void run() throws Exception {
                try {
                    machine.reset();

                    final Helpers.AsyncWaiter<Intent> registerCallback = useCustomRegisterer ? broadcastWaiter("PUSH_REGISTER_DEVICE") : null;
                    final Helpers.AsyncWaiter<Intent> activateCallback = broadcastWaiter("PUSH_ACTIVATE");

                    // Will move to WaitingForPushDeviceDetails.
                    rest.push.activate(getContext(), useCustomRegisterer);

                    CompletionWaiter handled = machine.getEventHandledWaiter(GotPushDeviceDetails.class);
                    Helpers.AsyncWaiter<Helpers.RawHttpRequest> requestWaiter = null;

                    if (!useCustomRegisterer) {
                        if (registerError != null) {
                            httpTracker.mockResponse = Helpers.httpResponseFromErrorInfo(registerError);
                        }

                        requestWaiter = httpTracker.getRequestWaiter();
                        // Block until we've checked the intermediate WaitingForUpdateToken state,
                        // before the request's response causes another state transition.
                        // Otherwise, our test would be racing against the request.
                        httpTracker.lockRequests();
                    }

                    // Will send GotPushDeviceDetails event.
                    rest.push.onNewRegistrationToken(getContext(), RegistrationToken.Type.GCM, "testToken");

                    handled.waitFor();

                    if (useCustomRegisterer) {
                        // RSH3b3a
                        registerCallback.waitFor();
                        assertNull(registerCallback.error);
                    } else {
                        // RSH3b3b
                        requestWaiter.waitFor();
                        Helpers.RawHttpRequest request = requestWaiter.result;
                        assertEquals("POST", request.method);
                        assertEquals("/push/deviceRegistrations", request.url.getPath());
                    }

                    // RSH3b3d
                    assertSize(0, machine.pendingEvents);
                    assertInstanceOf(WaitingForUpdateToken.class, machine.current);

                    // Now wait for next event, when we've got an updateToken or an error.
                    handled = machine.getEventHandledWaiter();
                    BlockingQueue<Event> events = machine.getEventReceiver(1);

                    if (useCustomRegisterer) {
                        Intent intent = new Intent();
                        if (registerError != null) {
                            IntentUtils.addErrorInfo(intent, registerError);
                        } else {
                            intent.putExtra("updateToken", updateToken);
                        }
                        sendBroadcast("PUSH_UPDATE_TOKEN", intent);
                    } else {
                        httpTracker.unlockRequests();
                    }

                    assertTrue(expectedEvent.isInstance(events.take()));
                    assertNull(handled.waitFor());

                    // RSH3c2a
                    if (useCustomRegisterer) {
                        assertEquals(updateToken, rest.device(getContext()).updateToken);
                    } else if (registerError == null) {
                        // No error expected, so updateToken should've been set by the server.
                        assertNotNull(rest.device(getContext()).updateToken);

                    }

                    // RSH3c2b, RSH3c3a
                    activateCallback.waitFor();
                    assertEquals(registerError, activateCallback.error);
                    assertTrue(expectedState.isInstance(machine.current));
                } finally {
                    httpTracker.unlockRequests();
                    rest.push.admin.deviceRegistrations.remove(rest.device(getContext()));
                }
            }
        }

        TestCases testCases = new TestCases();

        // RSH3c2
        testCases.add(new TestCase(
                "ok with custom registerer",
                true,
                null, "testUpdateToken",
                GotUpdateToken.class, // RSH3b3c
                WaitingForNewPushDeviceDetails.class /* RSH3c2c */));

        testCases.add(new TestCase(
                "ok with default registerer",
                false,
                null, "testUpdateToken",
                GotUpdateToken.class, // RSH3b3c
                WaitingForNewPushDeviceDetails.class /* RSH3c2c */));

        // RSH3c3
        testCases.add(new TestCase(
                "failing with custom registerer",
                true,
                new ErrorInfo("testError", 123), null,
                GettingUpdateTokenFailed.class, // RSH3b3c
                NotActivated.class /* RSH3c3b */));

        testCases.add(new TestCase(
                "failing with default registerer",
                false,
                new ErrorInfo("testError", 123), null,
                GettingUpdateTokenFailed.class, // RSH3b3c
                NotActivated.class /* RSH3c3b */));

        testCases.run();
    }

    // RSH3c1
    public void test_WaitingForUpdateToken_on_CalledActivate() {
        State state = new WaitingForUpdateToken(machine);
        State to = state.transition(new CalledActivate());

        assertSize(0, machine.pendingEvents);

        // RSH3c1a
        assertInstanceOf(WaitingForUpdateToken.class, to);
    }

    // RSH3d1
    public void test_WaitingForNewPushDeviceDetails_on_CalledActivate() {
        State state = new WaitingForNewPushDeviceDetails(machine);

        final Helpers.AsyncWaiter<Intent> waiter = broadcastWaiter("PUSH_ACTIVATE");

        State to = state.transition(new CalledActivate());

        // RSH3d1a
        waiter.waitFor();
        assertNull(waiter.error);

        assertSize(0, machine.pendingEvents);

        // RSH3d1b
        assertInstanceOf(WaitingForNewPushDeviceDetails.class, to);
    }

    // RSH3d2
    public void test_WaitingForNewPushDeviceDetails_on_CalledDeactivate() throws Exception {
        new DeactivateTest(WaitingForNewPushDeviceDetails.class) {
            @Override
            protected void setUpMachineState(TestCase testCase) {
                registerAndWait();
            }
        }.run();
    }

    // RSH3d3
    public void test_WaitingForNewPushDeviceDetails_on_GotPushDeviceDetails() throws Exception {
        new UpdateRegistrationTest() {
            @Override
            protected void setUpMachineState(TestCase testCase) {
                registerAndWait();
                rest.push.activate(getContext(), testCase.useCustomRegisterer);
            }
        }.run();
    }

    // RSH3e1
    public void test_WaitingForRegistrationUpdate_on_CalledActivate() {
        State state = new WaitingForRegistrationUpdate(machine);

        final AsyncWaiter<Intent> waiter = broadcastWaiter("PUSH_ACTIVATE");

        State to = state.transition(new CalledActivate());

        // RSH3e1a
        waiter.waitFor();
        assertNull(waiter.error);

        assertSize(0, machine.pendingEvents);

        // RSH3e1b
        assertInstanceOf(WaitingForRegistrationUpdate.class, to);
    }

    // RSH3e2
    public void test_WaitingForRegistrationUpdate_on_RegistrationUpdated() {
        State state = new WaitingForRegistrationUpdate(machine);

        State to = state.transition(new RegistrationUpdated());

        // RSH3e2a
        assertSize(0, machine.pendingEvents);
        assertInstanceOf(WaitingForNewPushDeviceDetails.class, to);
    }


    // RSH3e3
    public void test_WaitingForRegistrationUpdate_on_UpdatingRegistrationFailed() {
        State state = new WaitingForRegistrationUpdate(machine);
        ErrorInfo reason = new ErrorInfo("test", 123);

        final AsyncWaiter<Intent> waiter = broadcastWaiter("PUSH_UPDATE_FAILED");

        State to = state.transition(new UpdatingRegistrationFailed(reason));

        // RSH3e3a
        waiter.waitFor();
        assertNull(waiter.result);
        assertEquals(reason, waiter.error);

        assertSize(0, machine.pendingEvents);

        // RSH3e3b
        assertInstanceOf(AfterRegistrationUpdateFailed.class, to);
    }

    // RSH3f1
    public void test_AfterRegistrationUpdateFailed_on_GotPushDeviceDetails() throws Exception {
        new UpdateRegistrationTest() {
            @Override
            protected void setUpMachineState(TestCase testCase) {
                registerAndWait();
                rest.push.activate(getContext(), testCase.useCustomRegisterer);
                moveToAfterRegistrationUpdateFailed();
            }
        }.run();
    }

    // RSH3f1
    public void test_AfterRegistrationUpdateFailed_on_CalledActivate() throws Exception {
        new UpdateRegistrationTest() {
            @Override
            protected void setUpMachineState(TestCase testCase) {
                registerAndWait();
                moveToAfterRegistrationUpdateFailed();
            }

            @Override
            protected String sendInitialEvent(UpdateRegistrationTest.TestCase testCase) {
                rest.push.activate(getContext(), testCase.useCustomRegisterer);
                return "testTokenFailed";
            }
        }.run();
    }

    // RSH3f1
    public void test_AfterRegistrationUpdateFailed_on_CalledDeactivate() throws Exception {
        new DeactivateTest(AfterRegistrationUpdateFailed.class) {
            @Override
            protected void setUpMachineState(TestCase testCase) {
                registerAndWait();
                moveToAfterRegistrationUpdateFailed();
            }
        }.run();
    }

    // RSH3g1
    public void test_WaitingForDeregistration_on_CalledDeactivate() throws Exception {
        State state = new WaitingForDeregistration(machine, null);

        State to = state.transition(new CalledDeactivate());

        assertSize(0, machine.pendingEvents);
        assertInstanceOf(WaitingForDeregistration.class, to);
    }

    // RSH3g2
    public void test_WaitingForDeregistration_on_Deregistered() throws Exception {
        State state = new WaitingForDeregistration(machine, null);

        rest.device(getContext()).setUpdateToken(getContext(), "test");
        final Helpers.AsyncWaiter<Intent> waiter = broadcastWaiter("PUSH_DEACTIVATE");

        State to = state.transition(new Deregistered());

        // RSH3g2b
        waiter.waitFor();
        assertNull(waiter.error);

        // RSH3g2a
        assertNull(rest.device(getContext()).updateToken);

        // RSH3g2c
        assertSize(0, machine.pendingEvents);
        assertInstanceOf(NotActivated.class, to);
    }

    // RSH3g3
    public void test_WaitingForDeregistration_on_DeregistrationFailed() throws Exception {
        class TestCase extends TestCases.Base {
            private State previousState;

            public TestCase(String name, State previousState) {
                super(name, null);
                this.previousState = previousState;
            }

            @Override
            public void run() throws Exception {
                State state = new WaitingForDeregistration(machine, previousState);

                Helpers.AsyncWaiter<Intent> waiter = broadcastWaiter("PUSH_DEACTIVATE");
                ErrorInfo reason = new ErrorInfo("test", 123);

                State to = state.transition(new DeregistrationFailed(reason));

                // RSH3g3a
                waiter.waitFor();
                assertEquals(reason, waiter.error);

                // RSH3g3b
                assertSize(0, machine.pendingEvents);
                assertInstanceOf(previousState.getClass(), to);
            }
        }

        TestCases testCases = new TestCases();

        testCases.add(new TestCase(
                "from WaitingForNewPushDeviceDetails",
                new WaitingForNewPushDeviceDetails(machine)));

        testCases.add(new TestCase(
                "from AfterRegistrationUpdateFailed",
                new AfterRegistrationUpdateFailed(machine)));

        testCases.run();
    }

    // RSH4a1
    public void test_PushChannel_subscribeDevice_not_registered() throws AblyException {
        Channel channel = rest.channels.get("pushenabled:foo");
        PushBase.ChannelSubscription sub = PushBase.ChannelSubscription.forDevice(channel.name, rest.device(getContext()).id);

        try {
            channel.push.subscribeDevice(getContext());
            fail("expected failure due to device not being registered");
        } catch (AblyException e) {
        } finally {
            rest.push.admin.channelSubscriptions.remove(sub);
        }
    }

    // RSH4a2
    public void test_PushChannel_subscribeDevice_ok() throws AblyException {
        Channel channel = rest.channels.get("pushenabled:foo");
        PushBase.ChannelSubscription sub = PushBase.ChannelSubscription.forDevice(channel.name, rest.device(getContext()).id);

        try {
            registerAndWait();

            channel.push.subscribeDevice(getContext());

            PushBase.ChannelSubscription[] items = rest.push.admin.channelSubscriptions.list(new Param[]{
                    new Param("channel", channel.name),
                    new Param("deviceId", sub.deviceId),
                    new Param("fullWait", "true"),
            }).items();
            assertSize(1, items);
            assertEquals(items[0], sub);
        } finally {
            rest.push.admin.channelSubscriptions.remove(sub);
        }
    }

    // RSH4b1
    public void test_PushChannel_subscribeClient_not_registered() throws AblyException {
        Channel channel = rest.channels.get("pushenabled:foo");
        PushBase.ChannelSubscription sub = PushBase.ChannelSubscription.forClientId(channel.name, rest.device(getContext()).clientId);

        try {
            channel.push.subscribeClient(getContext());
            fail("expected failure due to device not having a client ID");
        } catch (AblyException e) {
        }
    }

    // RSH4b2
    public void test_PushChannel_subscribeClient_ok() throws AblyException {
        rest.auth.setClientId("testClient");
        machine.reset();
        
        Channel channel = rest.channels.get("pushenabled:foo");
        PushBase.ChannelSubscription sub = PushBase.ChannelSubscription.forClientId(channel.name, rest.device(getContext()).clientId);

        try {
            registerAndWait();

            channel.push.subscribeClient(getContext());

            PushBase.ChannelSubscription[] items = rest.push.admin.channelSubscriptions.list(new Param[]{
                    new Param("channel", channel.name),
                    new Param("clientId", sub.clientId),
                    new Param("fullWait", "true"),
            }).items();
            assertSize(1, items);
            assertEquals(items[0], sub);
        } finally {
            rest.push.admin.channelSubscriptions.remove(sub);
        }
    }

    // RSH4c1
    public void test_PushChannel_unsubscribeDevice_not_registered() throws AblyException {
        Channel channel = rest.channels.get("pushenabled:foo");
        PushBase.ChannelSubscription sub = PushBase.ChannelSubscription.forDevice(channel.name, rest.device(getContext()).id);

        try {
            channel.push.unsubscribeDevice(getContext());
            fail("expected failure due to device not being registered");
        } catch (AblyException e) {
        }
    }

    // RSH4c2
    public void test_PushChannel_unsubscribeDevice_ok() throws AblyException {
        Channel channel = rest.channels.get("pushenabled:foo");
        PushBase.ChannelSubscription sub = PushBase.ChannelSubscription.forDevice(channel.name, rest.device(getContext()).id);

        try {
            registerAndWait();

            rest.push.admin.channelSubscriptions.save(sub);

            channel.push.unsubscribeDevice(getContext());

            PushBase.ChannelSubscription[] items = rest.push.admin.channelSubscriptions.list(new Param[]{
                    new Param("channel", channel.name),
                    new Param("deviceId", sub.deviceId),
                    new Param("fullWait", "true"),
            }).items();
            assertSize(0, items);
        } finally {
            rest.push.admin.channelSubscriptions.remove(sub);
        }
    }

    // RSH4d1
    public void test_PushChannel_unsubscribeClient_not_registered() throws AblyException {
        Channel channel = rest.channels.get("pushenabled:foo");
        PushBase.ChannelSubscription sub = PushBase.ChannelSubscription.forClientId(channel.name, rest.device(getContext()).clientId);

        try {
            channel.push.unsubscribeClient(getContext());
            fail("expected failure due to device not having a client ID");
        } catch (AblyException e) {
        }
    }

    // RSH4d2
    public void test_PushChannel_unsubscribeClient_ok() throws AblyException {
        rest.auth.setClientId("testClient");
        machine.reset();
        
        Channel channel = rest.channels.get("pushenabled:foo");
        PushBase.ChannelSubscription sub = PushBase.ChannelSubscription.forClientId(channel.name, rest.device(getContext()).clientId);

        try {
            registerAndWait();

            rest.push.admin.channelSubscriptions.save(sub);

            channel.push.unsubscribeClient(getContext());

            PushBase.ChannelSubscription[] items = rest.push.admin.channelSubscriptions.list(new Param[]{
                    new Param("channel", channel.name),
                    new Param("clientId", sub.clientId),
                    new Param("fullWait", "true"),
            }).items();
            assertSize(0, items);
        } finally {
            rest.push.admin.channelSubscriptions.remove(sub);
        }
    }

    // RSH4e
    public void test_PushChannel_listSubscriptions() throws Exception {
        class TestCase extends TestCases.Base {
            private boolean useClientId;

            public TestCase(String name, boolean useClientId) {
                super(name, null);
                this.useClientId = useClientId;
            }

            @Override
            public void run() throws Exception {
                if (useClientId) {
                    rest.auth.setClientId("testClient");
                    machine.reset();
                }

                registerAndWait();
                DeviceDetails otherDevice = DeviceDetails.fromJsonObject(JsonUtils.object()
                        .add("id", "other")
                        .add("platform", "android")
                        .add("formFactor", "tablet")
                        .add("metadata", JsonUtils.object())
                        .add("push", JsonUtils.object()
                                .add("recipient", JsonUtils.object()
                                        .add("transportType", "gcm")
                                        .add("registrationToken", "qux")))
                        .toJson());

                String deviceId = rest.device(getContext()).id;

                Push.ChannelSubscription[] fixtures = new Push.ChannelSubscription[] {
                    PushBase.ChannelSubscription.forDevice("pushenabled:foo", deviceId),
                    PushBase.ChannelSubscription.forDevice("pushenabled:foo", "other"),
                    PushBase.ChannelSubscription.forDevice("pushenabled:bar", deviceId),
                    PushBase.ChannelSubscription.forClientId("pushenabled:foo", "testClient"),
                    PushBase.ChannelSubscription.forClientId("pushenabled:foo", "otherClient"),
                    PushBase.ChannelSubscription.forClientId("pushenabled:bar", "testClient"),
                };

                try {
                    rest.push.admin.deviceRegistrations.save(otherDevice);

                    for (PushBase.ChannelSubscription sub : fixtures) {
                        rest.push.admin.channelSubscriptions.save(sub);
                    }

                    Push.ChannelSubscription[] got = rest.channels.get("pushenabled:foo").push.listSubscriptions(getContext()).items();

                    ArrayList<Push.ChannelSubscription> expected = new ArrayList<>(2);
                    expected.add(PushBase.ChannelSubscription.forDevice("pushenabled:foo", deviceId));
                    if (useClientId) {
                        expected.add(PushBase.ChannelSubscription.forClientId("pushenabled:foo", "testClient"));
                    }

                    assertArrayUnorderedEquals(expected.toArray(), got);
                } finally {
                    rest.push.admin.deviceRegistrations.remove(otherDevice);
                    for (PushBase.ChannelSubscription sub : fixtures) {
                        rest.push.admin.channelSubscriptions.remove(sub);
                    }
                }
            }
        }

        TestCases testCases = new TestCases();

        testCases.add(new TestCase("without client ID", false));
        testCases.add(new TestCase("with client ID", true));

        testCases.run();
    }

    private void moveToAfterRegistrationUpdateFailed() {
        // Move to AfterRegistrationUpdateFailed by forcing an update failure.

        rest.push.activate(getContext(), true); // Just to set useCustomRegisterer to true.
        AsyncWaiter<Intent> customRegisterer = broadcastWaiter("PUSH_REGISTER_DEVICE");
        rest.push.onNewRegistrationToken(getContext(), RegistrationToken.Type.GCM, "testTokenFailed");
        customRegisterer.waitFor();

        CompletionWaiter failedWaiter = machine.getTransitionedToWaiter(AfterRegistrationUpdateFailed.class);

        Intent intent = new Intent();
        IntentUtils.addErrorInfo(intent, new ErrorInfo("intentional", 123));
        sendBroadcast("PUSH_UPDATE_TOKEN", intent);

        failedWaiter.waitFor();
    }

    // This is all copied and pasted from ParameterizedTest, since I can't inherit from it.
    // I need to inherit from AndroidPushTest, and Java doesn't have multiple inheritance
    // or mixins or something like that.

    protected static Setup.TestVars testVars;

    public static void setUpBeforeClass() throws Exception {
        testVars = Setup.getTestVars();
    }

    public static void tearDownAfterClass() throws Exception {
        Setup.clearTestVars();
    }

    private Setup.TestParameters testParams = Setup.TestParameters.getDefault();

    protected DebugOptions createOptions() throws AblyException {
        return testVars.createOptions(testParams);
    }

    protected DebugOptions createOptions(String key) throws AblyException {
        return testVars.createOptions(key, testParams);
    }

    protected void fillInOptions(ClientOptions opts) {
        testVars.fillInOptions(opts, testParams);
    }

    private class TestActivationStateMachine extends Push.ActivationStateMachine {
        class EventOrStateWaiter extends CompletionWaiter {
            Class<? extends Event> event;
            Class<? extends State> state;

            public boolean shouldFire(State state, Event event) {
                if (this.state != null) {
                    if (this.state.isInstance(state)) {
                        return true;
                    }
                } else if (this.event != null) {
                    if (this.event.isInstance(event)) {
                        return true;
                    }
                } else {
                    return true;
                }
                return false;
            }
        }

        private BlockingQueue<Event> events = null;
        private EventOrStateWaiter waiter;
        private Class<? extends State> waitingForState;

        public TestActivationStateMachine(Context context, AblyRest rest) {
            super(context, rest);
        }

        @Override
        public synchronized void handleEvent(Event event) {
            if (events != null) {
                try {
                    events.put(event);
                } catch (InterruptedException e) {}
            }

            super.handleEvent(event);

            if (waiter != null && waiter.shouldFire(current, event)) {
                CompletionWaiter w = waiter;
                waiter = null;
                w.onSuccess();
            }
        }

        @Override
        public boolean reset() {
            waiter = null;
            events = null;
            return super.reset();
        }

        public BlockingQueue<Event> getEventReceiver(int capacity) {
            events = new ArrayBlockingQueue<Event>(capacity);
            return events;
        }

        public CompletionWaiter getEventHandledWaiter() {
            return getEventHandledWaiter(null);
        }

        public CompletionWaiter getEventHandledWaiter(final Class<? extends Event> e) {
            waiter = new EventOrStateWaiter() {{
                event = e;
            }};
            return waiter;
        }

        public CompletionWaiter getTransitionedToWaiter(final Class<? extends State> s) {
            waiter = new EventOrStateWaiter() {{
                state = s;
            }};
            return waiter;
        }
    }

    private AsyncWaiter<Intent> broadcastWaiter(String event) {
        final AsyncWaiter<Intent> waiter = new AsyncWaiter<Intent>();
        BroadcastReceiver onceReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                LocalBroadcastManager.getInstance(context.getApplicationContext()).unregisterReceiver(this);
                ErrorInfo error = IntentUtils.getErrorInfo(intent);
                if (error == null) {
                    waiter.onSuccess(intent);
                } else {
                    waiter.onError(error);
                }
            }
        };
        IntentFilter filter = new IntentFilter("io.ably.broadcast." + event);
        LocalBroadcastManager.getInstance(getContext().getApplicationContext()).registerReceiver(onceReceiver, filter);
        return waiter;
    }

    private void sendBroadcast(String name, Intent intent) {
        intent.setAction("io.ably.broadcast." + name);
        LocalBroadcastManager.getInstance(getContext().getApplicationContext()).sendBroadcast(intent);
    }

    private void registerAndWait() {
        AsyncWaiter<Helpers.RawHttpRequest> requestWaiter = httpTracker.getRequestWaiter();
        AsyncWaiter<Intent> activateWaiter = broadcastWaiter("PUSH_ACTIVATE");

        rest.push.onNewRegistrationToken(getContext(), RegistrationToken.Type.GCM, "testToken");
        rest.push.activate(getContext(), false);

        activateWaiter.waitFor();
        assertNull(activateWaiter.error);
        requestWaiter.waitFor();
        Helpers.RawHttpRequest request = requestWaiter.result;
        assertEquals("POST", request.method);
        assertEquals("/push/deviceRegistrations", request.url.getPath());
    }

    private abstract class DeactivateTest {
        private Class<? extends State> previousState;

        DeactivateTest(Class<? extends State> previousState) {
            this.previousState = previousState;
        }

        protected abstract void setUpMachineState(TestCase testCase);

        class TestCase extends TestCases.Base {
            private final ErrorInfo deregisterError;
            private final boolean useCustomDeregisterer;
            private final Class<? extends Event> expectedEvent;
            private final Class<? extends State> expectedState;

            public TestCase(String name, boolean useCustomDeregisterer, ErrorInfo error, Class<? extends Event> expectedEvent, Class<? extends State> expectedState) {
                super(name, null);
                this.useCustomDeregisterer = useCustomDeregisterer;
                this.deregisterError = error;
                this.expectedEvent = expectedEvent;
                this.expectedState = expectedState;
            }

            @Override
            public void run() throws Exception {
                try {
                    machine.reset();

                    setUpMachineState(this);

                    final AsyncWaiter<Intent> deregisterCallback = useCustomDeregisterer ? broadcastWaiter("PUSH_DEREGISTER_DEVICE") : null;
                    final AsyncWaiter<Intent> deactivateCallback = broadcastWaiter("PUSH_DEACTIVATE");
                    AsyncWaiter<Helpers.RawHttpRequest> requestWaiter = null;

                    if (!useCustomDeregisterer) {
                        if (deregisterError != null) {
                            httpTracker.mockResponse = Helpers.httpResponseFromErrorInfo(deregisterError);
                        }

                        requestWaiter = httpTracker.getRequestWaiter();
                        // Block until we've checked the intermediate WaitingForDeregistration state,
                        // before the request's response causes another state transition.
                        // Otherwise, our test would be racing against the request.
                        httpTracker.lockRequests();
                    }

                    CompletionWaiter deactivatingWaiter = machine.getTransitionedToWaiter(WaitingForDeregistration.class);
                    // Will send a CalledDeactivate event.
                    rest.push.deactivate(getContext(), useCustomDeregisterer);
                    deactivatingWaiter.waitFor();

                    if (useCustomDeregisterer) {
                        // RSH3d2a
                        deregisterCallback.waitFor();
                        assertNull(deregisterCallback.error);
                    } else {
                        // RSH3d2b
                        requestWaiter.waitFor();
                        Helpers.RawHttpRequest request = requestWaiter.result;
                        assertEquals("DELETE", request.method);
                        assertEquals("/push/deviceRegistrations", request.url.getPath());
                        assertTrue(request.url.getQuery().contains("deviceId="+rest.device(getContext()).id));
                    }

                    // RSH3d2d
                    assertInstanceOf(WaitingForDeregistration.class, machine.current);

                    // Now wait for next event, after deregistration.
                    CompletionWaiter handled = machine.getEventHandledWaiter();
                    BlockingQueue<Event> events = machine.getEventReceiver(1);

                    if (useCustomDeregisterer) {
                        Intent intent = new Intent();
                        if (deregisterError != null) {
                            IntentUtils.addErrorInfo(intent, deregisterError);
                        }
                        sendBroadcast("PUSH_DEVICE_DEREGISTERED", intent);
                    } else {
                        httpTracker.unlockRequests();
                    }

                    assertTrue(expectedEvent.isInstance(events.take()));
                    assertNull(handled.waitFor());

                    if (deregisterError == null) {
                        // RSH3g2a
                        assertNull(rest.device(getContext()).updateToken);
                    } else {
                        // RSH3g3a
                        assertNotNull(rest.device(getContext()).updateToken);
                    }

                    // RSH3g2b, RSH3g3a
                    deactivateCallback.waitFor();
                    assertEquals(deregisterError, deactivateCallback.error);
                    assertInstanceOf(expectedState, machine.current);
                } finally {
                    httpTracker.unlockRequests();
                    rest.push.admin.deviceRegistrations.remove(rest.device(getContext()));
                }
            }
        }

        public void run() throws Exception {
            TestCases testCases = new TestCases();

            // RSH3g2
            testCases.add(new TestCase(
                    "ok with custom deregisterer",
                    true,
                    null,
                    Deregistered.class,
                    NotActivated.class /* RSH3g2c */));

            testCases.add(new TestCase(
                    "ok with default deregisterer",
                    false,
                    null,
                    Deregistered.class,
                    NotActivated.class /* RSH3g2c */));

            // RSH3g3
            testCases.add(new TestCase(
                    "failing with custom deregisterer",
                    true,
                    new ErrorInfo("testError", 123),
                    DeregistrationFailed.class,
                    previousState /* RSH3g3b */));

            testCases.add(new TestCase(
                    "failing with default deregisterer",
                    false,
                    new ErrorInfo("testError", 123),
                    DeregistrationFailed.class,
                    previousState /* RSH3g3b */));

            testCases.run();
        }
    }

    private abstract class UpdateRegistrationTest {
        protected abstract void setUpMachineState(TestCase testCase);

        class TestCase extends TestCases.Base {
            private final ErrorInfo updateError;
            private final boolean useCustomRegisterer;
            private final Class<? extends Event> expectedEvent;
            private final Class<? extends State> expectedState;

            public TestCase(String name, boolean useCustomRegisterer, ErrorInfo error, Class<? extends Event> expectedEvent, Class<? extends State> expectedState) {
                super(name, null);
                this.useCustomRegisterer = useCustomRegisterer;
                this.updateError = error;
                this.expectedEvent = expectedEvent;
                this.expectedState = expectedState;
            }

            @Override
            public void run() throws Exception {
                try {
                    machine.reset();

                    setUpMachineState(this);

                    final AsyncWaiter<Intent> registerCallback = useCustomRegisterer ? broadcastWaiter("PUSH_REGISTER_DEVICE") : null;
                    final AsyncWaiter<Intent> updateFailedCallback = updateError != null ? broadcastWaiter("PUSH_UPDATE_FAILED") : null;
                    AsyncWaiter<Helpers.RawHttpRequest> requestWaiter = null;

                    if (!useCustomRegisterer) {
                        if (updateError != null) {
                            httpTracker.mockResponse = Helpers.httpResponseFromErrorInfo(updateError);
                        }

                        requestWaiter = httpTracker.getRequestWaiter();
                        // Block until we've checked the intermediate WaitingForDeregistration state,
                        // before the request's response causes another state transition.
                        // Otherwise, our test would be racing against the request.
                        httpTracker.lockRequests();
                    }

                    CompletionWaiter updatingWaiter = machine.getTransitionedToWaiter(WaitingForRegistrationUpdate.class);
                    String updatedRegistrationToken = sendInitialEvent(this);
                    updatingWaiter.waitFor();

                    if (useCustomRegisterer) {
                        // RSH3d3a
                        registerCallback.waitFor();
                        assertNull(registerCallback.error);
                    } else {
                        // RSH3d3b
                        requestWaiter.waitFor();
                        Helpers.RawHttpRequest request = requestWaiter.result;
                        assertEquals("PATCH", request.method);
                        assertEquals("/push/deviceRegistrations/"+rest.device(getContext()).id, request.url.getPath());
                        assertEquals(
                                JsonUtils.object()
                                        .add("push", JsonUtils.object()
                                                .add("recipient", JsonUtils.object()
                                                        .add("transportType", "gcm")
                                                        .add("registrationToken", updatedRegistrationToken))).toJson().toString(),
                                Serialisation.msgpackToGson(request.requestBody.getEncoded()).toString());
                        String authToken = Helpers.tokenFromAuthHeader(request.authHeader);
                        assertEquals(rest.device(getContext()).updateToken, authToken);
                    }

                    // RSH3d3d
                    assertInstanceOf(WaitingForRegistrationUpdate.class, machine.current);

                    // Now wait for next event, after updated.
                    CompletionWaiter handled = machine.getEventHandledWaiter();
                    BlockingQueue<Event> events = machine.getEventReceiver(1);

                    if (useCustomRegisterer) {
                        Intent intent = new Intent();
                        if (updateError != null) {
                            IntentUtils.addErrorInfo(intent, updateError);
                        }
                        sendBroadcast("PUSH_UPDATE_TOKEN", intent);
                    } else {
                        httpTracker.unlockRequests();
                    }

                    assertTrue(expectedEvent.isInstance(events.take()));
                    assertNull(handled.waitFor());

                    if (updateError != null) {
                        // RSH3e3a
                        updateFailedCallback.waitFor();
                        assertEquals(updateError, updateFailedCallback.error);
                    }
                    // RSH3e2a, RSH3e3b
                    assertTrue(expectedState.isInstance(machine.current));
                } finally {
                    httpTracker.unlockRequests();
                    rest.push.admin.deviceRegistrations.remove(rest.device(getContext()));
                }
            }
        }

        public void run() throws Exception {
            TestCases testCases = new TestCases();

            // RSH3e2
            testCases.add(new TestCase(
                    "ok with custom registerer",
                    true,
                    null,
                    RegistrationUpdated.class,
                    WaitingForNewPushDeviceDetails.class));

            testCases.add(new TestCase(
                    "ok with default registerer",
                    false,
                    null,
                    RegistrationUpdated.class,
                    WaitingForNewPushDeviceDetails.class));

            // RSH3e3
            testCases.add(new TestCase(
                    "failing with custom registerer",
                    true,
                    new ErrorInfo("testError", 123),
                    UpdatingRegistrationFailed.class,
                    AfterRegistrationUpdateFailed.class));

            testCases.add(new TestCase(
                    "failing with default registerer",
                    false,
                    new ErrorInfo("testError", 123),
                    UpdatingRegistrationFailed.class,
                    AfterRegistrationUpdateFailed.class));

            testCases.run();
        }

        protected String sendInitialEvent(TestCase testCase) {
            // Will send GotPushDeviceDetails event.
            CalledActivate.useCustomRegisterer(testCase.useCustomRegisterer, PreferenceManager.getDefaultSharedPreferences(getContext()));
            rest.push.onNewRegistrationToken(getContext(), RegistrationToken.Type.GCM, "testTokenUpdated");
            return "testTokenUpdated";
        }
    }
}
