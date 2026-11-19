package io.github.mudklip.minigamebots.client.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.github.mudklip.minigamebots.client.control.GameInput;
import io.github.mudklip.minigamebots.client.control.VirtualKey;
import io.github.mudklip.minigamebots.client.events.GameEvent;
import io.github.mudklip.minigamebots.client.observe.Observation;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Reads and writes the recording chunk format. See {@code docs/formats/recording.md}.
 *
 * <p>Each record in a chunk is {@code int tick, int jsonLength, json, int jpegLength, jpeg}.
 * A jpegLength of -1 means "same frame as the previous record".
 */
public final class RecordingCodec {
	public static final Gson GSON = new GsonBuilder()
			.registerTypeAdapter(byte[].class, new Base64Adapter())
			.disableHtmlEscaping()
			.create();
	public static final Gson PRETTY = GSON.newBuilder().setPrettyPrinting().create();

	public static void write(DataOutputStream out, TickRecord record) throws IOException {
		JsonObject json = new JsonObject();
		json.add("input", inputJson(record.input()));
		if (record.observation() != null) {
			json.add("obs", GSON.toJsonTree(record.observation()));
		}
		JsonArray events = new JsonArray();
		for (GameEvent e : record.events()) {
			events.add(GSON.toJsonTree(e));
		}
		json.add("events", events);
		json.addProperty("frameAge", record.frameAge());
		if (record.cameraJump()) {
			json.addProperty("cameraJump", true);
		}
		if (!record.marks().isEmpty()) {
			json.add("marks", GSON.toJsonTree(record.marks()));
		}
		byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
		out.writeInt(record.tick());
		out.writeInt(bytes.length);
		out.write(bytes);
		byte[] jpeg = record.frameJpeg();
		if (jpeg == null) {
			out.writeInt(-1);
		} else {
			out.writeInt(jpeg.length);
			out.write(jpeg);
		}
	}

	/** Reads the next record, or null at the end of the chunk. Frames repeat as needed. */
	public static TickRecord read(DataInputStream in, byte[][] lastFrame) throws IOException {
		int tick;
		try {
			tick = in.readInt();
		} catch (EOFException e) {
			return null;
		}
		byte[] bytes = new byte[in.readInt()];
		in.readFully(bytes);
		int jpegLength = in.readInt();
		byte[] jpeg;
		if (jpegLength < 0) {
			jpeg = lastFrame[0];
		} else {
			jpeg = new byte[jpegLength];
			in.readFully(jpeg);
			lastFrame[0] = jpeg;
		}
		JsonObject json = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
		GameInput input = inputFrom(json.getAsJsonObject("input"));
		Observation obs = json.has("obs") ? GSON.fromJson(json.get("obs"), Observation.class) : null;
		List<GameEvent> events = new ArrayList<>();
		for (var e : json.getAsJsonArray("events")) {
			JsonObject o = e.getAsJsonObject();
			@SuppressWarnings("unchecked")
			Map<String, Object> data = GSON.fromJson(o.get("data"), Map.class);
			events.add(new GameEvent(o.get("tick").getAsLong(), o.get("type").getAsString(), data,
					o.get("source").getAsString()));
		}
		List<String> marks = new ArrayList<>();
		if (json.has("marks")) {
			for (var m : json.getAsJsonArray("marks")) {
				marks.add(m.getAsString());
			}
		}
		return new TickRecord(tick, input, obs, events, jpeg, json.get("frameAge").getAsInt(),
				json.has("cameraJump") && json.get("cameraJump").getAsBoolean(), marks);
	}

	public static JsonObject inputJson(GameInput input) {
		JsonObject o = new JsonObject();
		JsonArray keys = new JsonArray();
		for (VirtualKey key : VirtualKey.values()) {
			if (input.holds(key)) {
				keys.add(key.name().toLowerCase(java.util.Locale.ROOT));
			}
		}
		o.add("keys", keys);
		o.addProperty("attacks", input.attackClicks());
		o.addProperty("uses", input.useClicks());
		o.addProperty("yaw", input.yaw());
		o.addProperty("pitch", input.pitch());
		return o;
	}

	static GameInput inputFrom(JsonObject o) {
		EnumSet<VirtualKey> keys = EnumSet.noneOf(VirtualKey.class);
		for (var k : o.getAsJsonArray("keys")) {
			keys.add(VirtualKey.valueOf(k.getAsString().toUpperCase(java.util.Locale.ROOT)));
		}
		return new GameInput(keys, o.get("attacks").getAsInt(), o.get("uses").getAsInt(), o.get("yaw").getAsFloat(),
				o.get("pitch").getAsFloat());
	}

	/** Encodes an RGB frame (rows top to bottom) as JPEG. */
	public static byte[] jpeg(int size, byte[] rgb, float quality) {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				int i = (y * size + x) * 3;
				image.setRGB(x, y, (rgb[i] & 0xFF) << 16 | (rgb[i + 1] & 0xFF) << 8 | rgb[i + 2] & 0xFF);
			}
		}
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
		try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			writer.setOutput(out);
			writer.write(null, new IIOImage(image, null, null), param);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	/** Decodes a JPEG frame back to an image. */
	public static BufferedImage decode(byte[] jpeg) {
		try {
			return ImageIO.read(new ByteArrayInputStream(jpeg));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static final class Base64Adapter extends TypeAdapter<byte[]> {
		@Override
		public void write(JsonWriter out, byte[] value) throws IOException {
			if (value == null) {
				out.nullValue();
			} else {
				out.value(Base64.getEncoder().encodeToString(value));
			}
		}

		@Override
		public byte[] read(JsonReader in) throws IOException {
			return Base64.getDecoder().decode(in.nextString());
		}
	}

	private RecordingCodec() {
	}
}
