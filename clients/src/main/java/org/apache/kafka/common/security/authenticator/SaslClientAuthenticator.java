/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.security.authenticator;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.NetworkClient;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.IllegalSaslStateException;
import org.apache.kafka.common.errors.UnsupportedSaslMechanismException;
import org.apache.kafka.common.network.Authenticator;
import org.apache.kafka.common.network.Mode;
import org.apache.kafka.common.network.NetworkReceive;
import org.apache.kafka.common.network.NetworkSend;
import org.apache.kafka.common.network.Send;
import org.apache.kafka.common.network.TransportLayer;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.protocol.types.SchemaException;
import org.apache.kafka.common.requests.AbstractResponse;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.SaslHandshakeRequest;
import org.apache.kafka.common.requests.SaslHandshakeResponse;
import org.apache.kafka.common.security.auth.AuthCallbackHandler;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class SaslClientAuthenticator implements Authenticator {

    public enum SaslState {
        SEND_HANDSHAKE_REQUEST, RECEIVE_HANDSHAKE_RESPONSE, INITIAL, INTERMEDIATE, COMPLETE, FAILED
    }

    private static final Logger LOG = LoggerFactory.getLogger(SaslClientAuthenticator.class);

    private final Subject subject;
    private final String servicePrincipal;
    private final String host;
    private final String node;
    private final String mechanism;
    private final TransportLayer transportLayer;
    private final SaslClient saslClient;
    private final Map<String, ?> configs;
    private final String clientPrincipalName;
    private final AuthCallbackHandler callbackHandler;

    // buffers used in `authenticate`
    private NetworkReceive netInBuffer;
    private Send netOutBuffer;

    // Current SASL state
    private SaslState saslState;
    // Next SASL state to be set when outgoing writes associated with the current SASL state complete
    private SaslState pendingSaslState;
    // Correlation ID for the next request
    private int correlationId;
    // Request header for which response from the server is pending
    private RequestHeader currentRequestHeader;

    public SaslClientAuthenticator(Map<String, ?> configs,
                                   String node,
                                   Subject subject,
                                   String servicePrincipal,
                                   String host,
                                   String mechanism,
                                   boolean handshakeRequestEnable,
                                   TransportLayer transportLayer) throws IOException {
        this.node = node;
        this.subject = subject;
        this.host = host;
        this.servicePrincipal = servicePrincipal;
        this.mechanism = mechanism;
        this.correlationId = -1;
        this.transportLayer = transportLayer;
        this.configs = configs;

        try {
            setSaslState(handshakeRequestEnable ? SaslState.SEND_HANDSHAKE_REQUEST : SaslState.INITIAL);

            // determine client principal from subject for Kerberos to use as authorization id for the SaslClient.
            // For other mechanisms, the authenticated principal (username for PLAIN and SCRAM) is used as
            // authorization id. Hence the principal is not specified for creating the SaslClient.
            if (mechanism.equals(SaslConfigs.GSSAPI_MECHANISM))
                this.clientPrincipalName = firstPrincipal(subject);
            else
                this.clientPrincipalName = null;

            callbackHandler = new SaslClientCallbackHandler();
            callbackHandler.configure(configs, Mode.CLIENT, subject, mechanism);

            saslClient = createSaslClient();
        } catch (Exception e) {
            throw new KafkaException("Failed to configure SaslClientAuthenticator", e);
        }
    }

    private SaslClient createSaslClient() {
        try {
            return Subject.doAs(subject, new PrivilegedExceptionAction<SaslClient>() {
                public SaslClient run() throws SaslException {
                    String[] mechs = {mechanism};
                    LOG.debug("Creating SaslClient: client={};service={};serviceHostname={};mechs={}",
                        clientPrincipalName, servicePrincipal, host, Arrays.toString(mechs));
                    return Sasl.createSaslClient(mechs, clientPrincipalName, servicePrincipal, host, configs, callbackHandler);
                }
            });
        } catch (PrivilegedActionException e) {
            throw new KafkaException("Failed to create SaslClient with mechanism " + mechanism, e.getCause());
        }
    }

    /**
     * Sends an empty message to the server to initiate the authentication process. It then evaluates server challenges
     * via `SaslClient.evaluateChallenge` and returns client responses until authentication succeeds or fails.
     *
     * The messages are sent and received as size delimited bytes that consists of a 4 byte network-ordered size N
     * followed by N bytes representing the opaque payload.
     */
    public void authenticate() throws IOException {
        if (netOutBuffer != null && !flushNetOutBufferAndUpdateInterestOps())
            return;

        switch (saslState) {
            case SEND_HANDSHAKE_REQUEST:
                // When multiple versions of SASL_HANDSHAKE_REQUEST are to be supported,
                // API_VERSIONS_REQUEST must be sent prior to sending SASL_HANDSHAKE_REQUEST to
                // fetch supported versions.
                String clientId = (String) configs.get(CommonClientConfigs.CLIENT_ID_CONFIG);
                SaslHandshakeRequest handshakeRequest = new SaslHandshakeRequest(mechanism);
                currentRequestHeader = new RequestHeader(ApiKeys.SASL_HANDSHAKE,
                        handshakeRequest.version(), clientId, correlationId++);
                send(handshakeRequest.toSend(node, currentRequestHeader));
                setSaslState(SaslState.RECEIVE_HANDSHAKE_RESPONSE);
                break;
            case RECEIVE_HANDSHAKE_RESPONSE:
                byte[] responseBytes = receiveResponseOrToken();
                if (responseBytes == null)
                    break;
                else {
                    try {
                        handleKafkaResponse(currentRequestHeader, responseBytes);
                        currentRequestHeader = null;
                    } catch (Exception e) {
                        setSaslState(SaslState.FAILED);
                        throw e;
                    }
                    setSaslState(SaslState.INITIAL);
                    // Fall through and start SASL authentication using the configured client mechanism
                }
            case INITIAL:
                sendSaslToken(new byte[0], true);
                setSaslState(SaslState.INTERMEDIATE);
                break;
            case INTERMEDIATE:
                byte[] serverToken = receiveResponseOrToken();
                if (serverToken != null) {
                    sendSaslToken(serverToken, false);
                }
                if (saslClient.isComplete()) {
                    setSaslState(SaslState.COMPLETE);
                    transportLayer.removeInterestOps(SelectionKey.OP_WRITE);
                }
                break;
            case COMPLETE:
                break;
            case FAILED:
                throw new IOException("SASL handshake failed");
        }
    }

    private void setSaslState(SaslState saslState) {
        if (netOutBuffer != null && !netOutBuffer.completed())
            pendingSaslState = saslState;
        else {
            this.pendingSaslState = null;
            this.saslState = saslState;
            LOG.debug("Set SASL client state to {}", saslState);
        }
    }

    private void sendSaslToken(byte[] serverToken, boolean isInitial) throws IOException {
        if (!saslClient.isComplete()) {
            byte[] saslToken = createSaslToken(serverToken, isInitial);
            if (saslToken != null)
                send(new NetworkSend(node, ByteBuffer.wrap(saslToken)));
        }
    }

    private void send(Send send) throws IOException {
        try {
            netOutBuffer = send;
            flushNetOutBufferAndUpdateInterestOps();
        } catch (IOException e) {
            setSaslState(SaslState.FAILED);
            throw e;
        }
    }

    private boolean flushNetOutBufferAndUpdateInterestOps() throws IOException {
        boolean flushedCompletely = flushNetOutBuffer();
        if (flushedCompletely) {
            transportLayer.removeInterestOps(SelectionKey.OP_WRITE);
            if (pendingSaslState != null)
                setSaslState(pendingSaslState);
        } else
            transportLayer.addInterestOps(SelectionKey.OP_WRITE);
        return flushedCompletely;
    }

    private byte[] receiveResponseOrToken() throws IOException {
        if (netInBuffer == null) netInBuffer = new NetworkReceive(node);
        netInBuffer.readFrom(transportLayer);
        byte[] serverPacket = null;
        if (netInBuffer.complete()) {
            netInBuffer.payload().rewind();
            serverPacket = new byte[netInBuffer.payload().remaining()];
            netInBuffer.payload().get(serverPacket, 0, serverPacket.length);
            netInBuffer = null; // reset the networkReceive as we read all the data.
        }
        return serverPacket;
    }

    public KafkaPrincipal principal() {
        return new KafkaPrincipal(KafkaPrincipal.USER_TYPE, clientPrincipalName);
    }

    public boolean complete() {
        return saslState == SaslState.COMPLETE;
    }

    public void close() throws IOException {
        if (saslClient != null)
            saslClient.dispose();
        if (callbackHandler != null)
            callbackHandler.close();
    }

    private byte[] createSaslToken(final byte[] saslToken, boolean isInitial) throws SaslException {
        if (saslToken == null)
            throw new SaslException("Error authenticating with the Kafka Broker: received a `null` saslToken.");

        try {
            if (isInitial && !saslClient.hasInitialResponse())
                return saslToken;
            else
                return Subject.doAs(subject, new PrivilegedExceptionAction<byte[]>() {
                    public byte[] run() throws SaslException {
                        return saslClient.evaluateChallenge(saslToken);
                    }
                });
        } catch (PrivilegedActionException e) {
            String error = "An error: (" + e + ") occurred when evaluating SASL token received from the Kafka Broker.";
            // Try to provide hints to use about what went wrong so they can fix their configuration.
            // TODO: introspect about e: look for GSS information.
            final String unknownServerErrorText =
                "(Mechanism level: Server not found in Kerberos database (7) - UNKNOWN_SERVER)";
            if (e.toString().contains(unknownServerErrorText)) {
                error += " This may be caused by Java's being unable to resolve the Kafka Broker's" +
                    " hostname correctly. You may want to try to adding" +
                    " '-Dsun.net.spi.nameservice.provider.1=dns,sun' to your client's JVMFLAGS environment." +
                    " Users must configure FQDN of kafka brokers when authenticating using SASL and" +
                    " `socketChannel.socket().getInetAddress().getHostName()` must match the hostname in `principal/hostname@realm`";
            }
            error += " Kafka Client will go to AUTH_FAILED state.";
            //Unwrap the SaslException inside `PrivilegedActionException`
            throw new SaslException(error, e.getCause());
        }
    }

    private boolean flushNetOutBuffer() throws IOException {
        if (!netOutBuffer.completed()) {
            netOutBuffer.writeTo(transportLayer);
        }
        return netOutBuffer.completed();
    }

    private void handleKafkaResponse(RequestHeader requestHeader, byte[] responseBytes) {
        AbstractResponse response;
        try {
            response = NetworkClient.parseResponse(ByteBuffer.wrap(responseBytes), requestHeader);
        } catch (SchemaException | IllegalArgumentException e) {
            LOG.debug("Invalid SASL mechanism response, server may be expecting only GSSAPI tokens");
            throw new AuthenticationException("Invalid SASL mechanism response", e);
        }
        switch (requestHeader.apiKey()) {
            case SASL_HANDSHAKE:
                handleSaslHandshakeResponse((SaslHandshakeResponse) response);
                break;
            default:
                throw new IllegalStateException("Unexpected API key during handshake: " + requestHeader.apiKey());
        }
    }

    private void handleSaslHandshakeResponse(SaslHandshakeResponse response) {
        Errors error = response.error();
        switch (error) {
            case NONE:
                break;
            case UNSUPPORTED_SASL_MECHANISM:
                throw new UnsupportedSaslMechanismException(String.format("Client SASL mechanism '%s' not enabled in the server, enabled mechanisms are %s",
                    mechanism, response.enabledMechanisms()));
            case ILLEGAL_SASL_STATE:
                throw new IllegalSaslStateException(String.format("Unexpected handshake request with client mechanism %s, enabled mechanisms are %s",
                    mechanism, response.enabledMechanisms()));
            default:
                throw new AuthenticationException(String.format("Unknown error code %s, client mechanism is %s, enabled mechanisms are %s",
                    response.error(), mechanism, response.enabledMechanisms()));
        }
    }

    /**
     * Returns the first Principal from Subject.
     * @throws KafkaException if there are no Principals in the Subject.
     *     During Kerberos re-login, principal is reset on Subject. An exception is
     *     thrown so that the connection is retried after any configured backoff.
     */
    static final String firstPrincipal(Subject subject) {
        Set<Principal> principals = subject.getPrincipals();
        synchronized (principals) {
            Iterator<Principal> iterator = principals.iterator();
            if (iterator.hasNext())
                return iterator.next().getName();
            else
                throw new KafkaException("Principal could not be determined from Subject, this may be a transient failure due to Kerberos re-login");
        }
    }
}
