package io.github.mudklip.minigamebots.client.brain;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Unpacks a {@code .tar.gz} (the standalone Python download). Handles ustar, GNU long names and
 * pax headers, regular files, folders and symlinks. Refuses any entry that would land outside
 * the target folder.
 */
public final class TarExtractor {
	private static final int BLOCK = 512;

	public static void extractTarGz(Path archive, Path target) throws IOException {
		try (InputStream raw = Files.newInputStream(archive);
				InputStream in = new BufferedInputStream(new GZIPInputStream(new BufferedInputStream(raw, 1 << 16)), 1 << 16)) {
			extract(in, target);
		}
	}

	public static void extract(InputStream in, Path target) throws IOException {
		Path root = target.toAbsolutePath().normalize();
		Files.createDirectories(root);
		byte[] header = new byte[BLOCK];
		String longName = null;
		String longLink = null;
		boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
		while (true) {
			if (!readBlock(in, header)) {
				return;
			}
			if (isZero(header)) {
				return;
			}
			String name = longName != null ? longName : name(header);
			String link = longLink != null ? longLink : string(header, 157, 100);
			longName = null;
			longLink = null;
			int mode = (int) octal(header, 100, 8);
			long size = octal(header, 124, 12);
			char type = (char) header[156];

			if (type == 'L' || type == 'K') {
				String value = new String(readData(in, size), StandardCharsets.UTF_8).replace("\0", "");
				if (type == 'L') {
					longName = value;
				} else {
					longLink = value;
				}
				continue;
			}
			if (type == 'x' || type == 'g') {
				String pax = new String(readData(in, size), StandardCharsets.UTF_8);
				if (type == 'x') {
					longName = paxValue(pax, "path");
					longLink = paxValue(pax, "linkpath");
				}
				continue;
			}

			Path out = root.resolve(name).normalize();
			if (!out.startsWith(root)) {
				throw new IOException("Archive entry outside the target folder: " + name);
			}
			switch (type) {
				case '5' -> {
					Files.createDirectories(out);
					skip(in, size);
				}
				case '2' -> {
					Path resolved = out.getParent().resolve(link).normalize();
					if (!resolved.startsWith(root)) {
						throw new IOException("Archive link points outside the target folder: " + name);
					}
					Files.createDirectories(out.getParent());
					Files.deleteIfExists(out);
					try {
						Files.createSymbolicLink(out, Path.of(link));
					} catch (UnsupportedOperationException | IOException e) {
						// No symlinks (e.g. Windows without permission): copy later if the target exists.
						if (Files.isRegularFile(resolved)) {
							Files.copy(resolved, out, StandardCopyOption.REPLACE_EXISTING);
						}
					}
					skip(in, size);
				}
				case '0', '\0', '7' -> {
					Files.createDirectories(out.getParent());
					try (var file = Files.newOutputStream(out)) {
						copy(in, file, size);
					}
					skipPadding(in, size);
					if (posix) {
						Files.setPosixFilePermissions(out, permissions(mode));
					}
				}
				default -> skip(in, size);
			}
		}
	}

	private static String name(byte[] h) {
		String name = string(h, 0, 100);
		String prefix = string(h, 345, 155);
		boolean ustar = string(h, 257, 6).startsWith("ustar");
		return ustar && !prefix.isEmpty() ? prefix + "/" + name : name;
	}

	static String paxValue(String pax, String key) {
		// Records look like "<length> <key>=<value>\n".
		for (String line : pax.split("\n")) {
			int space = line.indexOf(' ');
			int eq = line.indexOf('=');
			if (space > 0 && eq > space && line.substring(space + 1, eq).equals(key)) {
				return line.substring(eq + 1);
			}
		}
		return null;
	}

	private static Set<PosixFilePermission> permissions(int mode) {
		Set<PosixFilePermission> p = EnumSet.noneOf(PosixFilePermission.class);
		PosixFilePermission[] all = PosixFilePermission.values();
		// OWNER_READ .. OTHERS_EXECUTE map to bits 8..0.
		for (int i = 0; i < 9; i++) {
			if ((mode & (1 << (8 - i))) != 0) {
				p.add(all[i]);
			}
		}
		p.add(PosixFilePermission.OWNER_READ);
		p.add(PosixFilePermission.OWNER_WRITE);
		return p;
	}

	private static String string(byte[] h, int offset, int length) {
		int end = offset;
		while (end < offset + length && h[end] != 0) {
			end++;
		}
		return new String(h, offset, end - offset, StandardCharsets.UTF_8);
	}

	private static long octal(byte[] h, int offset, int length) {
		// GNU base-256 for big sizes.
		if ((h[offset] & 0x80) != 0) {
			long v = 0;
			for (int i = offset + 1; i < offset + length; i++) {
				v = (v << 8) | (h[i] & 0xFF);
			}
			return v;
		}
		String s = string(h, offset, length).trim();
		return s.isEmpty() ? 0 : Long.parseLong(s, 8);
	}

	private static boolean isZero(byte[] block) {
		for (byte b : block) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	private static boolean readBlock(InputStream in, byte[] block) throws IOException {
		int read = in.readNBytes(block, 0, BLOCK);
		if (read == 0) {
			return false;
		}
		if (read < BLOCK) {
			throw new EOFException("Truncated archive");
		}
		return true;
	}

	private static byte[] readData(InputStream in, long size) throws IOException {
		if (size > 1 << 20) {
			throw new IOException("Header too large");
		}
		byte[] data = in.readNBytes((int) size);
		if (data.length < size) {
			throw new EOFException("Truncated archive");
		}
		skipPadding(in, size);
		return data;
	}

	private static void copy(InputStream in, java.io.OutputStream out, long size) throws IOException {
		byte[] buffer = new byte[1 << 16];
		long left = size;
		while (left > 0) {
			int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
			if (n < 0) {
				throw new EOFException("Truncated archive");
			}
			out.write(buffer, 0, n);
			left -= n;
		}
	}

	private static void skip(InputStream in, long size) throws IOException {
		in.skipNBytes(size);
		skipPadding(in, size);
	}

	private static void skipPadding(InputStream in, long size) throws IOException {
		long pad = (BLOCK - size % BLOCK) % BLOCK;
		in.skipNBytes(pad);
	}

	private TarExtractor() {
	}
}
