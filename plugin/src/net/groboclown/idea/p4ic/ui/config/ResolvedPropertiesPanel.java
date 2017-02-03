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

package net.groboclown.idea.p4ic.ui.config;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.CollectionListModel;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.util.ui.AsyncProcessIcon;
import net.groboclown.idea.p4ic.P4Bundle;
import net.groboclown.idea.p4ic.background.BackgroundAwtActionRunner;
import net.groboclown.idea.p4ic.config.ClientConfig;
import net.groboclown.idea.p4ic.config.ConfigProblem;
import net.groboclown.idea.p4ic.config.P4ProjectConfig;
import net.groboclown.idea.p4ic.server.exceptions.P4InvalidConfigException;
import net.groboclown.idea.p4ic.ui.config.props.ConfigurationUpdatedListener;
import net.groboclown.idea.p4ic.ui.config.props.RequestConfigurationUpdateListener;
import net.groboclown.idea.p4ic.v2.server.connection.ConnectionUIConfiguration;
import net.groboclown.idea.p4ic.v2.server.connection.ServerConnectionManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.ResourceBundle;

public class ResolvedPropertiesPanel {
    private static final Logger LOG = Logger.getInstance(ResolvedPropertiesPanel.class);

    private JPanel rootPanel;

    private JComboBox rootDirDropdownBox;
    private DefaultComboBoxModel/*<ConfigPath>*/ rootDirDropdownBoxModel; // JDK 1.6 doesn't have generic models

    private JButton refreshResolvedPropertiesButton;
    private JTextArea resolvedValuesText;

    private JList configProblemsList;
    private AsyncProcessIcon refreshResolvedPropertiesSpinner;
    private JTabbedPane resolutionTabbedPane;
    private CollectionListModel/*<String>*/ configProblemsListModel; // JDK 1.6 doesn't have generic models

    private P4ProjectConfig lastConfig;

    private RequestConfigurationUpdateListener requestConfigurationUpdateListener;

    private final ConfigurationUpdatedListener configurationUpdatedListener = new ConfigurationUpdatedListener() {
        @Override
        public void onConfigurationUpdated(@NotNull P4ProjectConfig config) {
            refresh(config);
        }
    };

    ResolvedPropertiesPanel() {
        // Initialize GUI constant values
        $$$setupUI$$$();

        configProblemsListModel = new CollectionListModel/*<String>*/();
        configProblemsList.setModel(configProblemsListModel);

        rootDirDropdownBoxModel = new DefaultComboBoxModel/*<ConfigPath>*/();
        rootDirDropdownBox.setModel(rootDirDropdownBoxModel);
        rootDirDropdownBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refreshSelectedConfig();
            }
        });

        refreshResolvedPropertiesButton.setIcon(AllIcons.Actions.Refresh);
        refreshResolvedPropertiesButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                refresh();
            }
        });
    }

    @NotNull
    public ConfigurationUpdatedListener getConfigurationUpdatedListener() {
        return configurationUpdatedListener;
    }

    public void refresh(@Nullable P4ProjectConfig config) {
        lastConfig = config;
        refresh();
    }

    private void refresh() {
        BackgroundAwtActionRunner.runBackgroundAwtAction(refreshResolvedPropertiesSpinner,
                new BackgroundAwtActionRunner.BackgroundAwtAction<ComputedConfigResults>() {
                    @Override
                    public ComputedConfigResults runBackgroundProcess() {
                        if (requestConfigurationUpdateListener != null) {
                            requestConfigurationUpdateListener.updateConfigPartFromUI();
                        }
                        final ComputedConfigResults results = new ComputedConfigResults();
                        if (lastConfig == null) {
                            results.problemMessages.add(
                                    P4Bundle.getString("configuration.error.no-config-list")
                            );
                            return results;
                        }

                        lastConfig.refresh();
                        {
                            final Collection<ConfigProblem> problems = lastConfig.getConfigProblems();
                            results.problemMessages = new ArrayList<String>(problems.size());
                            for (ConfigProblem problem : problems) {
                                LOG.info("ConfigProblem: " + problem);
                                results.problemMessages.add(problem.getMessage());
                            }
                        }
                        final boolean tryConnection = results.problemMessages.isEmpty();

                        Collection<ClientConfig> configs = lastConfig.getClientConfigs();
                        if (configs.isEmpty()) {
                            results.problemMessages.add(
                                    P4Bundle.getString("configuration.error.no-config-list"));
                        }
                        for (ClientConfig config : configs) {
                            if (tryConnection) {
                                ConfigProblem problem = ConnectionUIConfiguration.checkConnection(config,
                                        ServerConnectionManager.getInstance(), false);
                                if (problem != null) {
                                    results.problemMessages.add(problem.getMessage());
                                }
                                // We can have a connection without a client, for testing purposes,
                                // but to actually use it, we need a client.
                                if (!config.isWorkspaceCapable()) {
                                    results.problemMessages.add(
                                            P4Bundle.getString("error.config.no-client"));
                                }
                            }
                            if (config.getProjectSourceDirs().isEmpty()) {
                                results.problemMessages.add(
                                        P4Bundle.message("client.root.non-existent",
                                                config.getProject().getBaseDir()));
                                if (config.getProject().getBaseDir() != null) {
                                    results.configs.add(new ConfigPath(config, config.getProject().getBaseDir()));
                                }
                            }
                            for (VirtualFile virtualFile : config.getProjectSourceDirs()) {
                                if (virtualFile != null) {
                                    results.configs.add(new ConfigPath(config, virtualFile));
                                }
                            }
                        }
                        return results;
                    }

                    @Override
                    public void runAwtProcess(ComputedConfigResults results) {
                        if (results.problemMessages.isEmpty()) {
                            configProblemsListModel.removeAll();
                            // No errors, so show the resolved properties
                            resolutionTabbedPane.setSelectedIndex(0);
                        } else {
                            configProblemsListModel.replaceAll(results.problemMessages);
                            // Errors, so show the problems
                            resolutionTabbedPane.setSelectedIndex(1);
                        }
                        if (results.configs == null || results.configs.isEmpty()) {
                            rootDirDropdownBoxModel.removeAllElements();
                            rootDirDropdownBox.setEnabled(false);
                        } else {
                            rootDirDropdownBox.setEnabled(true);
                            rootDirDropdownBoxModel.removeAllElements();
                            for (ConfigPath config : results.configs) {
                                rootDirDropdownBoxModel.addElement(config);
                            }
                            rootDirDropdownBox.setSelectedIndex(0);
                        }

                        refreshSelectedConfig();
                    }
                });
    }


    // called in Awt
    private void refreshSelectedConfig() {
        ApplicationManager.getApplication().assertIsDispatchThread();

        if (rootDirDropdownBoxModel.getSize() <= 0) {
            resolvedValuesText.setText(P4Bundle.message("config.display.properties.no_path"));
        } else {
            Object selected = rootDirDropdownBoxModel.getSelectedItem();
            if (selected == null || !(selected instanceof ConfigPath)) {
                selected = rootDirDropdownBoxModel.getElementAt(0);
                if (selected == null || !(selected instanceof ConfigPath)) {
                    throw new IllegalStateException("Resolved properties selected item is not ConfigPath: " + selected);
                }
                rootDirDropdownBoxModel.setSelectedItem(selected);
            }
            showResolvedPropertiesText((ConfigPath) selected);
        }
    }

    private void showResolvedPropertiesText(@NotNull final ConfigPath selected) {
        // This can load values from a file, so put in the background.
        BackgroundAwtActionRunner.runBackgroundAwtAction(refreshResolvedPropertiesSpinner,
                new BackgroundAwtActionRunner.BackgroundAwtAction<String>() {
                    @Override
                    public String runBackgroundProcess() {
                        Map<String, String> props = selected.config.toProperties();
                        ArrayList<String> keys = new ArrayList<String>(props.keySet());
                        Collections.sort(keys);
                        StringBuilder sb = new StringBuilder();
                        for (String key : keys) {
                            sb.append(key).append('=').append(props.get(key)).append('\n');
                        }
                        return sb.toString();
                    }

                    @Override
                    public void runAwtProcess(String value) {
                        resolvedValuesText.setText(value);
                    }
                });
    }

    public void setRequestConfigurationUpdateListener(
            @NotNull RequestConfigurationUpdateListener requestConfigurationUpdateListener) {
        this.requestConfigurationUpdateListener = requestConfigurationUpdateListener;
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        createUIComponents();
        rootPanel = new JPanel();
        rootPanel.setLayout(new BorderLayout(0, 0));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new BorderLayout(0, 0));
        rootPanel.add(panel1, BorderLayout.NORTH);
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new FlowLayout(FlowLayout.CENTER, 5, 5));
        panel1.add(panel2, BorderLayout.EAST);
        panel2.add(refreshResolvedPropertiesSpinner);
        refreshResolvedPropertiesButton = new JButton();
        refreshResolvedPropertiesButton.setText("");
        refreshResolvedPropertiesButton.setToolTipText(ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle")
                .getString("configuration.resolve.refresh.tooltip"));
        panel2.add(refreshResolvedPropertiesButton);
        final JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(1, 3, new Insets(0, 0, 0, 0), -1, -1));
        panel1.add(panel3, BorderLayout.CENTER);
        rootDirDropdownBox = new JComboBox();
        panel3.add(rootDirDropdownBox,
                new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                        GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0,
                        false));
        final JLabel label1 = new JLabel();
        this.$$$loadLabelText$$$(label1,
                ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.resolved.path"));
        panel3.add(label1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        resolutionTabbedPane = new JTabbedPane();
        rootPanel.add(resolutionTabbedPane, BorderLayout.CENTER);
        final JPanel panel4 = new JPanel();
        panel4.setLayout(new BorderLayout(0, 0));
        resolutionTabbedPane.addTab(ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle")
                .getString("configurations.resolved-values.tab"), panel4);
        panel4.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4), null));
        final JScrollPane scrollPane1 = new JScrollPane();
        scrollPane1.setVerticalScrollBarPolicy(22);
        panel4.add(scrollPane1, BorderLayout.CENTER);
        resolvedValuesText = new JTextArea();
        resolvedValuesText.setFont(UIManager.getFont("TextArea.font"));
        resolvedValuesText.setRows(8);
        scrollPane1.setViewportView(resolvedValuesText);
        final JPanel panel5 = new JPanel();
        panel5.setLayout(new BorderLayout(0, 0));
        resolutionTabbedPane.addTab(ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle")
                .getString("configuration.problems-list.tab"), panel5);
        panel5.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4), null));
        final JScrollPane scrollPane2 = new JScrollPane();
        scrollPane2.setVisible(true);
        panel5.add(scrollPane2, BorderLayout.CENTER);
        configProblemsList = new JList();
        scrollPane2.setViewportView(configProblemsList);
        label1.setLabelFor(rootDirDropdownBox);
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

    private static class ComputedConfigResults {
        ArrayList<String> problemMessages = new ArrayList<String>();
        ArrayList<ConfigPath> configs = new ArrayList<ConfigPath>();
    }


    private static class ConfigPath {
        final ClientConfig config;
        final VirtualFile file;

        private ConfigPath(@NotNull ClientConfig config, @NotNull VirtualFile virtualFile) {
            this.config = config;
            this.file = virtualFile;
        }

        @Override
        public String toString() {
            return file.getPath();
        }
    }


    // -----------------------------------------------------------------------
    // UI form stuff

    private void createUIComponents() {
        // Add custom component construction here.
        refreshResolvedPropertiesSpinner = new AsyncProcessIcon("Refresh Resolved Progress");
        refreshResolvedPropertiesSpinner.setName("Refresh Resolved Progress");
        refreshResolvedPropertiesSpinner.setVisible(false);
    }


}
