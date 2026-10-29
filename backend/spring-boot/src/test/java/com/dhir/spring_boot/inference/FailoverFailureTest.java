package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

class FailoverFailureTest {
    @Test void onlyExplicitConnectionFailuresAreRetryable() {
        for (Throwable cause : new Throwable[] {new ConnectException(), new UnknownHostException(),
                new UnresolvedAddressException()}) {
            assertEquals(FailoverFailure.PRE_SEND_CONNECTION,
                    FailoverFailure.classify(new RuntimeException(cause)));
        }
        assertFalse(FailoverFailure.classify(new SocketException("Connection reset")).retryable());
        assertFalse(FailoverFailure.classify(new java.io.EOFException()).retryable());
        assertFalse(FailoverFailure.classify(new WorkerClient.WorkerErrorException("malformed")).retryable());
    }

    @Test void everyTimeoutIncludingConnectIsExcluded() {
        FailoverFailure connect = FailoverFailure.classify(
                new RuntimeException(new HttpConnectTimeoutException("connect")));
        assertEquals(FailoverFailure.CONNECT_TIMEOUT, connect);
        assertFalse(connect.retryable());
        for (Throwable cause : new Throwable[] {
                new HttpTimeoutException("read"), new SocketTimeoutException("read")}) {
            assertEquals(FailoverFailure.TIMEOUT, FailoverFailure.classify(new RuntimeException(cause)));
        }
        ConnectException wrapper = new ConnectException("ambiguous");
        wrapper.initCause(new HttpTimeoutException("timeout takes precedence"));
        assertEquals(FailoverFailure.TIMEOUT, FailoverFailure.classify(wrapper));
    }

    @Test void exactHttpStatusAllowlist() {
        for (int status : new int[] {400, 404, 422, 500, 501, 502, 503, 504, 505}) {
            Throwable response = new RestClientResponseException("test", status, "test", null,
                    new byte[0], StandardCharsets.UTF_8);
            assertEquals(status == 502 || status == 503,
                    FailoverFailure.classify(new WorkerClient.WorkerErrorException("worker", response)).retryable(),
                    "status " + status);
        }
    }

    @Test void interruptionTakesPrecedenceAndIsNotCleared() {
        assertEquals(FailoverFailure.INTERRUPTED,
                FailoverFailure.classify(new RuntimeException(new InterruptedException())));
        Thread.currentThread().interrupt();
        try {
            assertEquals(FailoverFailure.INTERRUPTED, FailoverFailure.classify(new ConnectException()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void cyclicCausesTerminate() {
        RuntimeException a = new RuntimeException();
        RuntimeException b = new RuntimeException(a);
        a.initCause(b);
        assertEquals(FailoverFailure.UNKNOWN, FailoverFailure.classify(a));
    }
}
