// SPDX-License-Identifier: MIT

package io.github.muntashirakon.AppManager.servermanager;

import static org.junit.Assert.assertEquals;

import io.github.muntashirakon.AppManager.server.common.DataTransmission;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

public class ServerConnectionFailureTest {
    @Test
    public void classifiesProtocolMismatch() {
        ServerConnectionFailure failure = ServerConnectionFailure.from(
                new DataTransmission.ProtocolVersionException("old protocol"), false);
        assertEquals(ServerConnectionFailure.Reason.PROTOCOL_MISMATCH, failure.getReason());
    }

    @Test
    public void classifiesAuthenticationFailure() {
        ServerConnectionFailure failure = ServerConnectionFailure.from(
                new IOException("Unauthorized server: HMAC mismatch."), false);
        assertEquals(ServerConnectionFailure.Reason.AUTHENTICATION, failure.getReason());
    }

    @Test
    public void classifiesTimeoutAsUnresponsiveServer() {
        ServerConnectionFailure failure = ServerConnectionFailure.from(
                new SocketTimeoutException("read timed out"), false);
        assertEquals(ServerConnectionFailure.Reason.SERVER_UNRESPONSIVE, failure.getReason());
    }

    @Test
    public void classifiesConnectionRefusalAsTransportFailure() {
        ServerConnectionFailure failure = ServerConnectionFailure.from(
                new ConnectException("Connection refused"), false);
        assertEquals(ServerConnectionFailure.Reason.TRANSPORT, failure.getReason());
    }

    @Test
    public void classifiesStartCommandFailureSeparately() {
        ServerConnectionFailure failure = ServerConnectionFailure.from(
                new IOException("Error! Could not start server."), true);
        assertEquals(ServerConnectionFailure.Reason.SERVER_START, failure.getReason());
    }
}
