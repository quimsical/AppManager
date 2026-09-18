// SPDX-License-Identifier: MIT

package io.github.muntashirakon.AppManager.servermanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.muntashirakon.AppManager.server.common.DataTransmission;
import io.github.muntashirakon.AppManager.server.common.FLog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class DataTransmissionTest {
    private static final String TOKEN = "test-token";

    @Before
    public void setUp() {
        FLog.writeLog = true;
    }

    @After
    public void tearDown() {
        FLog.writeLog = false;
    }

    @Test
    public void authenticatedHandshakeSucceeds() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                            socket.getInputStream(), false);
                    transfer.shakeHands(TOKEN, DataTransmission.Role.Server);
                }
                return null;
            });
            try (Socket socket = new Socket("127.0.0.1", listener.getLocalPort())) {
                DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                        socket.getInputStream(), false);
                transfer.shakeHands(TOKEN, DataTransmission.Role.Client);
            }
            server.get(2, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    public void wrongTokenFailsAuthentication() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Throwable> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                            socket.getInputStream(), false);
                    transfer.shakeHands(TOKEN, DataTransmission.Role.Server);
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            });
            try (Socket socket = new Socket("127.0.0.1", listener.getLocalPort())) {
                DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                        socket.getInputStream(), false);
                try {
                    transfer.shakeHands("wrong-token", DataTransmission.Role.Client);
                    fail("Expected authentication failure");
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("Unauthorized"));
                }
            }
            assertTrue(server.get(2, TimeUnit.SECONDS) instanceof IOException);
            executor.shutdownNow();
        }
    }

    @Test
    public void protocolMismatchIsClassified() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Throwable> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                            socket.getInputStream(), false);
                    transfer.shakeHands(TOKEN, DataTransmission.Role.Server);
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            });
            try (Socket socket = new Socket("127.0.0.1", listener.getLocalPort())) {
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                byte[] protocol = "old-protocol".getBytes(StandardCharsets.UTF_8);
                output.writeInt(protocol.length);
                output.write(protocol);
                output.flush();
            }
            Throwable failure = server.get(2, TimeUnit.SECONDS);
            assertTrue("Unexpected server result: " + failure,
                    failure instanceof DataTransmission.ProtocolVersionException);
            executor.shutdownNow();
        }
    }

    @Test
    public void invalidFrameLengthBecomesIOException() throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream data = new java.io.DataOutputStream(output);
        data.writeInt(-1);
        DataTransmission transfer = new DataTransmission(output,
                new java.io.ByteArrayInputStream(output.toByteArray()), false);
        try {
            transfer.sendAndReceiveMessage(new byte[0]);
            fail("Expected invalid frame failure");
        } catch (IOException expected) {
            assertEquals("Invalid message length: -1", expected.getMessage());
        }
    }

    @Test
    public void requestResponseRoundTripWorksAfterHandshake() throws Exception {
        byte[] request = "ping".getBytes(StandardCharsets.UTF_8);
        byte[] response = "pong".getBytes(StandardCharsets.UTF_8);
        try (ServerSocket listener = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<byte[]> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                            socket.getInputStream(), false);
                    transfer.shakeHands(TOKEN, DataTransmission.Role.Server);
                    return transfer.sendAndReceiveMessage(response);
                }
            });
            try (Socket socket = new Socket("127.0.0.1", listener.getLocalPort())) {
                DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                        socket.getInputStream(), false);
                transfer.shakeHands(TOKEN, DataTransmission.Role.Client);
                assertArrayEquals(response, transfer.sendAndReceiveMessage(request));
            }
            assertArrayEquals(request, server.get(2, TimeUnit.SECONDS));
            executor.shutdownNow();
        }
    }

    @Test
    public void truncatedFrameBecomesIOException() throws Exception {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream data = new java.io.DataOutputStream(output);
        data.writeInt(4);
        data.write(new byte[]{1, 2});
        DataTransmission transfer = new DataTransmission(output,
                new java.io.ByteArrayInputStream(output.toByteArray()), false);
        try {
            transfer.sendAndReceiveMessage(new byte[0]);
            fail("Expected truncated frame failure");
        } catch (IOException expected) {
            assertTrue(expected instanceof java.io.EOFException);
        }
    }

    @Test
    public void oversizedOutgoingFrameIsRejected() throws Exception {
        DataTransmission transfer = new DataTransmission(
                new java.io.ByteArrayOutputStream(),
                new java.io.ByteArrayInputStream(new byte[0]), false);
        try {
            transfer.sendMessage(new byte[16 * 1024 * 1024 + 1]);
            fail("Expected oversized frame failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().startsWith("Message is too large:"));
        }
    }

    @Test
    public void clientHandshakePropagatesPeerEof() throws Exception {
        DataTransmission transfer = new DataTransmission(
                new java.io.ByteArrayOutputStream(),
                new java.io.ByteArrayInputStream(new byte[0]), false);
        try {
            transfer.shakeHands(TOKEN, DataTransmission.Role.Client);
            fail("Expected handshake EOF");
        } catch (java.io.EOFException expected) {
            // Expected: the peer disconnected before completing its response.
        }
    }

    @Test
    public void serverHandshakePropagatesPeerEof() throws Exception {
        DataTransmission transfer = new DataTransmission(
                new java.io.ByteArrayOutputStream(),
                new java.io.ByteArrayInputStream(new byte[0]), false);
        try {
            transfer.shakeHands(TOKEN, DataTransmission.Role.Server);
            fail("Expected handshake EOF");
        } catch (java.io.EOFException expected) {
            // Expected: the peer disconnected before sending its protocol.
        }
    }

    @Test
    public void rogueServerProofIsRejected() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                    readFrame(input); // Client protocol.
                    readFrame(input); // Client nonce.
                    writeFrame(output, new byte[32]); // Invalid server HMAC.
                    writeFrame(output, new byte[32]); // Server nonce.
                    output.flush();
                }
                return null;
            });
            try (Socket socket = new Socket("127.0.0.1", listener.getLocalPort())) {
                DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                        socket.getInputStream(), false);
                try {
                    transfer.shakeHands(TOKEN, DataTransmission.Role.Client);
                    fail("Expected rogue-server rejection");
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains("Unauthorized server"));
                }
            }
            server.get(2, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    public void rogueClientProofIsRejected() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Throwable> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                            socket.getInputStream(), false);
                    transfer.shakeHands(TOKEN, DataTransmission.Role.Server);
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            });
            try (Socket socket = new Socket("127.0.0.1", listener.getLocalPort())) {
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                writeFrame(output, DataTransmission.PROTOCOL_VERSION.getBytes(StandardCharsets.UTF_8));
                writeFrame(output, new byte[32]);
                output.flush();
                readFrame(input); // Server proof.
                readFrame(input); // Server nonce.
                writeFrame(output, new byte[32]); // Invalid client proof.
                output.flush();
            }
            Throwable failure = server.get(2, TimeUnit.SECONDS);
            assertTrue("Unexpected server result: " + failure,
                    failure instanceof IOException
                            && failure.getMessage().contains("Unauthorized client"));
            executor.shutdownNow();
        }
    }

    @Test
    public void receiveLoopRejectsInvalidFrame() throws Exception {
        java.io.ByteArrayOutputStream inputBytes = new java.io.ByteArrayOutputStream();
        DataOutputStream input = new DataOutputStream(inputBytes);
        input.writeInt(-1);
        DataTransmission transfer = new DataTransmission(
                new java.io.ByteArrayOutputStream(),
                new java.io.ByteArrayInputStream(inputBytes.toByteArray()), null, true);
        try {
            transfer.handleReceive();
            fail("Expected invalid receive frame failure");
        } catch (IOException expected) {
            assertEquals("Invalid message length: -1", expected.getMessage());
        }
    }

    @Test
    public void commandResponseTimeoutIsPropagated() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                            socket.getInputStream(), false);
                    transfer.shakeHands(TOKEN, DataTransmission.Role.Server);
                    readFrame(new DataInputStream(socket.getInputStream()));
                    TimeUnit.MILLISECONDS.sleep(500);
                }
                return null;
            });
            try (Socket socket = new Socket("127.0.0.1", listener.getLocalPort())) {
                socket.setSoTimeout(50);
                DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                        socket.getInputStream(), false);
                transfer.shakeHands(TOKEN, DataTransmission.Role.Client);
                try {
                    transfer.sendAndReceiveMessage(new byte[]{1});
                    fail("Expected response timeout");
                } catch (SocketTimeoutException expected) {
                    // Expected: the peer accepted the request but did not respond.
                }
            }
            server.get(2, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    public void peerDisconnectAfterHandshakeBecomesEof() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> server = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                            socket.getInputStream(), false);
                    transfer.shakeHands(TOKEN, DataTransmission.Role.Server);
                }
                return null;
            });
            try (Socket socket = new Socket("127.0.0.1", listener.getLocalPort())) {
                DataTransmission transfer = new DataTransmission(socket.getOutputStream(),
                        socket.getInputStream(), false);
                transfer.shakeHands(TOKEN, DataTransmission.Role.Client);
                server.get(2, TimeUnit.SECONDS);
                try {
                    transfer.sendAndReceiveMessage(new byte[]{1});
                    fail("Expected peer disconnect");
                } catch (IOException expected) {
                    // Depending on timing/platform, disconnect is observed while writing
                    // (Broken pipe) or while reading (EOF).
                    assertTrue(expected instanceof java.io.EOFException
                            || expected instanceof java.net.SocketException);
                }
            }
            executor.shutdownNow();
        }
    }

    private static byte[] readFrame(DataInputStream input) throws IOException {
        int length = input.readInt();
        byte[] frame = new byte[length];
        input.readFully(frame);
        return frame;
    }

    private static void writeFrame(DataOutputStream output, byte[] frame) throws IOException {
        output.writeInt(frame.length);
        output.write(frame);
    }
}
