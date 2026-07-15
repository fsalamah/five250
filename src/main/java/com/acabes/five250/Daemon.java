package com.acabes.five250;

import org.tn5250j.tools.logging.TN5250jLogFactory;
import org.tn5250j.tools.logging.TN5250jLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local daemon holding live 5250 sessions. Exposes the same SessionService two ways:
 *  - TCP, one line of JSON in, one line of JSON out (used by the thin `Cli`)
 *  - HTTP, for the GUI (see HttpApi)
 */
public final class Daemon {

    public static final int PORT = 25250;

    private final SessionService sessionService = new SessionService();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public static void main(String[] args) throws IOException {
        new Daemon().run();
    }

    private void run() throws IOException {
        TN5250jLogFactory.setLogLevels(TN5250jLogger.WARN);

        new HttpApi(sessionService).start();

        try (ServerSocket server = new ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))) {
            System.out.println("five250 daemon listening on 127.0.0.1:" + PORT);
            while (true) {
                Socket client = server.accept();
                pool.submit(() -> handle(client));
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8);

            String line = in.readLine();
            if (line == null) return;

            Map<String, Object> resp;
            try {
                Map<String, Object> req = Json.parseObject(line);
                resp = sessionService.handle(req);
            } catch (Throwable e) {
                e.printStackTrace();
                resp = SessionService.errorResponse(e);
            }
            out.println(Json.write(resp));
        } catch (IOException ignored) {
            // client disconnected before we could respond
        }
    }
}
