package net.adamsmolnik.jpb;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.gradle.tooling.BuildLauncher;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;

import com.amazonaws.services.codepipeline.AWSCodePipeline;
import com.amazonaws.services.codepipeline.AWSCodePipelineClient;
import com.amazonaws.services.codepipeline.model.FailureDetails;
import com.amazonaws.services.codepipeline.model.FailureType;
import com.amazonaws.services.codepipeline.model.PutJobFailureResultRequest;
import com.amazonaws.services.codepipeline.model.PutJobSuccessResultRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;

/**
 * @author asmolnik
 *
 */
public class PackageBuilderHandler {

	private static final Path TMP = Paths.get("/tmp");

	// TODO move it out into some external configuration
	private static final String GRADLE_VERSION = "gradle-3.1";

	private static final String GRADLE_DIR = "gradle";

	private static final String PACKAGE_BUILDER_BUCKET_NAME = "java-package-builder";

	private static class S3Object {

		private final String bucketName, objectKey;

		private S3Object(String bucketName, String objectKey) {
			this.bucketName = bucketName;
			this.objectKey = objectKey;
		}

	}

	private final AWSCodePipeline cp = new AWSCodePipelineClient();

	private final AmazonS3 s3 = new AmazonS3Client();

	public void handle(Map<String, Object> eventMap, Context context) {
		LambdaLogger log = context.getLogger();
		Map<String, Object> jobMap = getMap(eventMap, "CodePipeline.job");
		String jobId = getValue("id", jobMap);
		try {
			setUp(log);
			Map<String, Object> data = getMap(jobMap, "data");
			S3Object ias = getS3Object("inputArtifacts", data);
			S3Object oas = getS3Object("outputArtifacts", data);
			log.log("input: " + ias.bucketName + "/" + ias.objectKey);
			String[] keyParts = ias.objectKey.split("/");
			Path workDir = Files.createDirectories(TMP.resolve(keyParts[keyParts.length - 1]));
			Path zippedArifact = zip(buildWar(extract(ias, workDir), log), workDir);
			ObjectMetadata om = new ObjectMetadata();
			om.setSSEAlgorithm(ObjectMetadata.AES_256_SERVER_SIDE_ENCRYPTION);
			PutObjectRequest por = new PutObjectRequest(oas.bucketName, oas.objectKey, zippedArifact.toFile());
			por.setMetadata(om);
			s3.putObject(por);
			log.log("output to S3: " + oas.bucketName + "/" + oas.objectKey);
			cp.putJobSuccessResult(new PutJobSuccessResultRequest().withJobId(jobId));
		} catch (Throwable t) {
			String message = "Exception occured: " + t.getLocalizedMessage();
			log.log(message);
			cp.putJobFailureResult(new PutJobFailureResultRequest().withJobId(jobId)
					.withFailureDetails(new FailureDetails().withType(FailureType.JobFailed).withMessage(message)));
			t.printStackTrace();
			throw new PackageBuilderException(t);
		} finally {
			// TODO clean up
		}

	}

	private void logSpaceUsed(LambdaLogger log) throws IOException {
		FileStore fs = Files.getFileStore(TMP);
		log.log("total space: " + fs.getTotalSpace() + ", unallocated space: " + fs.getUnallocatedSpace() + ", usable space:" + fs.getUsableSpace());
	}

	private void setUp(LambdaLogger log) throws IOException {
		try {
			logSpaceUsed(log);
			extract(new S3Object(PACKAGE_BUILDER_BUCKET_NAME, "gradle-3.1-bin.zip"), TMP);
			extract(new S3Object(PACKAGE_BUILDER_BUCKET_NAME, "jdk.zip"), TMP);
			Files.setPosixFilePermissions(TMP.resolve("jdk/bin/java"), PosixFilePermissions.fromString("rwxr-xr-x"));
		} catch (IOException e) {
			throw new PackageBuilderException(e);
		} finally {
			logSpaceUsed(log);
		}
	}

	private Path extract(S3Object ias, Path dirToExtract) throws IOException {
		try (ZipInputStream zis = new ZipInputStream(s3.getObject(ias.bucketName, ias.objectKey).getObjectContent())) {
			ZipEntry ze = null;
			while ((ze = zis.getNextEntry()) != null) {
				Path path = dirToExtract.resolve(ze.getName());
				if (ze.isDirectory()) {
					Files.createDirectories(path);
				} else {
					if (Files.notExists(path.getParent())) {
						Files.createDirectories(path);
					}
					Files.copy(zis, path, StandardCopyOption.REPLACE_EXISTING);
					zis.closeEntry();
				}
			}
		}
		return dirToExtract;
	}

	private static Path buildWar(Path workDir, LambdaLogger logger) throws IOException {
		String absWorkDir = workDir.toAbsolutePath().toString();
		logger.log("workDir: " + absWorkDir);
		GradleConnector connector = GradleConnector.newConnector();
		connector.useInstallation(TMP.resolve(GRADLE_VERSION).toAbsolutePath().toFile());
		connector.useGradleUserHomeDir(TMP.resolve(GRADLE_DIR).toAbsolutePath().toFile());
		connector.forProjectDirectory(workDir.toFile());
		ProjectConnection connection = connector.connect();
		try {
			GradleProject project = connection.getModel(GradleProject.class);
			logger.log("Project: " + project.getName());
			BuildLauncher launcher = connection.newBuild();
			launcher.setJavaHome(TMP.resolve("jdk").toFile()).forTasks("war").setStandardOutput(System.out).run();
		} finally {
			connection.close();
		}
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:**.war");
		Path warFile = Files.find(workDir.resolve("build/libs"), 1, (p, a) -> a.isRegularFile() && matcher.matches(p)).findFirst().get();
		return warFile;
	}

	private static Path zip(Path warFile, Path workDir) throws IOException {
		Path output = TMP.resolve("output.zip");
		try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))) {
			String appSpec = "appspec.yml";
			writeFileToZip(workDir.resolve(appSpec), appSpec, zos);
			String scriptsDir = "scripts";
			Files.newDirectoryStream(workDir.resolve("scripts")).forEach(p -> writeFileToZip(p, scriptsDir + "/" + p.getFileName(), zos));
			writeFileToZip(warFile, warFile.getFileName().toString(), zos);
		}
		return output;
	}

	private static void writeFileToZip(Path path, String entryName, ZipOutputStream zos) {
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

	private S3Object getS3Object(String key, Map<String, Object> map) {
		List<Map<String, Object>> lm = getListAsValue(key, map);
		Map<String, Object> s3Map = getMap(lm.get(0), "location", "s3Location");
		return new S3Object(getValue("bucketName", s3Map), getValue("objectKey", s3Map));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getMap(Map<String, Object> map, String... keys) {
		for (String key : keys) {
			map = Map.class.cast(map.get(key));
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> getListAsValue(String key, Map<String, Object> map) {
		return List.class.cast(map.get(key));
	}

	private String getValue(String key, Map<String, Object> map) {
		return String.class.cast(map.get(key));
	}

}
