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

import com.navercorp.pinpoint.rpc.ChannelWriteFailListenableFuture;
import com.navercorp.pinpoint.rpc.Future;
import com.navercorp.pinpoint.rpc.ResponseMessage;
import com.navercorp.pinpoint.rpc.client.RequestManager;
import com.navercorp.pinpoint.rpc.client.WriteFailFutureListener;
import com.navercorp.pinpoint.rpc.common.CyclicStateChecker;
import com.navercorp.pinpoint.rpc.common.SocketStateChangeResult;
import com.navercorp.pinpoint.rpc.common.SocketStateCode;
import com.navercorp.pinpoint.rpc.control.ProtocolException;
import com.navercorp.pinpoint.rpc.packet.*;
import com.navercorp.pinpoint.rpc.packet.stream.StreamPacket;
import com.navercorp.pinpoint.rpc.server.handler.DoNothingChannelStateEventHandler;
import com.navercorp.pinpoint.rpc.server.handler.ServerStateChangeEventHandler;
import com.navercorp.pinpoint.rpc.stream.ClientStreamChannelContext;
import com.navercorp.pinpoint.rpc.stream.ClientStreamChannelMessageListener;
import com.navercorp.pinpoint.rpc.stream.StreamChannelContext;
import com.navercorp.pinpoint.rpc.stream.StreamChannelManager;
import com.navercorp.pinpoint.rpc.util.*;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Taejin Koo
 */
public class DefaultPinpointServer implements PinpointServer {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final Channel channel;
    private final RequestManager requestManager;

    private final DefaultPinpointServerState state;
    private final CyclicStateChecker stateChecker;

    private final ServerMessageListener messageListener;

    private final List<ServerStateChangeEventHandler> stateChangeEventListeners;

    private final StreamChannelManager streamChannelManager;

    private final AtomicReference<Map<Object, Object>> properties = new AtomicReference<Map<Object, Object>>();

    private final String objectUniqName;
    
    private final ChannelFutureListener serverCloseWriteListener;
    private final ChannelFutureListener responseWriteFailListener;
    
    private final WriteFailFutureListener pongWriteFutureListener = new WriteFailFutureListener(logger, "pong write fail.", "pong write success.");
    
    
    public DefaultPinpointServer(Channel channel, PinpointServerConfig serverConfig) {
        this(channel, serverConfig, null);
    }

    public DefaultPinpointServer(Channel channel, PinpointServerConfig serverConfig, ServerStateChangeEventHandler... stateChangeEventListeners) {
        this.channel = channel;

        this.messageListener = serverConfig.getMessageListener();

        StreamChannelManager streamChannelManager = new StreamChannelManager(channel, IDGenerator.createEvenIdGenerator(), serverConfig.getStreamMessageListener());
        this.streamChannelManager = streamChannelManager;

        this.stateChangeEventListeners = new ArrayList<ServerStateChangeEventHandler>();
        List<ServerStateChangeEventHandler> configuredStateChangeEventHandlers = serverConfig.getStateChangeEventHandlers();
        if (configuredStateChangeEventHandlers != null) {
            for (ServerStateChangeEventHandler configuredStateChangeEventHandler : configuredStateChangeEventHandlers) {
                ListUtils.addIfValueNotNull(this.stateChangeEventListeners, configuredStateChangeEventHandler);
            }
        }
        ListUtils.addAllExceptNullValue(this.stateChangeEventListeners, stateChangeEventListeners);
        if (this.stateChangeEventListeners.isEmpty()) {
            this.stateChangeEventListeners.add(DoNothingChannelStateEventHandler.INSTANCE);
        }

        RequestManager requestManager = new RequestManager(serverConfig.getRequestManagerTimer(), serverConfig.getDefaultRequestTimeout());
        this.requestManager = requestManager;

        
        this.objectUniqName = ClassUtils.simpleClassNameAndHashCodeString(this);
        
        this.serverCloseWriteListener = new WriteFailFutureListener(logger, objectUniqName + " sendClosePacket() write fail.", "serverClosePacket write success");
        this.responseWriteFailListener = new WriteFailFutureListener(logger, objectUniqName + " response() write fail.");

        this.state = new DefaultPinpointServerState(this, this.stateChangeEventListeners);
        this.stateChecker = new CyclicStateChecker(5);
    }
    
    public void start() {
        logger.info("{} start() started. channel:{}.", objectUniqName, channel);
        
        state.toConnected();
        state.toRunWithoutHandshake();
        
        logger.info("{} start() completed.", objectUniqName);
    }
    
    public void stop() {
        logger.info("{} stop() started. channel:{}.", objectUniqName, channel);

        stop(false);
        
        logger.info("{} stop() completed.", objectUniqName);
    }
    
    public void stop(boolean serverStop) {
        SocketStateCode currentStateCode = getCurrentStateCode();
        if (SocketStateCode.BEING_CLOSE_BY_SERVER == currentStateCode) {
            state.toClosed();
        } else if (SocketStateCode.BEING_CLOSE_BY_CLIENT == currentStateCode) {
            state.toClosedByPeer();
        } else if (SocketStateCode.isRun(currentStateCode) && serverStop) {
            state.toUnexpectedClosed();
        } else if (SocketStateCode.isRun(currentStateCode)) {
            state.toUnexpectedClosedByPeer();
        } else if (SocketStateCode.isClosed(currentStateCode)) {
            logger.warn("{} stop(). Socket has closed state({}).", objectUniqName, currentStateCode);
        } else {
            state.toErrorUnknown();
            logger.warn("{} stop(). Socket has unexpected state.", objectUniqName, currentStateCode);
        }
        
        if (this.channel.isConnected()) {
            channel.close();
        }
        
        streamChannelManager.close();
    }

    @Override
    public void send(byte[] payload) {
        AssertUtils.assertNotNull(payload, "payload may not be null.");
        if (!isEnableDuplexCommunication()) {
            throw new IllegalStateException("Send fail. Error: Illegal State. pinpointServer:" + toString());
        }
        
        SendPacket send = new SendPacket(payload);
        write0(send);
    }

    @Override
    public Future<ResponseMessage> request(byte[] payload) {
        AssertUtils.assertNotNull(payload, "payload may not be null.");
        if (!isEnableDuplexCommunication()) {
            throw new IllegalStateException("Request fail. Error: Illegal State. pinpointServer:" + toString());
        }

        RequestPacket requestPacket = new RequestPacket(payload);
        ChannelWriteFailListenableFuture<ResponseMessage> messageFuture = this.requestManager.register(requestPacket);
        write0(requestPacket, messageFuture);
        return messageFuture;
    }

    @Override
    public void response(RequestPacket requestPacket, byte[] payload) {
        response(requestPacket.getRequestId(), payload);
    }

    @Override
    public void response(int requestId, byte[] payload) {
        AssertUtils.assertNotNull(payload, "payload may not be null.");
        if (!isEnableCommunication()) {
            throw new IllegalStateException("Response fail. Error: Illegal State. pinpointServer:" + toString());
        }

        ResponsePacket responsePacket = new ResponsePacket(requestId, payload);
        write0(responsePacket, responseWriteFailListener);
    }
    
    private ChannelFuture write0(Object message) {
        return write0(message, null);
    }

    private ChannelFuture write0(Object message, ChannelFutureListener futureListener) {
        ChannelFuture future = channel.write(message);
        if (futureListener != null) {
            future.addListener(futureListener);;
        }
        return future;
    }

    public StreamChannelContext getStreamChannel(int channelId) {
        return streamChannelManager.findStreamChannel(channelId);
    }

    @Override
    public ClientStreamChannelContext createStream(byte[] payload, ClientStreamChannelMessageListener clientStreamChannelMessageListener) {
        logger.info("{} createStream() started.", objectUniqName);

        ClientStreamChannelContext streamChannel = streamChannelManager.openStreamChannel(payload, clientStreamChannelMessageListener);
        
        logger.info("{} createStream() completed.", objectUniqName);
        return streamChannel;
    }

    public void closeAllStreamChannel() {
        logger.info("{} closeAllStreamChannel() started.", objectUniqName);

        streamChannelManager.close();

        logger.info("{} closeAllStreamChannel() completed.", objectUniqName);
    }
    
    @Override
    public Map<Object, Object> getChannelProperties() {
        Map<Object, Object> properties = this.properties.get();
        return properties == null ? Collections.emptyMap() : properties;
    }

    public boolean setChannelProperties(Map<Object, Object> value) {
        if (value == null) {
            return false;
        }

        return this.properties.compareAndSet(null, Collections.unmodifiableMap(value));
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    public ChannelFuture sendClosePacket() {
        logger.info("{} sendClosePacket() started.", objectUniqName);
        
        SocketStateChangeResult stateChangeResult = state.toBeingClose();
        if (stateChangeResult.isChange()) {
            final ChannelFuture writeFuture = this.channel.write(ServerClosePacket.DEFAULT_SERVER_CLOSE_PACKET);
            writeFuture.addListener(serverCloseWriteListener);

            logger.info("{} sendClosePacket() completed.", objectUniqName);
            return writeFuture;
        } else {
            logger.info("{} sendClosePacket() failed. Error:{}.", objectUniqName, stateChangeResult);
            return null;
        }
    }

    @Override
    public void messageReceived(Object message) {
        if (!isEnableCommunication()) {
            // FIXME need change rules.
            // as-is : do nothing when state is not run.
            // candidate : close channel when state is not run.
            logger.warn("{} messageReceived() failed. Error: Illegal state this message({}) will be ignore.", objectUniqName, message);
            return;
        }
        
        final short packetType = getPacketType(message);
        switch (packetType) {
            case PacketType.APPLICATION_SEND: {
                handleSend((SendPacket) message);
                return;
            }
            case PacketType.APPLICATION_REQUEST: {
                handleRequest((RequestPacket) message);
                return;
            }
            case PacketType.APPLICATION_RESPONSE: {
                handleResponse((ResponsePacket) message);
                return;
            }
            case PacketType.APPLICATION_STREAM_CREATE:
            case PacketType.APPLICATION_STREAM_CLOSE:
            case PacketType.APPLICATION_STREAM_CREATE_SUCCESS:
            case PacketType.APPLICATION_STREAM_CREATE_FAIL:
            case PacketType.APPLICATION_STREAM_RESPONSE:
            case PacketType.APPLICATION_STREAM_PING:
            case PacketType.APPLICATION_STREAM_PONG:
                handleStreamEvent((StreamPacket) message);
                return;
            case PacketType.CONTROL_HANDSHAKE:
                handleHandshake((ControlHandshakePacket) message);
                return;
            case PacketType.CONTROL_CLIENT_CLOSE: {
                handleClosePacket(channel);
                return;
            }
            case PacketType.CONTROL_PING: {
                handlePingPacket(channel, (PingPacket) message);
                return;
            }            
            default: {
                logger.warn("invalid messageReceived msg:{}, connection:{}", message, channel);
            }
        }
    }

    private short getPacketType(Object packet) {
        if (packet == null) {
            return PacketType.UNKNOWN;
        }

        if (packet instanceof Packet) {
            return ((Packet) packet).getPacketType();
        }

        return PacketType.UNKNOWN;
    }

    private void handleSend(SendPacket sendPacket) {
        messageListener.handleSend(sendPacket, this);
    }

    private void handleRequest(RequestPacket requestPacket) {
        messageListener.handleRequest(requestPacket, this);
    }

    private void handleResponse(ResponsePacket responsePacket) {
        this.requestManager.messageReceived(responsePacket, this);
    }

    private void handleStreamEvent(StreamPacket streamPacket) {
        streamChannelManager.messageReceived(streamPacket);
    }

    private void handleHandshake(ControlHandshakePacket handshakepacket) {
        logger.info("{} handleHandshake() started. Packet:{}", objectUniqName, handshakepacket);
        
        int requestId = handshakepacket.getRequestId();
        Map<Object, Object> handshakeData = decodeHandshakePacket(handshakepacket);
        HandshakeResponseCode responseCode = messageListener.handleHandshake(handshakeData);
        boolean isFirst = setChannelProperties(handshakeData);
        if (isFirst) {
            if (HandshakeResponseCode.DUPLEX_COMMUNICATION == responseCode) {
                state.toRunDuplex();
            } else if (HandshakeResponseCode.SIMPLEX_COMMUNICATION == responseCode || HandshakeResponseCode.SUCCESS == responseCode) {
                state.toRunSimplex();
            } 
        }

        logger.info("{} handleHandshake(). ResponseCode:{}", objectUniqName, responseCode);

        Map<String, Object> responseData = createHandshakeResponse(responseCode, isFirst);
        sendHandshakeResponse0(requestId, responseData);
        
        logger.info("{} handleHandshake() completed.", objectUniqName);
    }

    private void handleClosePacket(Channel channel) {
        logger.info("{} handleClosePacket() started.", objectUniqName);
        
        SocketStateChangeResult stateChangeResult = state.toBeingCloseByPeer();
        if (!stateChangeResult.isChange()) {
            logger.info("{} handleClosePacket() failed. Error: {}", objectUniqName, stateChangeResult);
        } else {
            logger.info("{} handleClosePacket() completed.", objectUniqName);
        }
    }
    
    private void handlePingPacket(Channel channel, PingPacket packet) {
        logger.debug("{} handlePingPacket() started. packet:{}", objectUniqName, packet);
        
        SocketStateCode statusCode = state.getCurrentStateCode();

        if (statusCode.getId() == packet.getStateCode()) {
            stateChecker.unmark();
            
            messageListener.handlePing(packet, this);

            PongPacket pongPacket = PongPacket.PONG_PACKET;
            ChannelFuture write = channel.write(pongPacket);
            write.addListener(pongWriteFutureListener);
        } else {
            logger.warn("Session state sync failed. channel:{}, packet:{}, server-state:{}", channel, packet, statusCode);
            
            if (stateChecker.markAndCheckCondition()) {
                state.toErrorSyncStateSession();
                stop();
            }
        }
    }

    private Map<String, Object> createHandshakeResponse(HandshakeResponseCode responseCode, boolean isFirst) {
        HandshakeResponseCode createdCode = null;
        if (isFirst) {
            createdCode = responseCode;
        } else {
            if (HandshakeResponseCode.DUPLEX_COMMUNICATION == responseCode) {
                createdCode = HandshakeResponseCode.ALREADY_DUPLEX_COMMUNICATION;
            } else if (HandshakeResponseCode.SIMPLEX_COMMUNICATION == responseCode) {
                createdCode = HandshakeResponseCode.ALREADY_SIMPLEX_COMMUNICATION;
            } else {
                createdCode = responseCode;
            }
        }

        Map<String, Object> result = new HashMap<String, Object>();
        result.put(ControlHandshakeResponsePacket.CODE, createdCode.getCode());
        result.put(ControlHandshakeResponsePacket.SUB_CODE, createdCode.getSubCode());

        return result;
    }

    private void sendHandshakeResponse0(int requestId, Map<String, Object> data) {
        try {
            byte[] resultPayload = ControlMessageEncodingUtils.encode(data);
            ControlHandshakeResponsePacket packet = new ControlHandshakeResponsePacket(requestId, resultPayload);

            channel.write(packet);
        } catch (ProtocolException e) {
            logger.warn(e.getMessage(), e);
        }
    }

    private Map<Object, Object> decodeHandshakePacket(ControlHandshakePacket message) {
        try {
            byte[] payload = message.getPayload();
            Map<Object, Object> properties = (Map) ControlMessageEncodingUtils.decode(payload);
            return properties;
        } catch (ProtocolException e) {
            logger.warn(e.getMessage(), e);
        }

        return null;
    }

    public boolean isEnableCommunication() {
        return state.isEnableCommunication();
    }
    
    public boolean isEnableDuplexCommunication() {
        return state.isEnableDuplexCommunication();
    }

    String getObjectUniqName() {
        return objectUniqName;
    }
    
    @Override
    public SocketStateCode getCurrentStateCode() {
        return state.getCurrentStateCode();
    }
    
    @Override
    public String toString() {
        StringBuilder log = new StringBuilder(32);
        log.append(objectUniqName);
        log.append("(");
        log.append("remote:");
        log.append(getRemoteAddress());
        log.append(", state:");
        log.append(getCurrentStateCode());
        log.append(")");
        
        return log.toString();
    }
    
}
