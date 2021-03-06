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
package net.groboclown.idea.p4ic.actions;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.actions.StandardVcsGroup;
import net.groboclown.idea.p4ic.extension.P4Vcs;

public class P4Menu extends StandardVcsGroup {
    @Override
    public AbstractVcs getVcs(Project project) {
        return P4Vcs.getInstance(project);
    }

    @Override
    public String getVcsName(final Project project) {
        return P4Vcs.VCS_NAME;
    }
}
