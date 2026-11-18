package io.github.mudklip.minigamebots.client.brain;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * The local socket to the brain. The mod listens on 127.0.0.1 with a random port and a one-time
 * token; the brain process connects back and must present the token, so nothing else on the PC
 * can pose as the brain. Messages are a 4-byte big-endian length followed by UTF-8 JSON.
 */
public final class BrainConnection implements AutoCloseable {
	private static final int MAX_MESSAGE = 16 * 1024 * 1024;

	private final Gson gson;
	private final ServerSocket server;
	private final String token;
	private Socket socket;
	private DataOutputStream out;
	private Thread reader;
	private volatile boolean closed;

	public BrainConnection(Gson gson) throws IOException {
		this.gson = gson;
		this.server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		byte[] bytes = new byte[16];
		new SecureRandom().nextBytes(bytes);
		this.token = HexFormat.of().formatHex(bytes);
	}

	public int port() {
		return server.getLocalPort();
	}

	public String token() {
		return token;
	}

	/**
	 * Waits for the brain to connect and say hello with the right token. Returns the hello
	 * message; after that, every message goes to {@code onMessage} on a background thread.
	 */
	public JsonObject accept(int timeoutMs, Consumer<JsonObject> onMessage, Runnable onClosed) throws IOException {
		server.setSoTimeout(timeoutMs);
		Socket s;
		try {
			s = server.accept();
		} catch (SocketTimeoutException e) {
			throw new IOException("The brain didn't connect within " + timeoutMs / 1000 + " s");
		} finally {
			server.close();
		}
		s.setTcpNoDelay(true);
		s.setSoTimeout(timeoutMs);
		DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
		JsonObject hello = read(in);
		if (!"hello".equals(str(hello, "type")) || !token.equals(str(hello, "token"))) {
			s.close();
			throw new IOException("Something other than the brain connected; refused");
		}
		s.setSoTimeout(0);
		socket = s;
		out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 65536));
		reader = new Thread(() -> {
			try {
				while (!closed) {
					onMessage.accept(read(in));
				}
			} catch (IOException e) {
				// Closed or the brain exited.
			} finally {
				closed = true;
				onClosed.run();
			}
		}, "MinigameBots brain reader");
		reader.setDaemon(true);
		reader.start();
		return hello;
	}

	private JsonObject read(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0 || length > MAX_MESSAGE) {
			throw new IOException("Bad message length " + length);
		}
		byte[] body = new byte[length];
		in.readFully(body);
		return gson.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
	}

	public synchronized void send(JsonObject message) throws IOException {
		if (out == null || closed) {
			throw new IOException("Not connected");
		}
		byte[] body = gson.toJson(message).getBytes(StandardCharsets.UTF_8);
		out.writeInt(body.length);
		out.write(body);
		out.flush();
	}

	public boolean connected() {
		return out != null && !closed;
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
	}

	@Override
	public void close() {
		closed = true;
		try {
			server.close();
			if (socket != null) {
				socket.close();
			}
		} catch (IOException ignored) {
			// Already gone.
		}
	}
}
