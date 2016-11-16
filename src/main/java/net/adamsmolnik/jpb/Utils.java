package net.adamsmolnik.jpb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * @author asmolnik
 *
 */
public class Utils {

	private Utils() {

	}

	static void writeFileToZip(Path path, String entryName, ZipOutputStream zos) {
		try {
			zos.putNextEntry(new ZipEntry(entryName));
			byte[] buffer = new byte[8192];
			try (InputStream is = Files.newInputStream(path)) {
				int len;
				while ((len = is.read(buffer)) != -1) {
					zos.write(buffer, 0, len);
				}
				zos.closeEntry();
			}
		} catch (IOException e) {
			throw new PackageBuilderException(e);
		}
	}

	static void deleteQuietlyRecursively(Path dir, boolean dirExcluded) {
		if (dir == null) {
			return;
		}
		try {
			Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.delete(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dirVisited, IOException exc) throws IOException {
					if (!dirExcluded || !dir.equals(dirVisited)) {
						try {
							Files.delete(dirVisited);
						} catch (Exception e) {
							System.out.println(e.getLocalizedMessage());
						}
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
