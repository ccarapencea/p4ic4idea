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

package net.groboclown.idea.p4ic.ui.config.props;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.jgoodies.forms.layout.CellConstraints;
import com.jgoodies.forms.layout.FormLayout;
import net.groboclown.idea.p4ic.P4Bundle;
import net.groboclown.idea.p4ic.config.P4ProjectConfig;
import net.groboclown.idea.p4ic.config.part.ServerFingerprintDataPart;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ResourceBundle;

public class ServerFingerprintConfigPartPanel
        extends ConfigPartPanel<ServerFingerprintDataPart> {
    private static final Logger LOG = Logger.getInstance(ServerFingerprintConfigPartPanel.class);

    private JPanel rootPanel;
    private JTextField fingerprintField;
    private JLabel fingerprintFieldLabel;

    ServerFingerprintConfigPartPanel(@NotNull Project project, @NotNull ServerFingerprintDataPart part) {
        super(project, part);
    }

    @Override
    public void updateConfigPartFromUI() {
        getConfigPart().setServerFingerprint(fingerprintField.getText());
        LOG.info("Set server fingerprint to " + getConfigPart().getServerFingerprint());
    }

    @Nls
    @NotNull
    @Override
    public String getTitle() {
        return P4Bundle.getString("configuration.stack.server-fingerprint.title");
    }

    @NotNull
    @Override
    public JPanel getRootPanel() {
        return rootPanel;
    }

    @NotNull
    @Override
    ServerFingerprintDataPart copyPart() {
        ServerFingerprintDataPart ret = new ServerFingerprintDataPart();
        ret.setServerFingerprint(getConfigPart().getServerFingerprint());
        return ret;
    }

    @Override
    public boolean isModified(@NotNull ServerFingerprintDataPart originalPart) {
        return !originalPart.equals(getConfigPart());
    }

    {
// GUI initializer generated by IntelliJ IDEA GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
        $$$setupUI$$$();
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        rootPanel = new JPanel();
        rootPanel.setLayout(new FormLayout("fill:d:noGrow,left:4dlu:noGrow,fill:d:grow", "center:d:noGrow"));
        fingerprintFieldLabel = new JLabel();
        this.$$$loadLabelText$$$(fingerprintFieldLabel, ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle")
                .getString("configuration.properties.serverfingerprint.label"));
        CellConstraints cc = new CellConstraints();
        rootPanel.add(fingerprintFieldLabel, cc.xy(1, 1));
        fingerprintField = new JTextField();
        rootPanel.add(fingerprintField, cc.xy(3, 1, CellConstraints.FILL, CellConstraints.DEFAULT));
    }

    /**
     * @noinspection ALL
     */
    private void $$$loadLabelText$$$(JLabel component, String text) {
        StringBuffer result = new StringBuffer();
        boolean haveMnemonic = false;
        char mnemonic = '\0';
        int mnemonicIndex = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '&') {
                i++;
                if (i == text.length()) {
                    break;
                }
                if (!haveMnemonic && text.charAt(i) != '&') {
                    haveMnemonic = true;
                    mnemonic = text.charAt(i);
                    mnemonicIndex = result.length();
                }
            }
            result.append(text.charAt(i));
        }
        component.setText(result.toString());
        if (haveMnemonic) {
            component.setDisplayedMnemonic(mnemonic);
            component.setDisplayedMnemonicIndex(mnemonicIndex);
        }
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return rootPanel;
    }
}
