package com.dhir.spring_boot.inference;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

/** Real loopback HTTP with fake worker responses; no model inference. */
class FailoverHttpTest {
    @Test void stalledResponseBodyIsBoundedBySharedBudget() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        server.setExecutor(executor);
        server.createContext("/predict", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200,1000);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush(); entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var client=HttpClient.newHttpClient()) {
            var transport=new BudgetedWorkerTransport(client,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/predict"),30000);
            long started=System.nanoTime();
            var failure=assertThrows(WorkerClient.WorkerUnavailableException.class,()->transport.predict("x",new DispatchBudget(500)));
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            assertEquals(FailoverFailure.TIMEOUT,FailoverFailure.classify(failure));
            assertEquals(0,entered.getCount(),"must reach response body");
            assertTrue(elapsed<=750,"500ms budget +250ms scheduling allowance; measured "+elapsed);
        } finally { release.countDown(); server.stop(0); executor.close(); }
    }
}
