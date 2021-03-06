/*
 * Copyright 2014 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.rpc.server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.navercorp.pinpoint.rpc.client.PinpointClient;
import com.navercorp.pinpoint.rpc.client.PinpointClientFactory;
import org.jboss.netty.util.Timer;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.navercorp.pinpoint.rpc.client.PinpointClientHandshaker;
import com.navercorp.pinpoint.rpc.packet.HandshakeResponseCode;
import com.navercorp.pinpoint.rpc.packet.HandshakeResponseType;
import com.navercorp.pinpoint.rpc.util.PinpointRPCTestUtils;
import com.navercorp.pinpoint.rpc.util.TimerFactory;

public class HandshakeTest {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private static Timer timer = null;

    private static int bindPort;
    
    @BeforeClass
    public static void setUp() throws IOException {
        timer = TimerFactory.createHashedWheelTimer(HandshakeTest.class.getSimpleName(), 100, TimeUnit.MILLISECONDS, 512);
        bindPort = PinpointRPCTestUtils.findAvailablePort();
    }

    @AfterClass
    public static void tearDown() {
        if (timer != null) {
            timer.stop();
        }
    }

    // simple test
    @Test
    public void handshakeTest1() throws InterruptedException {
        PinpointServerAcceptor serverAcceptor = PinpointRPCTestUtils.createPinpointServerFactory(bindPort, new AlwaysHandshakeSuccessListener());

        PinpointClientFactory clientFactory1 = PinpointRPCTestUtils.createClientFactory(PinpointRPCTestUtils.getParams(), PinpointRPCTestUtils.createEchoClientListener());
        PinpointClientFactory clientFactory2 = PinpointRPCTestUtils.createClientFactory(PinpointRPCTestUtils.getParams(), null);
        try {
            PinpointClient client = clientFactory1.connect("127.0.0.1", bindPort);
            PinpointClient client2 = clientFactory2.connect("127.0.0.1", bindPort);

            Thread.sleep(500);

            List<PinpointServer> writableServerList = serverAcceptor.getWritableServerList();
            if (writableServerList.size() != 2) {
                Assert.fail();
            }

            PinpointRPCTestUtils.close(client, client2);
        } finally {
            clientFactory1.release();
            clientFactory2.release();

            PinpointRPCTestUtils.close(serverAcceptor);
        }
    }

    @Test
    public void handshakeTest2() throws InterruptedException {
        PinpointServerAcceptor serverAcceptor = PinpointRPCTestUtils.createPinpointServerFactory(bindPort, new AlwaysHandshakeSuccessListener());

        Map params = PinpointRPCTestUtils.getParams();
        
        PinpointClientFactory clientFactory1 = PinpointRPCTestUtils.createClientFactory(PinpointRPCTestUtils.getParams(), PinpointRPCTestUtils.createEchoClientListener());

        try {
            PinpointClient client = clientFactory1.connect("127.0.0.1", bindPort);
            Thread.sleep(500);

            PinpointServer writableServer = getWritableServer("application", "agent", (Long) params.get(AgentHandshakePropertyType.START_TIMESTAMP.getName()), serverAcceptor.getWritableServerList());
            Assert.assertNotNull(writableServer);

            writableServer = getWritableServer("application", "agent", (Long) params.get(AgentHandshakePropertyType.START_TIMESTAMP.getName()) + 1, serverAcceptor.getWritableServerList());
            Assert.assertNull(writableServer);

            PinpointRPCTestUtils.close(client);
        } finally {
            clientFactory1.release();
            PinpointRPCTestUtils.close(serverAcceptor);
        }
    }

    @Test
    public void testExecuteCompleteWithoutStart() {
        int retryInterval = 100;
        int maxHandshakeCount = 10;

        PinpointClientHandshaker handshaker = new PinpointClientHandshaker(timer, retryInterval, maxHandshakeCount);
        handshaker.handshakeComplete(null);

        Assert.assertEquals(null, handshaker.getHandshakeResult());

        Assert.assertTrue(handshaker.isFinished());
    }

    @Test
    public void testExecuteAbortWithoutStart() {
        int retryInterval = 100;
        int maxHandshakeCount = 10;

        PinpointClientHandshaker handshaker = new PinpointClientHandshaker(timer, retryInterval, maxHandshakeCount);
        handshaker.handshakeAbort();

        Assert.assertTrue(handshaker.isFinished());
    }

    private PinpointServer getWritableServer(String applicationName, String agentId, long startTimeMillis, List<PinpointServer> writableServerList) {
        if (applicationName == null) {
            return null;
        }

        if (agentId == null) {
            return null;
        }

        if (startTimeMillis <= 0) {
            return null;
        }

        List<PinpointServer> result = new ArrayList<PinpointServer>();

        for (PinpointServer writableServer : writableServerList) {
            Map agentProperties = writableServer.getChannelProperties();

            if (!applicationName.equals(agentProperties.get(AgentHandshakePropertyType.APPLICATION_NAME.getName()))) {
                continue;
            }

            if (!agentId.equals(agentProperties.get(AgentHandshakePropertyType.AGENT_ID.getName()))) {
                continue;
            }

            if (startTimeMillis != (Long) agentProperties.get(AgentHandshakePropertyType.START_TIMESTAMP.getName())) {
                continue;
            }

            result.add(writableServer);
        }

        if (result.size() == 0) {
            return null;
        }

        if (result.size() == 1) {
            return result.get(0);
        } else {
            logger.warn("Ambiguous Channel Context {}, {}, {} (Valid Agent list={}).", applicationName, agentId, startTimeMillis, result);
            return null;
        }
    }

    private class AlwaysHandshakeSuccessListener extends SimpleLoggingServerMessageListener {
        @Override
        public HandshakeResponseCode handleHandshake(Map properties) {
            logger.info("handleEnableWorker {}", properties);
            return HandshakeResponseType.Success.DUPLEX_COMMUNICATION;

        }
    }

}
