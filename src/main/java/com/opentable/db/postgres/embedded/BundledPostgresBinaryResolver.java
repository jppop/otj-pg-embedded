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
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tukaani.xz.XZInputStream;

import com.google.common.base.Preconditions;
import com.google.common.base.Verify;

/**
 * Resolves pre-bundled binaries from within the JAR file.
 */
public final class BundledPostgresBinaryResolver implements PgBinaryResolver, BundleResolver {

    private static final Logger LOG = LoggerFactory.getLogger(BundledPostgresBinaryResolver.class);

    private static final Lock PREPARE_BINARIES_LOCK = new ReentrantLock();
    private static final String LOCK_FILE_NAME = "epg-lock";
    private static final String TMP_DIR_LOC = System.getProperty("java.io.tmpdir");
    private static final String PG_VERSION = System.getProperty("postgresql.version", "9.6.0-1");
    private static final File TMP_DIR = new File(TMP_DIR_LOC, "embedded-pg");

    private final String pgVersion;

    public BundledPostgresBinaryResolver() {
        this(PG_VERSION);
    }

    public BundledPostgresBinaryResolver(String version) {
        this.pgVersion = version;
    }

    @Override
    public File getPgBundle(String version, String system, String machineHardware) {
        URL resource = EmbeddedPostgres.class
                .getResource(format("/postgresql-%s-%s-%s.txz", version, system, machineHardware));
        if (resource == null) {
            return null;
        }
        else {
            File f;
            try {
                f = new File(resource.toURI());
            }
            catch (URISyntaxException e) {
                f = new File(resource.getPath());
            }
            return f;
        }
    }

    @Override
    public File prepareBinaries(final Optional<File> targetPath) {
        return prepareBinaries(getOS(), getArchitecture(), targetPath);
    }

    public File prepareBinaries(final String system, final String machineHardware,
            final Optional<File> targetPath) {
        PREPARE_BINARIES_LOCK.lock();
        try {

            LOG.info("Detected a {} {} system", system, machineHardware);
            File pgDir;
            final File pgBundle = getPgBundle(this.pgVersion, system, machineHardware);

            if (pgBundle == null) {
                throw new IllegalStateException(
                        "No Postgres binary found for " + system + " / " + machineHardware);
            }

            if (targetPath.isPresent()) {
                pgDir = targetPath.get();
            }
            else {
                pgDir = new File(TMP_DIR, String.format("PG-%s", pgVersion));
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
                            extractTxz(pgBundle.getPath(), pgDir.getPath());
                            Verify.verify(pgDirExists.createNewFile(),
                                    "couldn't make .exists file");
                        }
                        catch (Exception e) {
                            LOG.error("while unpacking Postgres", e);
                            throw e;
                        }
                    }
                    else {
                        // the other guy is unpacking for us.
                        int maxAttempts = 60;
                        while (!pgDirExists.exists() && --maxAttempts > 0) {
                            Thread.sleep(1000L);
                        }
                        Verify.verify(pgDirExists.exists(),
                                "Waited 60 seconds for postgres to be unpacked but it never finished!");
                    }
                }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ExceptionInInitializerError(ie);
                }
                catch (IOException e) {
                    throw new ExceptionInInitializerError(e);
                }
                finally {
                    if (unpackLockFile.exists()) {
                        Verify.verify(unpackLockFile.delete(), "could not remove lock file %s",
                                unpackLockFile.getAbsolutePath());
                    }
                }
            }
            LOG.info("Postgres binaries at {}", pgDir);
            return pgDir;
        }
        finally {
            PREPARE_BINARIES_LOCK.unlock();
        }
    }

    private static void mkdirs(File dir) {
        Verify.verify(dir.mkdirs() || (dir.isDirectory() && dir.exists()), // NOPMD
                "could not create %s", dir);
    }

    /**
     * Unpack archive compressed by tar with bzip2 compression. By default system tar is used
     * (faster). If not found, then the java implementation takes place.
     *
     * @param tbzPath
     *        The archive path.
     * @param targetDir
     *        The directory to extract the content to.
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
                }
                else if (entry.isFile()) {
                    byte[] content = new byte[(int) entry.getSize()];
                    int read = tarIn.read(content, 0, content.length);
                    Verify.verify(read != -1, "could not read %s", individualFile);
                    mkdirs(fsObject.getParentFile());
                    try (OutputStream outputFile = new FileOutputStream(fsObject)) {
                        IOUtils.write(content, outputFile);
                    }
                }
                else if (entry.isDirectory()) {
                    mkdirs(fsObject);
                }
                else {
                    throw new UnsupportedOperationException(
                            String.format("Unsupported entry found: %s", individualFile));
                }

                if (individualFile.startsWith("bin/") || individualFile.startsWith("./bin/")) {
                    fsObject.setExecutable(true);
                }
            }
        }
    }

    /**
     * Get current operating system string. The string is used in the appropriate postgres binary
     * name.
     *
     * @return Current operating system string.
     */
    private static String getOS() {
        if (SystemUtils.IS_OS_WINDOWS) {
            return "Windows";
        }
        if (SystemUtils.IS_OS_MAC_OSX) {
            return "Darwin";
        }
        if (SystemUtils.IS_OS_LINUX) {
            return "Linux";
        }
        throw new UnsupportedOperationException("Unknown OS " + SystemUtils.OS_NAME);
    }

    /**
     * Get the machine architecture string. The string is used in the appropriate postgres binary
     * name.
     *
     * @return Current machine architecture string.
     */
    private static String getArchitecture() {
        return "amd64".equals(SystemUtils.OS_ARCH) ? "x86_64" : SystemUtils.OS_ARCH;
    }

}
