/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.opentable.db.postgres.embedded;

import static java.lang.String.format;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tukaani.xz.XZInputStream;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;

/**
 * Resolves pre-bundled binaries from within the JAR file.
 */
final class BundledPostgresBinaryResolver implements PgBinaryResolver {

	private static final Logger LOG = LoggerFactory.getLogger(BundledPostgresBinaryResolver.class);

	private static final Lock PREPARE_BINARIES_LOCK = new ReentrantLock();
    private static final String LOCK_FILE_NAME = "epg-lock";
    private static final String TMP_DIR_LOC = System.getProperty("java.io.tmpdir");
    private static final File TMP_DIR = new File(TMP_DIR_LOC, "embedded-pg");

	@Override
	public InputStream getPgBinary(String system, String machineHardware) {
		return EmbeddedPostgres.class.getResourceAsStream(format("/postgresql-%s-%s.txz", system, machineHardware));
	}

	@Override
	public File prepareBinaries(final String system, final String machineHardware, final Optional<String> targetPath) {
		PREPARE_BINARIES_LOCK.lock();
		try {

			LOG.info("Detected a {} {} system", system, machineHardware);
			File pgDir;
			File pgTbz;
			final InputStream pgBinary;
			try {
				pgTbz = File.createTempFile("pgpg", "pgpg");
				pgBinary = getPgBinary(system, machineHardware);
			} catch (final IOException e) {
				throw new ExceptionInInitializerError(e);
			}

			if (pgBinary == null) {
				throw new IllegalStateException("No Postgres binary found for " + system + " / " + machineHardware);
			}

			try (final DigestInputStream pgArchiveData = new DigestInputStream(pgBinary,
					MessageDigest.getInstance("MD5")); final FileOutputStream os = new FileOutputStream(pgTbz)) {
				IOUtils.copy(pgArchiveData, os);
				pgArchiveData.close();
				os.close();

				if (targetPath.isPresent()) {
					pgDir = new File(targetPath.get());
				} else {
					String pgDigest = Hex.encodeHexString(pgArchiveData.getMessageDigest().digest());
	                pgDir = new File(TMP_DIR, String.format("PG-%s", pgDigest));
				}

				mkdirs(pgDir);
				final File unpackLockFile = new File(pgDir, LOCK_FILE_NAME);
				final File pgDirExists = new File(pgDir, ".exists");

				if (!pgDirExists.exists()) {
					try (final FileOutputStream lockStream = new FileOutputStream(unpackLockFile);
							final FileLock unpackLock = lockStream.getChannel().tryLock()) {
						if (unpackLock != null) {
							try {
								Preconditions.checkState(!pgDirExists.exists(),
										"unpack lock acquired but .exists file is present.");
								LOG.info("Extracting Postgres...");
								extractTxz(pgTbz.getPath(), pgDir.getPath());
								Verify.verify(pgDirExists.createNewFile(), "couldn't make .exists file");
							} catch (Exception e) {
								LOG.error("while unpacking Postgres", e);
							}
						} else {
							// the other guy is unpacking for us.
							int maxAttempts = 60;
							while (!pgDirExists.exists() && --maxAttempts > 0) {
								Thread.sleep(1000L);
							}
							Verify.verify(pgDirExists.exists(),
									"Waited 60 seconds for postgres to be unpacked but it never finished!");
						}
					} finally {
						if (unpackLockFile.exists()) {
							Verify.verify(unpackLockFile.delete(), "could not remove lock file %s",
									unpackLockFile.getAbsolutePath());
						}
					}
				}
			} catch (final IOException | NoSuchAlgorithmException e) {
				throw new ExceptionInInitializerError(e);
			} catch (final InterruptedException ie) {
				Thread.currentThread().interrupt();
				throw new ExceptionInInitializerError(ie);
			} finally {
				Verify.verify(pgTbz.delete(), "could not delete %s", pgTbz);
			}
			LOG.info("Postgres binaries at {}", pgDir);
			return pgDir;
		} finally {
			PREPARE_BINARIES_LOCK.unlock();
		}
	}

	private static void mkdirs(File dir) {
		Verify.verify(dir.mkdirs() || (dir.isDirectory() && dir.exists()), // NOPMD
				"could not create %s", dir);
	}

	/**
	 * Unpack archive compressed by tar with bzip2 compression. By default
	 * system tar is used (faster). If not found, then the java implementation
	 * takes place.
	 *
	 * @param tbzPath
	 *            The archive path.
	 * @param targetDir
	 *            The directory to extract the content to.
	 */
	private static void extractTxz(String tbzPath, String targetDir) throws IOException {
		try (InputStream in = Files.newInputStream(Paths.get(tbzPath));
				XZInputStream xzIn = new XZInputStream(in);
				TarArchiveInputStream tarIn = new TarArchiveInputStream(xzIn)) {
			TarArchiveEntry entry;

			while ((entry = tarIn.getNextTarEntry()) != null) {
				final String individualFile = entry.getName();
				final File fsObject = new File(targetDir + "/" + individualFile);

				if (entry.isSymbolicLink() || entry.isLink()) {
					Path target = FileSystems.getDefault().getPath(entry.getLinkName());
					Files.createSymbolicLink(fsObject.toPath(), target);
				} else if (entry.isFile()) {
					byte[] content = new byte[(int) entry.getSize()];
					int read = tarIn.read(content, 0, content.length);
					Verify.verify(read != -1, "could not read %s", individualFile);
					mkdirs(fsObject.getParentFile());
					try (OutputStream outputFile = new FileOutputStream(fsObject)) {
						IOUtils.write(content, outputFile);
					}
				} else if (entry.isDirectory()) {
					mkdirs(fsObject);
				} else {
					throw new UnsupportedOperationException(
							String.format("Unsupported entry found: %s", individualFile));
				}

				if (individualFile.startsWith("bin/") || individualFile.startsWith("./bin/")) {
					fsObject.setExecutable(true);
				}
			}
		}
	}

}
