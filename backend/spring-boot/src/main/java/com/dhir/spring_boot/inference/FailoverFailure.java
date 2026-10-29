package com.dhir.spring_boot.inference;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.web.client.RestClientResponseException;

/** Conservative retry boundary; an unknown transport failure may have executed remotely. */
enum FailoverFailure {
    PRE_SEND_CONNECTION(true), TRANSIENT_HTTP(true), BUDGET_EXPIRED(false), CONNECT_TIMEOUT(false), TIMEOUT(false),
    INTERRUPTED(false), HTTP_ERROR(false), UNKNOWN(false);

    private final boolean retryable;
    FailoverFailure(boolean retryable) { this.retryable = retryable; }
    boolean retryable() { return retryable; }

    static FailoverFailure classify(Throwable failure) {
        boolean connection = false;
        boolean timeout = false;
        boolean connectTimeout = false;
        boolean budgetExpired = false;
        boolean fairAllocation = false;
        boolean interrupted = Thread.currentThread().isInterrupted();
        Integer status = null;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            interrupted |= t instanceof InterruptedException;
            budgetExpired |= t instanceof BudgetExpiredException;
            if (t instanceof BudgetExpiredException expired) fairAllocation |= expired.fairAllocation();
            connectTimeout |= t instanceof HttpConnectTimeoutException;
            timeout |= t instanceof HttpTimeoutException || t instanceof SocketTimeoutException;
            connection |= t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof UnresolvedAddressException;
            if (t instanceof RestClientResponseException response) status = response.getStatusCode().value();
        }
        if (interrupted) return INTERRUPTED;
        if (budgetExpired) return fairAllocation ? TIMEOUT : BUDGET_EXPIRED;
        if (connectTimeout) return CONNECT_TIMEOUT;
        if (timeout) return TIMEOUT;
        if (status != null) return status == 502 || status == 503 ? TRANSIENT_HTTP : HTTP_ERROR;
        return connection ? PRE_SEND_CONNECTION : UNKNOWN;
    }
}
