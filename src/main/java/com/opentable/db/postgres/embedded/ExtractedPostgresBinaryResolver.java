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

import java.io.File;
import java.util.Optional;

import com.google.common.base.Verify;

public class ExtractedPostgresBinaryResolver implements PgBinaryResolver {

    @Override
    public File prepareBinaries(Optional<File> targetPath) {
        if (targetPath.isPresent()) {
            // nothing to do except verifications
            final File pgHome = targetPath.get();
            Verify.verify(pgHome.exists() && pgHome.isDirectory(),
                    "%s does not exist or is not a directory", pgHome.getAbsolutePath());
            return pgHome;

        }
        else {
            PgBinaryResolver resolver = new BundledPostgresBinaryResolver();
            return resolver.prepareBinaries(targetPath);
        }
    }

}
