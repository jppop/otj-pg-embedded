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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import org.junit.Test;

public class AlreadyUnpackedPostgresTest {
	
	@Test
	public void testEmbeddedPg() throws Exception {
		
		final String system = System.getProperty("postgresql.system");
		assertNotNull("postgresql.system not defined", system);
		final String machineHardware = System.getProperty("postgresql.hardware");
		assertNotNull("postgresql.hardware not defined", machineHardware);
		Optional<String> targetPath = Optional.ofNullable(System.getProperty("postgresql.home"));
		assertTrue("postgresql.home not defined", targetPath.isPresent());
		
		// use a Bundled resolver to get 'already extracted' postgres binaries
		PgBinaryResolver resolver = new BundledPostgresBinaryResolver();
		final File pgHome = resolver.prepareBinaries(Optional.of(new File(targetPath.get())));
		
		try (
				EmbeddedPostgres pg = EmbeddedPostgres.builder()
						.setPgBinaryResolver(new ExtractedPostgresBinaryResolver())
						.setPgHome(pgHome.getAbsolutePath())
						.setDataDirectory(new File(targetPath.get(), "data"))
						.start(); 
				Connection c = pg.getPostgresDatabase().getConnection()) {
			Statement s = c.createStatement();
			ResultSet rs = s.executeQuery("SELECT 1");
			assertTrue(rs.next());
			assertEquals(1, rs.getInt(1));
			assertFalse(rs.next());
		}
	}
}
