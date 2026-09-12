package com.sparrowlogic.smtptester.smtp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** A minimal SMTP client, so the tests can assert on exact reply lines. */
final class SmtpConversation implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;

    SmtpConversation(final int port) throws IOException {
        this.socket = new Socket("127.0.0.1", port);
        this.socket.setSoTimeout(10_000);
        this.in = new BufferedReader(
                new InputStreamReader(this.socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(
                new RawOutputStreamWriter(this.socket.getOutputStream()), false);
    }

    void readGreeting() throws IOException {
        this.in.readLine();
    }

    String ehlo() throws IOException {
        this.send("EHLO test.example.com");
        final StringBuilder response = new StringBuilder();
        String line = this.in.readLine();
        while (line != null) {
            response.append(line).append('\n');
            if (line.length() < 4 || line.charAt(3) != '-') {
                break;
            }
            line = this.in.readLine();
        }
        return response.toString();
    }

    String command(final String command) throws IOException {
        this.send(command);
        return this.in.readLine();
    }

    String data(final String raw) throws IOException {
        // RFC 5321 section 4.1.1.4: the terminator is CRLF.CRLF, so a message that already ends
        // with CRLF needs only ".CRLF" appended. Adding another CRLF would append a blank line
        // to the message body -- which the server would then faithfully store, as it should.
        final String terminator = raw.endsWith("\r\n") ? ".\r\n" : "\r\n.\r\n";
        this.out.print(raw + terminator);
        this.out.flush();
        return this.in.readLine();
    }

    /** One BDAT chunk carrying the whole message, which is what CHUNKING is normally used for. */
    String bdat(final String raw) throws IOException {
        final byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        this.send("BDAT " + bytes.length + " LAST");
        this.out.print(raw);
        this.out.flush();
        return this.in.readLine();
    }

    private void send(final String command) {
        this.out.print(command + "\r\n");
        this.out.flush();
    }

    @Override
    public void close() throws IOException {
        this.send("QUIT");
        this.socket.close();
    }
}

/** Kept here so the conversation writes raw bytes without platform line-ending surprises. */
final class RawOutputStreamWriter extends java.io.OutputStreamWriter {

    RawOutputStreamWriter(final OutputStream out) {
        super(out, StandardCharsets.UTF_8);
    }
}
