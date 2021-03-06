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

import com.intellij.openapi.vcs.VcsException;

/**
 * A wrapper around a {@link VcsException} that indicates that
 * there was an error, so there was no data, but that it was
 * already handled, and the user shouldn't be prompted with
 * another error.
 */
public class HandledVcsException extends VcsException {
    public HandledVcsException(VcsException throwable) {
        super(throwable);
    }
}
