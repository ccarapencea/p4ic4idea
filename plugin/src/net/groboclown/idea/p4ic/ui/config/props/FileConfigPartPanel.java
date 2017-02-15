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
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import net.groboclown.idea.p4ic.P4Bundle;
import net.groboclown.idea.p4ic.config.P4ProjectConfig;
import net.groboclown.idea.p4ic.config.part.FileDataPart;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ResourceBundle;

public class FileConfigPartPanel
        extends ConfigPartPanel<FileDataPart> {
    private static final Logger LOG = Logger.getInstance(FileConfigPartPanel.class);

    private static final String DEFAULT_FILE_NAME = ".p4config";

    private JPanel rootPanel;
    // private TextFieldWithHistoryWithBrowseButton fileLocation;
    private TextFieldWithBrowseButton fileLocation;
    private JLabel fileLocationLabel;

    FileConfigPartPanel(@NotNull Project project, @NotNull FileDataPart part) {
        super(project, part);

        fileLocation.addBrowseFolderListener(
                P4Bundle.message("configuration.connection-choice.picker.p4config"),
                P4Bundle.message("configuration.p4config.chooser"),
                project,
                new FileChooserDescriptor(true, false, false, false, false, false)
        );
        if (part.getConfigFile() == null && project.getBasePath() != null) {
            // Not in old versions
            // fileLocation.setTextAndAddToHistory(project.getBasePath());
            File configFile = new File(project.getBasePath() + File.separator + DEFAULT_FILE_NAME);
            fileLocation.getTextField().setText(configFile.getAbsolutePath());
            part.setConfigFile(configFile);
        } else if (part.getConfigFile() != null) {
            fileLocation.getTextField().setText(part.getConfigFile().getAbsolutePath());
        }

        fileLocationLabel.setLabelFor(fileLocation);
    }

    @NotNull
    @Override
    FileDataPart copyPart() {
        FileDataPart ret = new FileDataPart(getProject());
        ret.setConfigFile(getConfigPart().getConfigFile());
        return ret;
    }

    @Override
    public boolean isModified(@NotNull FileDataPart originalPart) {
        return (originalPart.getConfigFile() == null && getSelectedLocation() != null)
                || (originalPart.getConfigFile() != null &&
                !originalPart.getConfigFile().getParent().equals(getSelectedLocation()));
    }

    @Nls
    @NotNull
    @Override
    public String getTitle() {
        return P4Bundle.getString("configuration.stack.file.title");
    }

    @NotNull
    @Override
    public JPanel getRootPanel() {
        return rootPanel;
    }

    @Override
    public void updateConfigPartFromUI() {
        String newLocation = getSelectedLocation();
        if (newLocation == null) {
            getConfigPart().setConfigFile(null);
        } else {
            getConfigPart().setConfigFile(new File(newLocation));
        }
        LOG.info("Set file location to " + getConfigPart().getConfigFile());
    }

    @Nullable
    private String getSelectedLocation() {
        return fileLocation.getText();
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
        rootPanel.setLayout(new BorderLayout(4, 0));
        fileLocationLabel = new JLabel();
        fileLocationLabel.setHorizontalAlignment(11);
        this.$$$loadLabelText$$$(fileLocationLabel,
                ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.p4config"));
        rootPanel.add(fileLocationLabel, BorderLayout.WEST);
        fileLocation = new TextFieldWithBrowseButton();
        fileLocation.setText(ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle")
                .getString("config.file.location.tooltip"));
        fileLocation.setToolTipText(ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle")
                .getString("configuration.p4config.chooser"));
        rootPanel.add(fileLocation, BorderLayout.CENTER);
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
