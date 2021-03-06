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
package net.groboclown.idea.p4ic.server.exceptions;

import com.intellij.openapi.project.Project;
import com.perforce.p4java.exception.P4JavaException;
import net.groboclown.idea.p4ic.config.ServerConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class P4PasswordException extends P4VcsConnectionException {
    public P4PasswordException(@NotNull String message) {
        super(message);
    }

    public P4PasswordException(@NotNull P4JavaException cause) {
        super(cause);
    }

    public P4PasswordException(@NotNull P4VcsConnectionException cause) {
        super(cause.getProject(), cause.getServerConfig(), cause);
    }

    public P4PasswordException(@Nullable Project project, @Nullable ServerConfig serverConfig, @NotNull String message) {
        super(project, serverConfig, message);
    }

    public P4PasswordException(@Nullable Project project, @Nullable ServerConfig serverConfig, @NotNull P4JavaException cause) {
        super(project, serverConfig, cause);
    }
}
